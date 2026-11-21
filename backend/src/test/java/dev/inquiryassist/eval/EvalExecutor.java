package dev.inquiryassist.eval;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 例を、製品と同じスキルの実装に渡して実行し、採点する。評価用に別の呼び出しや入力の組み立ては書かない
 * （入力の組み立てと出力の読み取りは、スキルの実装が行う）。
 */
final class EvalExecutor {

    private static final AccessScope SCOPE = new AccessScope("eval", Set.of("faq", "glossary"));

    private final ReceptionSkill reception;
    private final InterviewerSkill interviewer;
    private final RestaterSkill restater;
    private final ExplainerSkill explainer;
    private final List<SystemOption> systems;

    EvalExecutor(
            ReceptionSkill reception,
            InterviewerSkill interviewer,
            RestaterSkill restater,
            ExplainerSkill explainer,
            List<SystemOption> systems) {
        this.reception = reception;
        this.interviewer = interviewer;
        this.restater = restater;
        this.explainer = explainer;
        this.systems = systems;
    }

    /** 生の出力を結果に残すときの、1件あたりの長さの上限。 */
    private static final int RAW_OUTPUT_LIMIT = 600;

    private static String abbreviated(List<String> rawOutputs) {
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < rawOutputs.size(); i++) {
            String raw = rawOutputs.get(i) == null ? "" : rawOutputs.get(i).strip();
            String shown = raw.length() > RAW_OUTPUT_LIMIT ? raw.substring(0, RAW_OUTPUT_LIMIT) + "…（以下略）" : raw;
            parts.add("[" + (i + 1) + "回目] " + shown);
        }
        return String.join(" ", parts);
    }

    /**
     * 例を1回実行する。スキルの呼び出しが失敗した場合は、失敗として結果に残し、例外は投げない（実行は続ける）。
     *
     * @param c 例
     * @param run 繰り返しの何回目か
     * @return 結果
     */
    CaseResult execute(EvalCase c, int run) {
        long start = System.nanoTime();
        try {
            Outcome outcome = call(c);
            return result(c, run, outcome.failures(), false, outcome.output(), outcome.usage(), start);
        } catch (dev.inquiryassist.ollama.UnreadableOutputException e) {
            // 読めなかった出力そのものを結果に残す。理由だけでは、何を直せばよいかが分からない
            return failed(
                    c,
                    run,
                    "モデルの出力を指定の形として読めませんでした。理由: " + String.join(" / ", e.details()) + " 生の出力: "
                            + abbreviated(e.rawOutputs()),
                    e.usage(),
                    start);
        } catch (AiUnavailableException e) {
            return failed(c, run, e.getMessage(), e.usage(), start);
        } catch (AiRefusalException e) {
            return failed(c, run, e.getMessage(), e.usage(), start);
        }
    }

    private record Outcome(List<Failure> failures, Map<String, Object> output, Usage usage) {}

    private Outcome call(EvalCase c) {
        JsonNode body = c.body();
        Map<String, Object> output = new LinkedHashMap<>();
        switch (c.skill()) {
            case RECEPTION -> {
                SystemOption selected = body.path("selectedSystemId").isNull()
                        ? null
                        : systems.stream()
                                .filter(s -> s.id().equals(body.path("selectedSystemId").asString()))
                                .findFirst()
                                .orElse(null);
                var result = reception.receive(body.path("utterance").asString(), systems, selected);
                output.put("category", result.value().category().name());
                output.put("systemId", result.value().guessedSystemId());
                return new Outcome(
                        Scorer.scoreReception(c.expect(), result.value().category(), result.value().guessedSystemId()),
                        output,
                        result.usage());
            }
            case INTERVIEWER -> {
                var result = interviewer.ask(
                        body.path("utterance").asString(), category(body), systemOf(body), SCOPE);
                List<Scorer.Question> questions = result.value().questions().stream()
                        .map(q -> new Scorer.Question(q.text(), q.options()))
                        .toList();
                output.put("questions", result.value().questions().stream()
                        .map(q -> Map.of("text", q.text(), "options", q.options()))
                        .toList());
                return new Outcome(Scorer.scoreInterviewer(c.expect(), questions), output, result.usage());
            }
            case RESTATER -> {
                List<AnsweredQuestion> answers = new ArrayList<>();
                int n = 1;
                for (JsonNode a : body.path("answers")) {
                    answers.add(new AnsweredQuestion(
                            new FollowUpQuestion("q" + n++, a.path("question").asString(), List.of()),
                            a.path("answer").isNull() ? null : a.path("answer").asString()));
                }
                List<String> corrections = new ArrayList<>();
                body.path("corrections").forEach(x -> corrections.add(x.asString()));
                AiResult<StructuredInquiry> result = restater.restate(
                        body.path("utterance").asString(), category(body), answers, systemOf(body), corrections);
                StructuredInquiry value = result.value();
                output.put("summary", value.summary());
                output.put("targetSystem", value.targetSystem());
                output.put("goal", value.goal());
                output.put("whatHappened", value.whatHappened());
                output.put("expectedResult", value.expectedResult());
                output.put("urgency", value.urgency().name());
                return new Outcome(Scorer.scoreRestater(c.expect(), value.summary(), value.goal(), value.whatHappened(), value.expectedResult()), output, result.usage());
            }
            case EXPLAINER -> {
                // 事実の根拠は、評価では使わない形だけの出典にする（説明は事実の文だけを受け取る）
                Citation evidence = new Citation(Citation.SourceType.FAQ, "eval", "eval.md", 1, 1, "eval", null);
                List<Finding> facts = new ArrayList<>();
                body.path("facts").forEach(f -> facts.add(new Finding(f.asString(), List.of(evidence))));
                String question = body.path("question").asString();
                StructuredInquiry inquiry = new StructuredInquiry(question, category(body), null, question, null, null, null);
                var result = explainer.explain(inquiry, facts);
                output.put("text", result.value().text());
                output.put("answersQuestion", result.value().answersQuestion());
                return new Outcome(
                        Scorer.scoreExplainer(c.expect(), result.value().text(), result.value().answersQuestion()),
                        output,
                        result.usage());
            }
            default -> throw new IllegalStateException();
        }
    }

    private static InquiryCategory category(JsonNode body) {
        return InquiryCategory.valueOf(body.path("category").asString());
    }

    /** 対象システムの名前だけが入力に使われる（スキルは名前を使う）。id には名前を入れる。 */
    private static SystemOption systemOf(JsonNode body) {
        JsonNode label = body.path("systemLabel");
        return label.isNull() ? null : new SystemOption(label.asString(), label.asString());
    }

    /** 入力の要約（結果の一覧に載せる）。 */
    static String describeInput(EvalCase c) {
        JsonNode b = c.body();
        return switch (c.skill()) {
            case RECEPTION -> "発話: " + b.path("utterance").asString()
                    + (b.path("selectedSystemId").isNull() ? "" : "（本人が選んだシステム: " + b.path("selectedSystemId").asString() + "）");
            case INTERVIEWER -> "発話: " + b.path("utterance").asString() + " / 種別: " + b.path("category").asString()
                    + " / 対象のシステム: " + (b.path("systemLabel").isNull() ? "なし" : b.path("systemLabel").asString());
            case RESTATER -> "発話: " + b.path("utterance").asString() + " / 種別: " + b.path("category").asString()
                    + " / 回答 " + b.path("answers").size() + " 件 / 訂正 " + b.path("corrections").toString();
            case EXPLAINER -> "問い: " + b.path("question").asString() + " / 種別: " + b.path("category").asString()
                    + " / 事実: " + b.path("facts").toString();
        };
    }

    private static CaseResult result(
            EvalCase c, int run, List<Failure> failures, boolean callFailed, Map<String, Object> output, Usage usage, long start) {
        return new CaseResult(
                c.skill().id(),
                c.id(),
                c.split(),
                run,
                callFailed,
                failures,
                describeInput(c),
                output,
                (System.nanoTime() - start) / 1_000_000,
                usage == null ? 0 : usage.inputTokens(),
                usage == null ? 0 : usage.outputTokens());
    }

    private static CaseResult failed(EvalCase c, int run, String message, Usage usage, long start) {
        return result(c, run, List.of(new Failure("callFailed", message)), true, Map.of(), usage, start);
    }
}
