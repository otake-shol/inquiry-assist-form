package dev.inquiryassist.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 評価の実行条件（コマンドラインの引数）。
 *
 * @param root リポジトリのルート（{@code evals/} がある場所）
 * @param skills 測るスキル
 * @param splits 測る区分
 * @param cases 例の置き場所（{@code public}、{@code private}、{@code all}）
 * @param promptVersion プロンプトの版
 * @param model モデル。指定がなければ {@code local-ai} の設定
 * @param think 考える過程を出力させるか。指定がなければスキルごとの設定
 * @param temperature 出力のばらつき。指定がなければ 0
 * @param repeat 1つの例を繰り返す回数
 * @param label 結果のフォルダの名前に付ける語
 * @param compareWith 比べる相手の結果のフォルダ
 * @param ollamaUrl Ollama の URL
 * @param seed 乱数の種（製品の既定と同じ 42）
 */
record EvalConfig(
        Path root,
        List<EvalSkill> skills,
        List<String> splits,
        String cases,
        String promptVersion,
        String model,
        Boolean think,
        Double temperature,
        int repeat,
        String label,
        Path compareWith,
        String ollamaUrl,
        int seed) {

    static final String DEFAULT_MODEL = "qwen3.6:35b";
    static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";

    private static final Set<String> KNOWN =
            Set.of("skill", "split", "cases", "promptVersion", "model", "think", "temperature", "repeat", "label", "compareWith", "ollamaUrl", "seed");

    /**
     * {@code --名前=値} の引数から実行条件を作る。知らない名前や不正な値は、まとめてエラーにする。
     *
     * @param root リポジトリのルート
     * @param args 引数
     * @return 実行条件
     * @throws EvalConfigException 引数に誤りがある場合
     */
    static EvalConfig parse(Path root, List<String> args) {
        List<String> errors = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                errors.add("引数は --名前=値 の形: " + arg);
                continue;
            }
            String name = arg.substring(2, arg.indexOf('='));
            if (!KNOWN.contains(name)) {
                errors.add("知らない引数: " + name + "（使えるもの: " + KNOWN.stream().sorted().toList() + "）");
                continue;
            }
            values.put(name, arg.substring(arg.indexOf('=') + 1));
        }

        List<EvalSkill> skills = new ArrayList<>();
        String skill = values.getOrDefault("skill", "all");
        if (skill.equals("all")) {
            skills.addAll(List.of(EvalSkill.values()));
        } else {
            EvalSkill.parse(skill).ifPresentOrElse(skills::add, () -> errors.add("skill が不正: " + skill + "（" + EvalSkill.ids() + " または all）"));
        }
        String split = values.getOrDefault("split", "dev");
        List<String> splits = switch (split) {
            case "dev" -> List.of(EvalCase.DEV);
            case "holdout" -> List.of(EvalCase.HOLDOUT);
            case "all" -> List.of(EvalCase.DEV, EvalCase.HOLDOUT);
            default -> {
                errors.add("split が不正: " + split + "（dev、holdout、all）");
                yield List.of();
            }
        };
        String cases = values.getOrDefault("cases", "public");
        if (!Set.of("public", "private", "all").contains(cases)) {
            errors.add("cases が不正: " + cases + "（public、private、all）");
        }
        Boolean think = null;
        if (values.containsKey("think")) {
            String v = values.get("think");
            if (!v.equals("true") && !v.equals("false")) {
                errors.add("think は true か false: " + v);
            } else {
                think = Boolean.parseBoolean(v);
            }
        }
        Double temperature = null;
        if (values.containsKey("temperature")) {
            try {
                temperature = Double.parseDouble(values.get("temperature"));
            } catch (NumberFormatException e) {
                errors.add("temperature は数値: " + values.get("temperature"));
            }
        }
        int repeat = 1;
        if (values.containsKey("repeat")) {
            try {
                repeat = Integer.parseInt(values.get("repeat"));
                if (repeat < 1) {
                    errors.add("repeat は1以上: " + repeat);
                }
            } catch (NumberFormatException e) {
                errors.add("repeat は整数: " + values.get("repeat"));
            }
        }
        int seed = 42;
        if (values.containsKey("seed")) {
            try {
                seed = Integer.parseInt(values.get("seed"));
            } catch (NumberFormatException e) {
                errors.add("seed は整数: " + values.get("seed"));
            }
        }
        if (!errors.isEmpty()) {
            throw new EvalConfigException(errors);
        }
        String compare = values.get("compareWith");
        return new EvalConfig(
                root,
                skills,
                splits,
                cases,
                values.getOrDefault("promptVersion", "v2"),
                values.getOrDefault("model", DEFAULT_MODEL),
                think,
                temperature,
                repeat,
                values.get("label"),
                compare == null ? null : root.resolve(compare),
                values.getOrDefault("ollamaUrl", DEFAULT_OLLAMA_URL),
                seed);
    }
}
