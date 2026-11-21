package dev.inquiryassist.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Investigation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** モックの5つのスキルを、契約書「モックのシナリオ」に沿って確認する。 */
class MockSkillsTest {

    private static final Duration DELAY = Duration.ofMillis(40);
    private static final List<SystemOption> SYSTEMS = List.of(
            new SystemOption("job-admin", "求人管理画面"),
            new SystemOption("applicant-list", "応募者向けの一覧"),
            new SystemOption("billing", "請求管理"));

    private final MockReceptionSkill reception = new MockReceptionSkill();
    private final MockInterviewerSkill interviewer = new MockInterviewerSkill();
    private final MockRestaterSkill restater = new MockRestaterSkill();
    private final MockInvestigatorSkill investigator = new MockInvestigatorSkill(DELAY);
    private final MockExplainerSkill explainer = new MockExplainerSkill();
    private final AccessScope scope = new AccessScope("u", Set.of("job-board", "faq", "glossary"));
    private final CitationVerifier verifier = new CitationVerifier(new MockKnowledgeSearch());

    private StructuredInquiry inquiry(InquiryCategory category, String goal) {
        return new StructuredInquiry("言い直し", category, null, goal, null, null, null);
    }

    private AnswerPipeline pipeline() {
        return new AnswerPipeline(investigator, verifier, explainer);
    }

    // ---- 受付 ----

    @ParameterizedTest
    @CsvSource({
        "サイトがつながらない, INCIDENT",
        "システムが落ちている, INCIDENT",
        "本番が落ちています, INCIDENT",
        "掲載の延長をお願いします, WORK_REQUEST",
        "障害だが対応してください, INCIDENT", // 障害が依頼より優先
        "依頼したいがバグかもしれない, WORK_REQUEST", // 依頼がバグより優先
        "一覧の表示がおかしい, SUSPECTED_BUG",
        "期限の仕様を教えて, SPEC_QUESTION"
    })
    void 受付はキーワードの優先順位で種別を判定する(String utterance, InquiryCategory expected) {
        assertThat(reception.receive(utterance, SYSTEMS, null).value().category()).isEqualTo(expected);
    }

    @Test
    void 受付は発話に候補の名前があれば対象システムを推測し選ばれていれば推測しない() {
        assertThat(reception.receive("請求管理の期限の仕様", SYSTEMS, null).value().guessedSystemId())
                .isEqualTo("billing");
        assertThat(reception.receive("期限の仕様", SYSTEMS, null).value().guessedSystemId()).isNull();
        assertThat(reception.receive("請求管理の期限の仕様", SYSTEMS, SYSTEMS.get(0)).value().guessedSystemId())
                .isNull();
    }

    @Test
    void 判定エラータグは受付でAI利用不可になる() {
        assertThatThrownBy(() -> reception.receive("[判定エラー] 期限の仕様", SYSTEMS, null))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 聞き手 ----

    @Test
    void 聞き手は対象システムが未定なら2問_決まっていれば画面を尋ねる質問を除いた1問() {
        var unknown = interviewer.ask("期限の仕様", InquiryCategory.SPEC_QUESTION, null, scope).value();
        var known = interviewer.ask("期限の仕様", InquiryCategory.SPEC_QUESTION, SYSTEMS.get(0), scope).value();

        assertThat(unknown.questions()).extracting(FollowUpQuestion::id).containsExactly("q1", "q2");
        assertThat(unknown.questions()).allSatisfy(q -> assertThat(q.options()).isNotEmpty());
        assertThat(known.questions()).extracting(FollowUpQuestion::id).containsExactly("q2");
    }

    @Test
    void 質問なしタグは追加質問を出さず質問エラータグは聞き手が失敗する() {
        assertThat(interviewer.ask("[質問なし] 仕様", InquiryCategory.SPEC_QUESTION, null, scope).value().questions())
                .isEmpty();
        assertThatThrownBy(() -> interviewer.ask("[質問エラー] 仕様", InquiryCategory.SPEC_QUESTION, null, scope))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 言い直し ----

    @Test
    void 整形では確定した対象システムの名前をtargetSystemに入れgoalは発話のまま() {
        var withSystem = restater.restate("[遅延] x", InquiryCategory.SPEC_QUESTION, List.of(), SYSTEMS.get(2), List.of()).value();
        var without = restater.restate("x", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of()).value();

        assertThat(withSystem.targetSystem()).isEqualTo("請求管理");
        assertThat(withSystem.goal()).isEqualTo("[遅延] x");
        assertThat(without.targetSystem()).isNull();
    }

    @Test
    void 言い直しは種別ごとの形でタグを含まず回答が末尾に足される() {
        var answered = List.of(
                new AnsweredQuestion(new FollowUpQuestion("q1", "どの画面での話ですか", List.of()), "求人管理画面"),
                new AnsweredQuestion(new FollowUpQuestion("q2", "状態は", List.of()), null));

        assertThat(restater.restate("[質問なし] [遅延] 期限", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of()).value().summary())
                .isEqualTo("「期限」について、いまの仕組みがどうなっているかを知りたい。");
        assertThat(restater.restate("期限", InquiryCategory.SUSPECTED_BUG, answered, null, List.of()).value().summary())
                .isEqualTo("「期限」という状況が起きている。これが意図した動きかどうかを確認したい。（どの画面での話ですか: 求人管理画面）");
    }

    @Test
    void 作業依頼の言い直しは固定3問の回答から作り分からない項目を省く() {
        var answers = List.of(
                new AnsweredQuestion(new FollowUpQuestion("wr-what", "何を", List.of()), "期間の延長"),
                new AnsweredQuestion(new FollowUpQuestion("wr-when", "いつ", List.of()), null),
                new AnsweredQuestion(new FollowUpQuestion("wr-target", "対象", List.of()), "求人A"));

        assertThat(restater.restate("延長をお願いします", InquiryCategory.WORK_REQUEST, answers, null, List.of()).value().summary())
                .isEqualTo("期間の延長を対応してほしい。対象は求人A。");
    }

    @Test
    void 訂正があれば伝えられた順に補足を足しgoalを変えない() {
        var first = restater.restate("期限", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of()).value();

        var revised = restater
                .restate("期限", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of("一昨日から", "画面は管理画面"))
                .value();

        assertThat(revised.summary()).isEqualTo(first.summary() + " 補足: 一昨日から 補足: 画面は管理画面");
        assertThat(revised.goal()).isEqualTo(first.goal());
    }

    // ---- 調査・説明（パイプライン経由） ----

    @Test
    void 正常系は事実2件がすべて確かめられ説明は事実の文をつないだものになる() {
        List<String> progress = new ArrayList<>();

        var result = pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "期限の仕様"), scope, progress::add);

        assertThat(progress).hasSize(5).endsWith(AnswerPipeline.PROGRESS_VERIFYING, AnswerPipeline.PROGRESS_EXPLAINING);
        assertThat(result.answer().grounded()).isTrue();
        assertThat(result.answer().citations()).hasSize(2);
        assertThat(result.answer().memo().findings()).hasSize(2);
        assertThat(result.answer().memo().unconfirmed()).isEmpty();
        assertThat(result.answer().memo().notFound()).isNotEmpty();
        assertThat(result.answer().memo().nextChecks()).isNotEmpty();
        assertThat(result.answer().askerExplanation())
                .isEqualTo(result.answer().memo().findings().get(0) + "。" + result.answer().memo().findings().get(1) + "。");
    }

    @Test
    void 不具合の疑いの説明は現在の動作として書き仕様の正否を断定しない() {
        var answer = pipeline().run(inquiry(InquiryCategory.SUSPECTED_BUG, "表示がおかしい"), scope, m -> {}).answer();

        assertThat(answer.askerExplanation())
                .startsWith("現在の実装では、")
                .endsWith("この動作が意図した仕様かどうかは、担当者が確認します。")
                .doesNotContain("バグです")
                .doesNotContain("誤りです");
    }

    @Test
    void 一部幽霊タグは確かめられた事実だけから説明を書き落ちた事実は説明に現れない() {
        Investigation raw = investigator
                .investigate(inquiry(InquiryCategory.SPEC_QUESTION, "[一部幽霊] 期限"), scope, m -> {})
                .value();
        String ghostStatement = raw.findings().get(1).statement();

        var answer = pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[一部幽霊] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.askerExplanation()).contains(raw.findings().get(0).statement()).doesNotContain(ghostStatement);
        assertThat(answer.memo().findings()).containsExactly(raw.findings().get(0).statement());
        assertThat(answer.memo().unconfirmed()).containsExactly(ghostStatement);
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    void 幽霊出典タグは事実が全部落ちて根拠なしになりunconfirmedに残る() {
        var answer = pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[幽霊出典] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.askerExplanation()).isNull();
        assertThat(answer.memo().findings()).isEmpty();
        assertThat(answer.memo().unconfirmed()).hasSize(2);
    }

    @Test
    void 根拠なしタグは事実を返さず根拠なしになる() {
        var answer = pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[根拠なし] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.memo().unconfirmed()).isEmpty();
        assertThat(answer.memo().notFound()).isNotEmpty();
    }

    @Test
    void エラータグは調査で_拒否タグは調査で_説明エラータグは説明で失敗する() {
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[エラー] x"), scope, m -> {}))
                .isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[拒否] x"), scope, m -> {}))
                .isInstanceOf(AiRefusalException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryCategory.SPEC_QUESTION, "[説明エラー] x"), scope, m -> {}))
                .isInstanceOf(dev.inquiryassist.core.skill.ExplanationFailedException.class)
                .satisfies(e -> {
                    var failed = (dev.inquiryassist.core.skill.ExplanationFailedException) e;
                    assertThat(failed.investigation().confirmed()).extracting(Finding::statement).hasSize(2);
                });
    }

    @Test
    void 遅延タグは進捗の各段階で設定した時間だけ待つ() {
        List<Long> times = new ArrayList<>();
        long start = System.nanoTime();

        investigator.investigate(inquiry(InquiryCategory.SPEC_QUESTION, "[遅延] x"), scope, m -> times.add(System.nanoTime()));

        long previous = start;
        for (long t : times) {
            assertThat(Duration.ofNanos(t - previous)).isGreaterThanOrEqualTo(DELAY.minusMillis(5));
            previous = t;
        }
        assertThat(times).hasSize(3);
    }

    @Test
    void 遅延タグがなければ待たない() {
        long start = System.nanoTime();

        investigator.investigate(inquiry(InquiryCategory.SPEC_QUESTION, "x"), scope, m -> {});

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELAY);
    }

    @Test
    void 調査は回答を出さない種別を渡すと拒否する() {
        assertThatThrownBy(() -> investigator.investigate(inquiry(InquiryCategory.WORK_REQUEST, "x"), scope, m -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
