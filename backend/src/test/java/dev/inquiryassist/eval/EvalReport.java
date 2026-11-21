package dev.inquiryassist.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 評価の結果を、人が読む {@code report.md} と、比べるための {@code results.json} にして書く。
 *
 * <p>{@code holdout} の例は、出力を出さない（{@code report.md} にも {@code results.json} にも）。出力を読んで直す
 * 材料にしないため。落ちた条件は名前だけを出し、理由（出力の一部を含みうる）は出さない。
 */
final class EvalReport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Map<String, Object> conditions;
    private final List<CaseResult> results;
    private final Path compareWith;

    EvalReport(Map<String, Object> conditions, List<CaseResult> results, Path compareWith) {
        this.conditions = conditions;
        this.results = results;
        this.compareWith = compareWith;
    }

    /**
     * 結果のフォルダの名前を返す。
     *
     * @param now 実行日時
     * @param label 名前に付ける語（なければ null）
     * @return フォルダ名
     */
    static String folderName(LocalDateTime now, String label) {
        return FOLDER_TIME.format(now) + (label == null || label.isBlank() ? "" : "-" + label);
    }

    /**
     * 結果を書く。
     *
     * @param dir 書き込み先のフォルダ（なければ作る）
     * @throws IOException 書き込めない場合
     */
    void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), markdown());
        Files.writeString(dir.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json()));
    }

    List<String> skills() {
        return results.stream().map(CaseResult::skill).distinct().toList();
    }

    SkillSummary summaryOf(String skill) {
        return SkillSummary.of(skill, results.stream().filter(r -> r.skill().equals(skill)).toList());
    }

    // ---- results.json ----

    Map<String, Object> json() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("conditions", conditions);
        Map<String, Object> skills = new LinkedHashMap<>();
        for (String skill : skills()) {
            SkillSummary s = summaryOf(skill);
            Map<String, Object> entry = new LinkedHashMap<>();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("runs", s.runs());
            summary.put("passed", s.passed());
            summary.put("passRate", s.passRate());
            summary.put("callFailures", s.callFailures());
            summary.put("failedByCondition", s.failedByCondition());
            summary.put("medianMillis", s.medianMillis());
            summary.put("maxMillis", s.maxMillis());
            summary.put("inputTokens", s.inputTokens());
            summary.put("outputTokens", s.outputTokens());
            summary.put("caseCount", s.caseCount());
            summary.put("allPassedCases", s.allPassedCases());
            summary.put("anyFailedCases", s.anyFailedCases());
            entry.put("summary", summary);
            List<Map<String, Object>> perCase = new ArrayList<>();
            SkillSummary.perCase(results.stream().filter(r -> r.skill().equals(skill)).toList()).forEach((id, counts) -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("caseId", id);
                c.put("passedRuns", counts[0]);
                c.put("runs", counts[1]);
                perCase.add(c);
            });
            entry.put("cases", perCase);
            List<Map<String, Object>> list = new ArrayList<>();
            for (CaseResult r : results) {
                if (!r.skill().equals(skill)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("caseId", r.caseId());
                item.put("split", r.split());
                item.put("run", r.run());
                item.put("passed", r.passed());
                item.put("callFailed", r.callFailed());
                item.put("failedConditions", r.failures().stream().map(Failure::condition).distinct().toList());
                item.put("input", r.input());
                item.put("millis", r.millis());
                item.put("inputTokens", r.inputTokens());
                item.put("outputTokens", r.outputTokens());
                if (!r.isHoldout()) {
                    item.put("failureDetails", r.failures().stream()
                            .map(f -> Map.of("condition", f.condition(), "detail", f.detail()))
                            .toList());
                    item.put("output", r.output());
                }
                list.add(item);
            }
            entry.put("results", list);
            skills.put(skill, entry);
        }
        root.put("skills", skills);
        return root;
    }

    // ---- report.md ----

    String markdown() {
        StringBuilder md = new StringBuilder("# スキルの評価\n\n## 1. 条件\n\n");
        conditions.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));

        md.append("\n## 2. スキルごとのまとめ\n\n");
        md.append("通った割合は延べ（繰り返した回数も1回ずつ数える）。\n\n");
        md.append("| スキル | 例 | すべての回で通った例 | 1回でも落ちた例 | 実行(延べ) | 通った(延べ) | 割合(延べ) | 呼び出しの失敗 | 応答時間 中央値(ms) | 最大(ms) | 入力トークン | 出力トークン |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String skill : skills()) {
            SkillSummary s = summaryOf(skill);
            md.append(String.format(
                    "| %s | %d | %d | %d | %d | %d | %s | %d | %d | %d | %d | %d |%n",
                    skill, s.caseCount(), s.allPassedCases(), s.anyFailedCases(), s.runs(), s.passed(), s.percent(),
                    s.callFailures(), s.medianMillis(), s.maxMillis(), s.inputTokens(), s.outputTokens()));
        }
        if (hasRepeats()) {
            md.append("\n例ごとの通った回数（通った回数／繰り返した回数）:\n\n");
            for (String skill : skills()) {
                md.append("- ").append(skill).append(": ");
                md.append(SkillSummary.perCase(results.stream().filter(r -> r.skill().equals(skill)).toList()).entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1])
                        .collect(Collectors.joining("、")));
                md.append('\n');
            }
        }
        md.append("\n条件ごとの落ちた数:\n\n");
        for (String skill : skills()) {
            Map<String, Integer> byCondition = summaryOf(skill).failedByCondition();
            md.append("- ").append(skill).append(": ")
                    .append(byCondition.isEmpty() ? "なし" : byCondition.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining("、")))
                    .append('\n');
        }

        md.append("\n## 3. 落ちた例\n\n");
        List<CaseResult> failed = results.stream().filter(r -> !r.passed()).toList();
        if (failed.isEmpty()) {
            md.append("なし\n\n");
        }
        for (CaseResult r : failed) {
            md.append("### ").append(r.skill()).append(" / ").append(r.caseId()).append(" (").append(r.split());
            md.append(r.run() > 1 || hasRepeats() ? ", " + r.run() + "回目" : "").append(")\n\n");
            md.append("- 入力: ").append(r.input()).append('\n');
            if (r.isHoldout()) {
                // holdout は出力と理由を出さない。落ちた条件の名前だけ
                md.append("- 落ちた条件: ").append(r.failures().stream().map(Failure::condition).distinct().collect(Collectors.joining("、"))).append('\n');
                md.append("- （holdout のため、出力は出しません）\n\n");
            } else {
                if (!r.output().isEmpty()) {
                    md.append("- 出力: ").append(JSON.writeValueAsString(r.output())).append('\n');
                }
                r.failures().forEach(f -> md.append("- 落ちた条件 ").append(f.condition()).append(": ").append(f.detail()).append('\n'));
                md.append('\n');
            }
        }

        List<CaseResult> dev = results.stream().filter(r -> !r.isHoldout()).toList();
        if (!dev.isEmpty()) {
            md.append("## 4. すべての例の出力（dev）\n\n");
            for (CaseResult r : dev) {
                md.append("- ").append(r.skill()).append(" / ").append(r.caseId()).append(r.passed() ? "（通った）" : "（落ちた）").append(": ");
                md.append(r.output().isEmpty() ? "（出力なし）" : JSON.writeValueAsString(r.output())).append('\n');
            }
        }
        if (compareWith != null) {
            md.append('\n').append(comparison());
        }
        return md.toString();
    }

    private boolean hasRepeats() {
        return results.stream().anyMatch(r -> r.run() > 1);
    }

    // ---- 比較 ----

    /** 前回の結果（{@code results.json}）との差を文章にする。通った割合の差と、結果が変わった例。 */
    String comparison() {
        StringBuilder md = new StringBuilder("## 5. 前回との比較\n\n- 比べる相手: ").append(compareWith).append("\n\n");
        JsonNode previous;
        try {
            previous = JSON.readTree(Files.readString(compareWith.resolve("results.json")));
        } catch (IOException | RuntimeException e) {
            return md.append("前回の結果を読み込めませんでした: ").append(e.getMessage()).append('\n').toString();
        }
        JsonNode before = previous.path("conditions");
        md.append("- 前回の条件: プロンプトの版 ").append(before.path("promptVersion").asString("?"))
                .append("、モデル ").append(before.path("model").asString("?")).append('\n');
        md.append("- 今回の条件: プロンプトの版 ").append(conditions.get("promptVersion")).append("、モデル ").append(conditions.get("model")).append("\n\n");
        md.append("| スキル | 前回 | 今回 | 差 |\n| --- | --- | --- | --- |\n");
        List<String> changes = new ArrayList<>();
        for (String skill : skills()) {
            SkillSummary now = summaryOf(skill);
            JsonNode prevSkill = previous.path("skills").path(skill);
            if (prevSkill.isMissingNode()) {
                md.append("| ").append(skill).append(" | （なし） | ").append(now.percent()).append(" | - |\n");
                continue;
            }
            double prevRate = prevSkill.path("summary").path("passRate").asDouble(0);
            md.append(String.format("| %s | %.0f%% | %s | %+.0f pt |%n", skill, prevRate * 100, now.percent(), (now.passRate() - prevRate) * 100));
            // 例ごとの {通った回数, 実行した回数}。繰り返しがある場合は、通った回数の変化で示す
            Map<String, int[]> prevCounts = new LinkedHashMap<>();
            for (JsonNode r : prevSkill.path("results")) {
                int[] c = prevCounts.computeIfAbsent(r.path("caseId").asString(), k -> new int[2]);
                c[0] += r.path("passed").asBoolean() ? 1 : 0;
                c[1]++;
            }
            Map<String, int[]> nowCounts = SkillSummary.perCase(results.stream().filter(r -> r.skill().equals(skill)).toList());
            boolean repeated = prevCounts.values().stream().anyMatch(c -> c[1] > 1) || nowCounts.values().stream().anyMatch(c -> c[1] > 1);
            nowCounts.forEach((id, now2) -> {
                int[] was = prevCounts.get(id);
                if (was == null) {
                    return;
                }
                if (repeated) {
                    if (was[0] != now2[0] || was[1] != now2[1]) {
                        changes.add("通った回数の変化: " + skill + " / " + id + ": " + was[0] + "/" + was[1] + " → " + now2[0] + "/" + now2[1]);
                    }
                } else if ((was[0] == 1) != (now2[0] == 1)) {
                    changes.add((now2[0] == 1 ? "通るようになった: " : "落ちるようになった: ") + skill + " / " + id);
                }
            });
        }
        md.append("\n結果が変わった例:\n\n");
        if (changes.isEmpty()) {
            md.append("- なし\n");
        }
        changes.forEach(c -> md.append("- ").append(c).append('\n'));
        return md.toString();
    }
}
