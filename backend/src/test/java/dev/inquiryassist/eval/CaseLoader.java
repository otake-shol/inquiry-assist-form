package dev.inquiryassist.eval;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 評価の例のファイル（{@code evals/cases/<スキル>.json}、{@code evals/private/<スキル>.json}）を読み、実行の前に検査する。
 *
 * <p>知らない条件の名前や、必須の項目の欠けがあれば、実行を始める前にすべての誤りをまとめてエラーにする。
 * 書き間違えた条件が、確かめられないまま通ることを防ぐため。
 */
final class CaseLoader {

    /** 読み込んだ例と、受付の例が使うシステムの候補。 */
    record Loaded(List<EvalCase> cases, List<SystemOption> systems) {}

    /** 文章（言い直しの文章、説明）に使える条件。 */
    private static final Set<String> TEXT_CONDITIONS = Set.of(
            "mustInclude", "mustIncludeAny", "mustNotInclude", "mustNotMatch", "minSentences", "maxSentences", "style");

    private static final Set<String> RECEPTION_CONDITIONS =
            Set.of("type", "typeAnyOf", "systemId", "systemIdAnyOf");

    private static final Set<String> INTERVIEWER_CONDITIONS = Set.of(
            "minQuestions", "maxQuestions", "forbidLocationQuestion", "forbidEngineerTerms", "mustNotMatch");

    private static final Set<String> EXPLAINER_ONLY_CONDITIONS = Set.of("answersQuestion", "mustAcknowledgeUnknown");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CaseLoader() {}

    /**
     * 条件の名前の一覧を返す（スキルごと）。
     *
     * @param skill スキル
     * @return 使える条件の名前
     */
    static Set<String> allowedConditions(EvalSkill skill) {
        Set<String> names = new HashSet<>();
        switch (skill) {
            case RECEPTION -> names.addAll(RECEPTION_CONDITIONS);
            case INTERVIEWER -> names.addAll(INTERVIEWER_CONDITIONS);
            case RESTATER -> names.addAll(TEXT_CONDITIONS);
            case EXPLAINER -> {
                names.addAll(TEXT_CONDITIONS);
                names.addAll(EXPLAINER_ONLY_CONDITIONS);
            }
        }
        return names;
    }

    /**
     * 例を読み込む。公開の例は、対象のスキルのファイルがなければエラー。非公開の例は、ファイルがなければ0件として扱う。
     *
     * @param dirs 読み込む場所（公開の {@code evals/cases}、非公開の {@code evals/private}）と、必須かどうか
     * @param skills 読み込むスキル
     * @return 読み込んだ例
     * @throws EvalConfigException 誤りがある場合（すべての誤りをまとめて伝える）
     */
    static Loaded load(List<Source> dirs, List<EvalSkill> skills) {
        List<String> errors = new ArrayList<>();
        List<EvalCase> cases = new ArrayList<>();
        List<SystemOption> systems = new ArrayList<>();
        for (EvalSkill skill : skills) {
            Set<String> ids = new HashSet<>();
            for (Source source : dirs) {
                Path file = source.dir().resolve(skill.id() + ".json");
                if (!Files.exists(file)) {
                    if (source.required()) {
                        errors.add(file + ": 例のファイルがありません");
                    }
                    continue;
                }
                readFile(file, skill, ids, cases, systems, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new EvalConfigException(errors);
        }
        return new Loaded(cases, systems);
    }

    /** 例の置き場所。 */
    record Source(Path dir, boolean required) {}

    private static void readFile(
            Path file,
            EvalSkill skill,
            Set<String> ids,
            List<EvalCase> cases,
            List<SystemOption> systems,
            List<String> errors) {
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(file));
        } catch (IOException | JacksonException e) {
            errors.add(file + ": 読み込めません（" + e.getMessage() + "）");
            return;
        }
        String where = file.toString();
        if (!root.path("cases").isArray()) {
            errors.add(where + ": cases の配列がありません");
            return;
        }
        if (skill == EvalSkill.RECEPTION) {
            readSystems(root, where, systems, errors);
        }
        for (JsonNode node : root.path("cases")) {
            String id = node.path("id").isString() ? node.path("id").asString() : null;
            String label = where + " [" + (id != null ? id : "id なし") + "]";
            if (id == null || id.isBlank()) {
                errors.add(label + ": id は必須");
                continue;
            }
            if (!ids.add(id)) {
                errors.add(label + ": id が重複しています");
            }
            String split = node.path("split").isString() ? node.path("split").asString() : null;
            if (!EvalCase.DEV.equals(split) && !EvalCase.HOLDOUT.equals(split)) {
                errors.add(label + ": split は dev か holdout");
            }
            int before = errors.size();
            validateInputs(node, skill, label, errors);
            validateExpect(node.path("expect"), skill, label, errors);
            if (errors.size() == before && split != null) {
                cases.add(new EvalCase(skill, id, split, node, where));
            }
        }
    }

    private static void readSystems(JsonNode root, String where, List<SystemOption> systems, List<String> errors) {
        JsonNode array = root.path("systems");
        if (!array.isArray() || array.isEmpty()) {
            errors.add(where + ": 受付の例には systems（id と label の配列）が必要");
            return;
        }
        for (JsonNode s : array) {
            if (!s.path("id").isString() || !s.path("label").isString()) {
                errors.add(where + ": systems の要素には id と label が必要");
                continue;
            }
            SystemOption option = new SystemOption(s.path("id").asString(), s.path("label").asString());
            if (!systems.contains(option)) {
                systems.add(option);
            }
        }
    }

    private static void validateInputs(JsonNode node, EvalSkill skill, String label, List<String> errors) {
        switch (skill) {
            case RECEPTION -> {
                requireString(node, "utterance", label, errors);
                requireKey(node, "selectedSystemId", label, errors);
            }
            case INTERVIEWER -> {
                requireString(node, "utterance", label, errors);
                requireType(node, label, errors);
                requireKey(node, "systemLabel", label, errors);
            }
            case RESTATER -> {
                requireString(node, "utterance", label, errors);
                requireType(node, label, errors);
                requireKey(node, "systemLabel", label, errors);
                if (!node.path("answers").isArray()) {
                    errors.add(label + ": answers は配列で必須（なければ空の配列）");
                } else {
                    for (JsonNode a : node.path("answers")) {
                        if (!a.path("question").isString() || !a.has("answer")) {
                            errors.add(label + ": answers の要素には question と answer（null 可）が必要");
                        }
                    }
                }
                if (!node.path("corrections").isArray()) {
                    errors.add(label + ": corrections は配列で必須（なければ空の配列）");
                }
            }
            case EXPLAINER -> {
                requireString(node, "question", label, errors);
                requireType(node, label, errors);
                if (!node.path("facts").isArray() || node.path("facts").isEmpty()) {
                    errors.add(label + ": facts は1件以上の配列で必須");
                }
            }
        }
    }

    private static void requireString(JsonNode node, String field, String label, List<String> errors) {
        if (!node.path(field).isString() || node.path(field).asString().isBlank()) {
            errors.add(label + ": " + field + " は必須");
        }
    }

    private static void requireKey(JsonNode node, String field, String label, List<String> errors) {
        if (!node.has(field)) {
            errors.add(label + ": " + field + " は必須（なければ null）");
        }
    }

    private static void requireType(JsonNode node, String label, List<String> errors) {
        String value = node.path("type").isString() ? node.path("type").asString() : "";
        try {
            InquiryType.valueOf(value);
        } catch (IllegalArgumentException e) {
            errors.add(label + ": type が不正: " + value);
        }
    }

    private static void validateExpect(JsonNode expect, EvalSkill skill, String label, List<String> errors) {
        if (!expect.isObject() || expect.isEmpty()) {
            errors.add(label + ": expect は必須（条件が1つ以上）");
            return;
        }
        Set<String> allowed = allowedConditions(skill);
        for (String name : expect.propertyNames()) {
            if (!allowed.contains(name)) {
                errors.add(label + ": 知らない条件の名前: " + name + "（" + skill.id() + " で使える条件: "
                        + allowed.stream().sorted().toList() + "）");
                continue;
            }
            validateValue(name, expect.get(name), label, errors);
        }
        if (skill == EvalSkill.RECEPTION) {
            if (!expect.has("type") && !expect.has("typeAnyOf")) {
                errors.add(label + ": type か typeAnyOf が必要");
            }
            if (!expect.has("systemId") && !expect.has("systemIdAnyOf")) {
                errors.add(label + ": systemId か systemIdAnyOf が必要（null は「推測しない」が正解であることを表す）");
            }
        }
    }

    private static void validateValue(String name, JsonNode value, String label, List<String> errors) {
        String where = label + ": " + name;
        switch (name) {
            case "mustInclude", "mustNotInclude" -> requireStringArray(value, where, errors);
            case "mustIncludeAny" -> {
                if (!value.isArray()) {
                    errors.add(where + " は語の配列の配列");
                } else {
                    value.forEach(group -> requireStringArray(group, where, errors));
                }
            }
            case "mustNotMatch" -> {
                requireStringArray(value, where, errors);
                if (value.isArray()) {
                    for (JsonNode pattern : value) {
                        try {
                            Pattern.compile(pattern.asString(""));
                        } catch (PatternSyntaxException e) {
                            errors.add(where + " の正規表現が不正: " + pattern.asString(""));
                        }
                    }
                }
            }
            case "minSentences", "maxSentences", "minQuestions", "maxQuestions" -> {
                if (!value.isInt() || value.asInt() < 0) {
                    errors.add(where + " は0以上の整数");
                }
            }
            case "style" -> {
                if (!value.isString() || !Set.of("plain", "polite").contains(value.asString())) {
                    errors.add(where + " は plain か polite");
                }
            }
            case "type" -> requireTypeValue(value, where, errors);
            case "typeAnyOf" -> {
                if (!value.isArray() || value.isEmpty()) {
                    errors.add(where + " は種類の配列");
                } else {
                    value.forEach(v -> requireTypeValue(v, where, errors));
                }
            }
            case "systemId" -> {
                if (!value.isNull() && !value.isString()) {
                    errors.add(where + " は文字列か null");
                }
            }
            case "systemIdAnyOf" -> {
                if (!value.isArray() || value.isEmpty()) {
                    errors.add(where + " は文字列か null の配列");
                } else {
                    for (JsonNode v : value) {
                        if (!v.isNull() && !v.isString()) {
                            errors.add(where + " の要素は文字列か null");
                        }
                    }
                }
            }
            case "forbidLocationQuestion", "forbidEngineerTerms", "answersQuestion", "mustAcknowledgeUnknown" -> {
                if (!value.isBoolean()) {
                    errors.add(where + " は true か false");
                }
            }
            default -> { }
        }
    }

    private static void requireStringArray(JsonNode value, String where, List<String> errors) {
        if (!value.isArray() || value.isEmpty()) {
            errors.add(where + " は語の配列（1件以上）");
            return;
        }
        for (JsonNode v : value) {
            if (!v.isString() || v.asString().isEmpty()) {
                errors.add(where + " の要素は空でない文字列");
            }
        }
    }

    private static void requireTypeValue(JsonNode value, String where, List<String> errors) {
        try {
            InquiryType.valueOf(value.asString(""));
        } catch (IllegalArgumentException e) {
            errors.add(where + " の種類が不正: " + value.asString(""));
        }
    }
}
