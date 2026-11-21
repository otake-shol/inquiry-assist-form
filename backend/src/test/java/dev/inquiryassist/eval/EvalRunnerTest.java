package dev.inquiryassist.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.ollama.FakeOllama;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 評価の実行役を、偽の Ollama で確かめる（実際のモデルは呼ばない）。 */
class EvalRunnerTest {

    private static final String TAGS = "{\"models\":[{\"name\":\"qwen3.6:35b\"}]}";

    @TempDir
    Path root;

    private void writeCases(String skillJson) throws IOException {
        Path cases = root.resolve("evals/cases");
        Files.createDirectories(cases);
        Files.writeString(cases.resolve("reception.json"), skillJson);
    }

    private static final String RECEPTION_FILE = "{\"systems\":[{\"id\":\"billing\",\"label\":\"請求管理\"}],\"cases\":["
            + "{\"id\":\"rc-1\",\"split\":\"dev\",\"utterance\":\"仕組みを知りたい\",\"selectedSystemId\":null,"
            + "\"expect\":{\"category\":\"SPEC_QUESTION\",\"systemId\":null}},"
            + "{\"id\":\"rc-2\",\"split\":\"dev\",\"utterance\":\"金額がおかしい\",\"selectedSystemId\":null,"
            + "\"expect\":{\"category\":\"SUSPECTED_BUG\",\"systemId\":\"billing\"}},"
            + "{\"id\":\"rc-3\",\"split\":\"holdout\",\"utterance\":\"作業の依頼\",\"selectedSystemId\":null,"
            + "\"expect\":{\"category\":\"WORK_REQUEST\",\"systemId\":null}}]}";

    private EvalRunner runner(FakeOllama ollama, String... args) {
        EvalConfig config = EvalConfig.parse(root, List.of(args));
        return new EvalRunner(config, new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void 製品と同じスキルを呼び採点して結果を書く_呼び出しの失敗は失敗として数え実行を続ける() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw(TAGS)
                    .reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":null}", 10, 2) // rc-1: 通る
                    .status(500); // rc-2: 呼び出しの失敗
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            EvalConfig config = EvalConfig.parse(
                    root, List.of("--skill=reception", "--split=dev", "--label=t", "--ollamaUrl=" + ollama.baseUrl()));

            var outcome = new EvalRunner(config, new PrintStream(stdout, true, StandardCharsets.UTF_8)).run();

            var summary = outcome.report().summaryOf("reception");
            assertThat(summary.runs()).isEqualTo(2); // dev だけ
            assertThat(summary.passed()).isEqualTo(1);
            assertThat(summary.callFailures()).isEqualTo(1);
            assertThat(summary.inputTokens()).isEqualTo(10);
            assertThat(outcome.reportDir().getFileName().toString()).endsWith("-t");
            assertThat(outcome.reportDir().resolve("report.md")).exists();
            assertThat(outcome.reportDir().resolve("results.json")).exists();
            String out = stdout.toString(StandardCharsets.UTF_8);
            assertThat(out).contains("reception").contains("50%").contains(outcome.reportDir().toString());
            // 製品のスキルと同じ形のリクエスト（system + user、format なし、think と temperature あり）
            var request = ollama.requests().get(1);
            assertThat(request).contains("\"model\":\"qwen3.6:35b\"").contains("\"think\":false").contains("\"temperature\":0").doesNotContain("\"format\"");
            assertThat(request).contains("\"seed\":42");
            assertThat(request).contains("仕組みを知りたい").contains("- billing: 請求管理");
        }
    }

    @Test
    void holdoutを測った結果に出力は出ない() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw(TAGS).reply("{\"category\":\"INCIDENT\",\"systemId\":\"billing\"}", 1, 1);

            var outcome = runner(ollama, "--skill=reception", "--split=holdout", "--ollamaUrl=" + ollama.baseUrl()).run();

            String report = Files.readString(outcome.reportDir().resolve("report.md"));
            String json = Files.readString(outcome.reportDir().resolve("results.json"));
            assertThat(report).contains("rc-3").contains("category").contains("holdout のため、出力は出しません");
            // モデルが返した値（INCIDENT）は出さない。期待値の「WORK_REQUEST」も理由に含まれるため出さない
            assertThat(report).doesNotContain("INCIDENT").doesNotContain("すべての例の出力");
            assertThat(json).doesNotContain("INCIDENT").doesNotContain("\"output\"");
        }
    }

    @Test
    void Ollamaに接続できなければ最初に分かりやすいメッセージで止まる() throws Exception {
        writeCases(RECEPTION_FILE);
        String url;
        try (FakeOllama ollama = new FakeOllama()) {
            url = ollama.baseUrl();
        }
        EvalConfig config = EvalConfig.parse(root, List.of("--skill=reception", "--ollamaUrl=" + url));

        assertThatThrownBy(() -> new EvalRunner(config, new PrintStream(new ByteArrayOutputStream())).run())
                .isInstanceOf(EvalRunner.OllamaUnavailableException.class)
                .hasMessageContaining("Ollama に接続できません")
                .hasMessageContaining("Ollama を起動");
        assertThat(root.resolve("evals/reports")).doesNotExist();
    }

    @Test
    void モデルがなければ導入の案内を出して止まる() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw("{\"models\":[{\"name\":\"other:1b\"}]}");

            assertThatThrownBy(() -> runner(ollama, "--skill=reception", "--ollamaUrl=" + ollama.baseUrl()).run())
                    .isInstanceOf(EvalRunner.OllamaUnavailableException.class)
                    .hasMessageContaining("ollama pull qwen3.6:35b");
        }
    }

    @Test
    void 例の誤りは通信より前に止まる() throws Exception {
        writeCases(RECEPTION_FILE.replace("\"category\":\"SPEC_QUESTION\"", "\"categoryy\":\"SPEC_QUESTION\""));
        try (FakeOllama ollama = new FakeOllama()) {
            assertThatThrownBy(() -> runner(ollama, "--skill=reception", "--ollamaUrl=" + ollama.baseUrl()).run())
                    .isInstanceOf(EvalConfigException.class)
                    .hasMessageContaining("知らない条件の名前: categoryy");
            assertThat(ollama.requests()).isEmpty();
        }
    }

    @Test
    void 非公開の例がなければ0件として扱い公開の例だけを測る() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw(TAGS)
                    .reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":null}", 1, 1)
                    .reply("{\"category\":\"SUSPECTED_BUG\",\"systemId\":\"billing\"}", 1, 1);

            var outcome = runner(ollama, "--skill=reception", "--cases=all", "--ollamaUrl=" + ollama.baseUrl()).run();

            assertThat(outcome.report().summaryOf("reception").runs()).isEqualTo(2);
        }
    }

    @Test
    void 繰り返しの回数だけ同じ例を実行する() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw(TAGS);
            for (int i = 0; i < 6; i++) {
                ollama.reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":null}", 1, 1);
            }

            var outcome = runner(ollama, "--skill=reception", "--repeat=3", "--ollamaUrl=" + ollama.baseUrl()).run();

            assertThat(outcome.report().summaryOf("reception").runs()).isEqualTo(6);
        }
    }

    @Test
    void 結果の条件にseedが記録される() throws Exception {
        writeCases(RECEPTION_FILE);
        try (FakeOllama ollama = new FakeOllama()) {
            ollama.raw(TAGS)
                    .reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":null}", 1, 1)
                    .reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":null}", 1, 1);

            var outcome = runner(ollama, "--skill=reception", "--seed=7", "--ollamaUrl=" + ollama.baseUrl()).run();

            assertThat(Files.readString(outcome.reportDir().resolve("results.json"))).contains("\"seed\" : 7");
            assertThat(ollama.requests().get(1)).contains("\"seed\":7");
        }
    }

    @Test
    void 引数の誤りはまとめてエラーになる() {
        assertThatThrownBy(() -> EvalConfig.parse(root, List.of("--skill=nothing", "--split=x", "--bogus=1", "repeat")))
                .isInstanceOf(EvalConfigException.class)
                .satisfies(e -> assertThat(((EvalConfigException) e).errors()).hasSize(4));
    }

    @Test
    void 引数の既定値() {
        EvalConfig config = EvalConfig.parse(root, List.of());

        assertThat(config.skills()).containsExactly(EvalSkill.values());
        assertThat(config.splits()).containsExactly("dev");
        assertThat(config.cases()).isEqualTo("public");
        assertThat(config.promptVersion()).isEqualTo("v1");
        assertThat(config.repeat()).isEqualTo(1);
    }
}
