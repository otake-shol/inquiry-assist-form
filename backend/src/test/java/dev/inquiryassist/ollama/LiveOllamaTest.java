package dev.inquiryassist.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 実際の Ollama（{@code localhost:11434}、モデル {@code qwen3.6:35b}）を呼ぶ確認。
 *
 * <p>通常の検証（{@code check}）には含めず、{@code liveTest} で手元から実行する。確かめるのは、
 * 4つのスキルがそれぞれ型どおりの結果と利用量を返すことだけで、文章の中身までは検査しない。
 * 設定は {@code application-local-ai.yaml} と同じ（説明だけ think あり）。
 */
@Tag("live")
class LiveOllamaTest {

    private static final String MODEL = "qwen3.6:35b";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<SystemOption> SYSTEMS =
            List.of(new SystemOption("job-admin", "求人管理画面"), new SystemOption("billing", "請求管理"));
    private static final AccessScope SCOPE = new AccessScope("live", Set.of("faq"));
    private static final String UTTERANCE = "求人管理画面で、掲載期限を過ぎた求人が応募者向けの一覧に出なくなった。仕様どおりなのか知りたい。";

    private static InquiryProperties properties() {
        SkillSettings fast = new SkillSettings("ollama", MODEL, 0, false, Duration.ofSeconds(60), "v2");
        SkillSettings thinking = new SkillSettings("ollama", MODEL, 0, true, Duration.ofSeconds(120), "v2");
        return new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of("reception", fast, "interviewer", fast, "restater", fast, "explainer", thinking),
                new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("開発用ユーザー", "担当者（開発用）"),
                java.util.Map.of(),
                InquiryProperties.Rules.defaults());
    }

    private static OllamaClient client() {
        return new OllamaClient("http://localhost:11434", "30m", 42, JSON);
    }

    private static void assertUsage(Usage usage) {
        assertThat(usage.model()).isEqualTo(MODEL);
        assertThat(usage.promptVersion()).isEqualTo("v2");
        assertThat(usage.inputTokens()).isPositive();
        assertThat(usage.outputTokens()).isPositive();
        assertThat(usage.mock()).isFalse();
    }

    private static void report(String skill, long startNanos, AiResult<?> result) {
        System.out.printf(
                "[live] %s: %d ms, 入力 %d / 出力 %d トークン%n",
                skill,
                (System.nanoTime() - startNanos) / 1_000_000,
                result.usage().inputTokens(),
                result.usage().outputTokens());
    }

    @Test
    void 受付は種類を返しシステムと機能は候補のidかnull() {
        var skill = new OllamaReceptionSkill(client(), JSON, properties());
        long start = System.nanoTime();

        var result = skill.receive(UTTERANCE, SYSTEMS, Placement.NONE);

        report("reception", start, result);
        assertThat(result.value().type()).isNotNull();
        assertThat(result.value().systemId()).isIn(null, "job-admin", "billing");
        assertUsage(result.usage());
    }

    @Test
    void 聞き手は型どおりの質問を返す() {
        var skill = new OllamaInterviewerSkill(client(), JSON, properties());
        long start = System.nanoTime();

        var result = skill.ask(UTTERANCE, InquiryType.HOW_IT_WORKS, null, null, SCOPE, "ja");

        report("interviewer", start, result);
        List<FollowUpQuestion> questions = result.value().questions();
        assertThat(questions.size()).isBetween(0, 2);
        for (int i = 0; i < questions.size(); i++) {
            assertThat(questions.get(i).id()).isEqualTo("q" + (i + 1));
            assertThat(questions.get(i).text()).isNotBlank();
            assertThat(questions.get(i).options().size()).isBetween(0, 4);
            assertThat(questions.get(i).options()).doesNotContain("わからない", "その他");
        }
        assertUsage(result.usage());
    }

    @Test
    void 言い直しは文章と構造化データを返し訂正を足しても返す() {
        var skill = new OllamaRestaterSkill(client(), JSON, properties());
        var answers = List.of(
                new AnsweredQuestion(new FollowUpQuestion("q1", "求人の状態はどれですか", List.of("掲載中", "掲載終了")), "掲載中"),
                new AnsweredQuestion(new FollowUpQuestion("q2", "いつからですか", List.of()), null));
        long start = System.nanoTime();

        var first = skill.restate(UTTERANCE, InquiryType.HOW_IT_WORKS, SYSTEMS.get(0), null, answers, List.of(), "ja");
        report("restater", start, first);
        var corrected = skill.restate(
                UTTERANCE, InquiryType.HOW_IT_WORKS, SYSTEMS.get(0), null, answers, List.of("消えたのは昨日から"), "ja");

        for (var result : List.of(first, corrected)) {
            StructuredInquiry value = result.value();
            assertThat(value.summary()).isNotBlank();
            assertThat(value.goal()).isNotBlank();
            assertThat(value.type()).isEqualTo(InquiryType.HOW_IT_WORKS);
            assertThat(value.targetSystem()).isEqualTo("求人管理画面");
            assertUsage(result.usage());
        }
    }

    @Test
    void 説明は空でない文章を返す() {
        var skill = new OllamaExplainerSkill(client(), JSON, properties());
        var citation = new Citation(Citation.SourceType.FAQ, "faq", "posting-expiry.md", 5, 6, "x", null);
        var findings = List.of(
                new Finding("掲載期限を過ぎた求人は、応募者向けの一覧に表示されない", List.of(citation)),
                new Finding("期限切れの求人は延長できない", List.of(citation)));
        var inquiry = new StructuredInquiry(
                "求人の掲載期限を過ぎると一覧に出なくなった。仕様どおりかを知りたい。",
                InquiryType.HOW_IT_WORKS,
                "求人管理画面",
                UTTERANCE,
                null,
                null);
        long start = System.nanoTime();

        var result = skill.explain(inquiry, findings, "ja");

        report("explainer", start, result);
        assertThat(result.value().text()).isNotBlank();
        assertUsage(result.usage());
    }
}
