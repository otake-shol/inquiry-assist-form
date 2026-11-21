package dev.inquiryassist.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 結果の書き出し（report.md、results.json）と、holdout の出力を出さない決まり、比較を確かめる。 */
class EvalReportTest {

    @TempDir
    Path dir;

    private static CaseResult result(String id, String split, boolean pass, String outputText) {
        return new CaseResult(
                "restater",
                id,
                split,
                1,
                false,
                pass ? List.of() : List.of(new Failure("mustInclude", "「A社」を含まない: " + outputText)),
                "入力 " + id,
                Map.of("summary", outputText),
                100,
                10,
                5);
    }

    private static Map<String, Object> conditions(String promptVersion) {
        return Map.of("model", "m", "promptVersion", promptVersion, "split", List.of("dev", "holdout"));
    }

    @Test
    void holdoutの出力はreportにもresultsにも出さず落ちた条件の名前だけを出す() throws IOException {
        var results = List.of(
                result("dev-ng", "dev", false, "開発用の出力あいう"),
                result("hold-ng", "holdout", false, "確認用の出力かきく"),
                result("hold-ok", "holdout", true, "確認用の出力さしす"));

        new EvalReport(conditions("v1"), results, null).write(dir);

        String report = Files.readString(dir.resolve("report.md"));
        String json = Files.readString(dir.resolve("results.json"));
        // dev の出力は出す
        assertThat(report).contains("開発用の出力あいう");
        assertThat(json).contains("開発用の出力あいう");
        // holdout の出力は出さない（落ちた理由に含まれる出力の一部も出さない）
        for (String text : List.of(report, json)) {
            assertThat(text).doesNotContain("確認用の出力かきく").doesNotContain("確認用の出力さしす");
        }
        // 入力と、落ちた条件の名前は出す
        assertThat(report).contains("入力 hold-ng").contains("落ちた条件: mustInclude");
        assertThat(json).contains("入力 hold-ng").contains("\"failedConditions\"");
    }

    @Test
    void holdoutだけを測った結果にはすべての例の出力の節がない() throws IOException {
        var results = List.of(result("hold-1", "holdout", true, "秘密の出力"));

        String report = new EvalReport(conditions("v1"), results, null).markdown();

        assertThat(report).doesNotContain("すべての例の出力").doesNotContain("秘密の出力");
        assertThat(report).contains("## 1. 条件").contains("## 2. スキルごとのまとめ").contains("## 3. 落ちた例");
    }

    @Test
    void まとめには例の数と通った割合と応答時間とトークンが入る() {
        var results = List.of(result("a", "dev", true, "x"), result("b", "dev", false, "y"), result("c", "dev", true, "z"));

        var summary = new EvalReport(conditions("v1"), results, null).summaryOf("restater");

        assertThat(summary.runs()).isEqualTo(3);
        assertThat(summary.passed()).isEqualTo(2);
        assertThat(summary.percent()).isEqualTo("67%");
        assertThat(summary.failedByCondition()).containsEntry("mustInclude", 1);
        assertThat(summary.medianMillis()).isEqualTo(100);
        assertThat(summary.inputTokens()).isEqualTo(30);
        assertThat(summary.outputTokens()).isEqualTo(15);
    }

    private static CaseResult run(String id, int run, boolean pass) {
        return new CaseResult("restater", id, "dev", run, false,
                pass ? List.of() : List.of(new Failure("style", "d")), "入力", Map.of("summary", "出力"), 10, 1, 1);
    }

    @Test
    void 繰り返したときは例ごとの通った回数とすべての回で通った例の数と延べの割合を出す() throws IOException {
        var results = List.of(
                run("a", 1, true), run("a", 2, true), run("a", 3, true), // 3/3
                run("b", 1, true), run("b", 2, false), run("b", 3, true), // 2/3
                run("c", 1, false), run("c", 2, false), run("c", 3, false)); // 0/3

        var report = new EvalReport(conditions("v1"), results, null);
        var summary = report.summaryOf("restater");

        assertThat(summary.caseCount()).isEqualTo(3);
        assertThat(summary.allPassedCases()).isEqualTo(1);
        assertThat(summary.anyFailedCases()).isEqualTo(2);
        assertThat(summary.runs()).isEqualTo(9);
        assertThat(summary.passed()).isEqualTo(5);
        assertThat(summary.percent()).isEqualTo("56%");
        String md = report.markdown();
        assertThat(md).contains("a 3/3").contains("b 2/3").contains("c 0/3").contains("通った割合は延べ");
        report.write(dir);
        String json = Files.readString(dir.resolve("results.json"));
        assertThat(json).contains("\"allPassedCases\" : 1").contains("\"anyFailedCases\" : 2").contains("\"passedRuns\" : 2");
    }

    @Test
    void 繰り返しがなければ例ごとの回数の節は出さない() {
        String md = new EvalReport(conditions("v1"), List.of(result("a", "dev", true, "x")), null).markdown();

        assertThat(md).doesNotContain("例ごとの通った回数");
    }

    @Test
    void 繰り返しがある場合のcompareWithは通った回数の変化で示す() throws IOException {
        var before = List.of(run("a", 1, true), run("a", 2, false), run("a", 3, false), run("b", 1, true), run("b", 2, true), run("b", 3, true));
        Path previous = dir.resolve("previous-r3");
        new EvalReport(conditions("v1"), before, null).write(previous);
        var after = List.of(run("a", 1, true), run("a", 2, true), run("a", 3, true), run("b", 1, true), run("b", 2, true), run("b", 3, true));

        String md = new EvalReport(conditions("v2"), after, previous).markdown();

        assertThat(md).contains("通った回数の変化: restater / a: 1/3 → 3/3");
        assertThat(md).doesNotContain("通った回数の変化: restater / b");
        assertThat(md).doesNotContain("通るようになった");
    }

    @Test
    void 呼び出しの失敗は落ちた例として数える() {
        var failed = new CaseResult("restater", "x", "dev", 1, true, List.of(new Failure("callFailed", "接続できません")), "入力", Map.of(), 5, 0, 0);

        var summary = SkillSummary.of("restater", List.of(failed, result("ok", "dev", true, "o")));

        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.callFailures()).isEqualTo(1);
        assertThat(summary.failedByCondition()).containsEntry("callFailed", 1);
    }

    @Test
    void compareWithは通った割合の差と結果が変わった例を出す() throws IOException {
        var before = List.of(result("a", "dev", true, "x"), result("b", "dev", false, "y"), result("c", "dev", true, "z"));
        Path previous = dir.resolve("previous");
        new EvalReport(conditions("v1"), before, null).write(previous);
        var after = List.of(result("a", "dev", true, "x"), result("b", "dev", true, "y"), result("c", "dev", false, "z"));

        String report = new EvalReport(conditions("v2"), after, previous).markdown();

        assertThat(report).contains("## 5. 前回との比較").contains("プロンプトの版 v1").contains("プロンプトの版 v2");
        assertThat(report).contains("通るようになった: restater / b").contains("落ちるようになった: restater / c");
        assertThat(report).contains("| restater | 67% | 67% | +0 pt |");
    }

    @Test
    void 比べる相手を読めなければその旨を書く() {
        String report = new EvalReport(conditions("v1"), List.of(result("a", "dev", true, "x")), dir.resolve("missing")).markdown();

        assertThat(report).contains("前回の結果を読み込めませんでした");
    }

    @Test
    void フォルダ名は日時とラベルから作る() {
        var now = LocalDateTime.of(2026, 11, 21, 9, 5, 7);

        assertThat(EvalReport.folderName(now, null)).isEqualTo("20261121-090507");
        assertThat(EvalReport.folderName(now, "baseline")).isEqualTo("20261121-090507-baseline");
    }
}
