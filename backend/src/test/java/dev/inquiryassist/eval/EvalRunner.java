package dev.inquiryassist.eval;

import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import dev.inquiryassist.ollama.OllamaClient;
import dev.inquiryassist.ollama.OllamaExplainerSkill;
import dev.inquiryassist.ollama.OllamaInterviewerSkill;
import dev.inquiryassist.ollama.OllamaReceptionSkill;
import dev.inquiryassist.ollama.OllamaRestaterSkill;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 評価を実行する。製品と同じスキルの実装（{@code OllamaReceptionSkill} など）を、製品と同じ組み立て方で作って呼ぶ。
 * Spring のコンテキストは起動しない。
 */
final class EvalRunner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 実行の結果。 */
    record Outcome(Path reportDir, EvalReport report) {}

    private final EvalConfig config;
    private final PrintStream out;

    EvalRunner(EvalConfig config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * 例を読み込み、Ollama の疎通を確かめ、実行して結果を書く。
     *
     * @return 結果のフォルダとまとめ
     * @throws EvalConfigException 例のファイルや引数に誤りがある場合（実行を始める前）
     * @throws OllamaUnavailableException Ollama に接続できない、またはモデルがない場合（最初に止まる）
     * @throws IOException 結果を書き込めない場合
     */
    Outcome run() throws IOException {
        List<CaseLoader.Source> sources = new ArrayList<>();
        if (!config.cases().equals("private")) {
            sources.add(new CaseLoader.Source(config.root().resolve("evals/cases"), true));
        }
        if (!config.cases().equals("public")) {
            sources.add(new CaseLoader.Source(config.root().resolve("evals/private"), false));
        }
        CaseLoader.Loaded loaded = CaseLoader.load(sources, config.skills());
        List<EvalCase> cases = loaded.cases().stream().filter(c -> config.splits().contains(c.split())).toList();

        preflight();

        EvalExecutor executor = executor(loaded.systems());
        out.printf("評価を始めます: スキル %s、区分 %s、例 %d 件（繰り返し %d 回）、モデル %s、プロンプト %s%n",
                config.skills().stream().map(EvalSkill::id).toList(), config.splits(), cases.size(), config.repeat(), config.model(), config.promptVersion());
        long start = System.nanoTime();
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            for (int run = 1; run <= config.repeat(); run++) {
                CaseResult r = executor.execute(c, run);
                results.add(r);
                out.printf("  %-12s %-8s %s (%d ms)%n", r.skill(), r.caseId(), r.passed() ? "通った" : "落ちた", r.millis());
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> conditions = conditions(now, cases.size(), elapsedMillis);
        EvalReport report = new EvalReport(conditions, results, config.compareWith());
        Path dir = config.root().resolve("evals/reports").resolve(EvalReport.folderName(now, config.label()));
        report.write(dir);
        printSummary(report, dir, elapsedMillis);
        return new Outcome(dir, report);
    }

    /** Ollama に接続できるか、使うモデルがあるかを、最初に確かめる。 */
    private void preflight() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(config.ollamaUrl().replaceAll("/+$", "") + "/api/tags"))
                                    .timeout(Duration.ofSeconds(10))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OllamaUnavailableException("Ollama が HTTP " + response.statusCode() + " を返しました（" + config.ollamaUrl() + "）。");
            }
            JsonNode models = JSON.readTree(response.body()).path("models");
            boolean found = false;
            for (JsonNode m : models) {
                found |= m.path("name").asString("").equals(config.model());
            }
            if (!found) {
                throw new OllamaUnavailableException("Ollama にモデル " + config.model() + " がありません。`ollama pull " + config.model() + "` で導入してください。");
            }
        } catch (IOException e) {
            throw new OllamaUnavailableException("Ollama に接続できません（" + config.ollamaUrl() + "）。Ollama を起動してから実行してください。評価は実際のモデルを呼ぶため、Ollama が動いている手元でだけ実行できます。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaUnavailableException("Ollama の確認が中断されました。");
        }
    }

    /** 製品の設定（{@code local-ai}）と同じ値から、製品と同じスキルの実装を作る。 */
    private EvalExecutor executor(List<dev.inquiryassist.core.SystemOption> systems) {
        Map<String, SkillSettings> settings = new LinkedHashMap<>();
        for (EvalSkill skill : EvalSkill.values()) {
            boolean explainer = skill == EvalSkill.EXPLAINER;
            settings.put(
                    skill.id(),
                    new SkillSettings(
                            "ollama",
                            config.model(),
                            config.temperature() == null ? 0 : config.temperature(),
                            config.think() == null ? explainer : config.think(),
                            Duration.ofSeconds(explainer ? 120 : 60),
                            config.promptVersion()));
        }
        InquiryProperties properties = new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                settings,
                new InquiryProperties.Ollama(config.ollamaUrl(), "30m", config.seed()),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("開発用ユーザー", "担当者（開発用）"),
                java.util.Map.of(),
                InquiryProperties.Rules.defaults(),
                List.of());
        OllamaClient client = new OllamaClient(config.ollamaUrl(), "30m", config.seed(), JSON);
        return new EvalExecutor(
                new OllamaReceptionSkill(client, JSON, properties),
                new OllamaInterviewerSkill(client, JSON, properties),
                new OllamaRestaterSkill(client, JSON, properties),
                new OllamaExplainerSkill(client, JSON, properties),
                systems);
    }

    private Map<String, Object> conditions(LocalDateTime now, int caseCount, long elapsedMillis) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("startedAt", now.toString());
        c.put("model", config.model());
        c.put("promptVersion", config.promptVersion());
        c.put("temperature", config.temperature() == null ? 0 : config.temperature());
        c.put("seed", config.seed());
        c.put("think", config.think() == null ? "スキルの設定のとおり（説明だけ true）" : config.think());
        c.put("skills", config.skills().stream().map(EvalSkill::id).toList());
        c.put("split", config.splits());
        c.put("cases", config.cases());
        c.put("repeat", config.repeat());
        c.put("caseCount", caseCount);
        c.put("elapsedMillis", elapsedMillis);
        c.put("label", config.label());
        return c;
    }

    private void printSummary(EvalReport report, Path dir, long elapsedMillis) {
        out.println();
        out.printf("%-14s %5s %10s %10s %6s %6s %6s %10s%n", "スキル", "例", "全回通った", "1回でも落", "実行", "通った", "割合", "中央値(ms)");
        for (String skill : report.skills()) {
            SkillSummary s = report.summaryOf(skill);
            out.printf("%-14s %5d %10d %10d %6d %6d %6s %10d%n", skill, s.caseCount(), s.allPassedCases(), s.anyFailedCases(), s.runs(), s.passed(), s.percent(), s.medianMillis());
        }
        out.printf("%n所要時間: %.1f 秒%n結果のフォルダ: %s%n", elapsedMillis / 1000.0, dir);
        if (config.compareWith() != null) {
            out.println();
            out.println(report.comparison());
        }
    }

    /** Ollama に接続できない、またはモデルがない。 */
    static final class OllamaUnavailableException extends RuntimeException {
        OllamaUnavailableException(String message) {
            super(message);
        }
    }

}
