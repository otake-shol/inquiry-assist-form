package dev.inquiryassist.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
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
            new SystemOption(
                    "job-admin",
                    "求人管理画面",
                    List.of(new FunctionOption("posting", "求人の掲載"), new FunctionOption("applicants", "応募者の一覧"))),
            new SystemOption("billing", "請求管理", List.of(new FunctionOption("invoice", "請求書"))),
            new SystemOption("reports", "レポート"));
    private static final FunctionOption POSTING = SYSTEMS.get(0).functions().get(0);

    private final MockReceptionSkill reception = new MockReceptionSkill();
    private final MockInterviewerSkill interviewer = new MockInterviewerSkill();
    private final MockRestaterSkill restater = new MockRestaterSkill();
    private final MockInvestigatorSkill investigator = new MockInvestigatorSkill(DELAY);
    private final MockExplainerSkill explainer = new MockExplainerSkill();
    private final AccessScope scope = new AccessScope("u", Set.of("job-board", "faq", "glossary"));
    private final CitationVerifier verifier = new CitationVerifier(new MockKnowledgeSearch());

    private StructuredInquiry inquiry(InquiryType type, String goal) {
        return new StructuredInquiry("言い直し", type, null, goal, null, null);
    }

    private AnswerPipeline pipeline() {
        return new AnswerPipeline(investigator, verifier, explainer);
    }

    // ---- 受付 ----

    @ParameterizedTest
    @CsvSource({
        "ここを変えてほしい, CHANGE_REQUEST",
        "要望です, CHANGE_REQUEST",
        "一覧を改善してください, CHANGE_REQUEST", // 変えてほしい系が依頼より優先
        "掲載の延長をお願いします, DO_FOR_ME",
        "依頼したいがバグかもしれない, DO_FOR_ME", // 依頼がバグより優先
        "一覧の表示がおかしい, LOOKS_WRONG",
        "不具合かも, LOOKS_WRONG",
        "バグだと思う, LOOKS_WRONG",
        "おかしいが手順を知りたい, LOOKS_WRONG", // おかしいが使い方より優先
        "使い方を教えて, HOW_TO",
        "やり方が分からない, HOW_TO",
        "手順を知りたい, HOW_TO",
        "どうやって登録しますか, HOW_TO",
        "期限の仕様を教えて, HOW_IT_WORKS"
    })
    void 受付はキーワードの優先順位で種類を決める(String text, InquiryType expected) {
        assertThat(reception.receive(text, SYSTEMS, Placement.NONE).value().type()).isEqualTo(expected);
    }

    @Test
    void 受付は文章にシステムの名前があればそのシステムを置く() {
        assertThat(reception.receive("請求管理の期限の仕様", SYSTEMS, Placement.NONE).value().systemId())
                .isEqualTo("billing");
        assertThat(reception.receive("期限の仕様", SYSTEMS, Placement.NONE).value().systemId()).isNull();
    }

    @Test
    void 受付はシステムが決まっていて文章に機能の名前があれば機能を置く() {
        // 文章に機能の名前だけがあっても、システムが決まらなければ機能は置かない
        assertThat(reception.receive("求人の掲載の仕様", SYSTEMS, Placement.NONE).value().functionId()).isNull();
        var fromText = reception.receive("求人管理画面の求人の掲載の仕様", SYSTEMS, Placement.NONE).value();
        assertThat(fromText.systemId()).isEqualTo("job-admin");
        assertThat(fromText.functionId()).isEqualTo("posting");
        // 本人が選んだシステムの機能も置ける
        assertThat(reception.receive("応募者の一覧の仕様", SYSTEMS, new Placement(null, "job-admin", null)).value().functionId())
                .isEqualTo("applicants");
        // 文章に機能の名前がなければ置かない
        assertThat(reception.receive("求人管理画面の仕様", SYSTEMS, Placement.NONE).value().functionId()).isNull();
    }

    @Test
    void 受付は緊急度を文章から決めない() {
        assertThat(reception.receive("至急。止まっていて障害です", SYSTEMS, Placement.NONE).value().type())
                .isEqualTo(InquiryType.HOW_IT_WORKS);
    }

    @Test
    void 判定エラータグは受付でAI利用不可になる() {
        assertThatThrownBy(() -> reception.receive("[判定エラー] 期限の仕様", SYSTEMS, Placement.NONE))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 聞き手 ----

    @Test
    void 聞き手は1問を返し_idはq1で選択肢のidはo1から() {
        var questions = interviewer
                .ask("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, scope)
                .value()
                .questions();

        assertThat(questions).singleElement().satisfies(q -> {
            assertThat(q.id()).isEqualTo("q1");
            assertThat(q.text()).isEqualTo("どの場面での話ですか");
            assertThat(q.options()).containsExactly("画面を開いたとき", "保存したとき", "一覧を見たとき");
        });
    }

    @Test
    void 聞き手は文章がなくても質問を返す() {
        assertThat(interviewer.ask(null, null, SYSTEMS.get(0), POSTING, scope).value().questions()).hasSize(1);
    }

    @Test
    void 質問なしタグは詳しい質問を出さず質問エラータグは聞き手が失敗する() {
        assertThat(interviewer.ask("[質問なし] 仕様", InquiryType.HOW_IT_WORKS, null, null, scope).value().questions())
                .isEmpty();
        assertThatThrownBy(() -> interviewer.ask("[質問エラー] 仕様", InquiryType.HOW_IT_WORKS, null, null, scope))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 言い直し ----

    private static List<AnsweredQuestion> answers(String... textAndAnswer) {
        List<AnsweredQuestion> list = new ArrayList<>();
        for (int i = 0; i < textAndAnswer.length; i += 2) {
            list.add(new AnsweredQuestion(new FollowUpQuestion("q" + (i / 2 + 1), textAndAnswer[i], List.of()), textAndAnswer[i + 1]));
        }
        return list;
    }

    @Test
    void 言い直しは確定したシステムの名前をtargetSystemに入れgoalは本人が書いた文章のまま() {
        var withSystem = restater
                .restate("[遅延] x", InquiryType.HOW_IT_WORKS, SYSTEMS.get(1), null, List.of(), List.of())
                .value();
        var without = restater.restate("x", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of()).value();

        assertThat(withSystem.targetSystem()).isEqualTo("請求管理");
        assertThat(withSystem.goal()).isEqualTo("[遅延] x");
        assertThat(withSystem.type()).isEqualTo(InquiryType.HOW_IT_WORKS);
        assertThat(without.targetSystem()).isNull();
    }

    @Test
    void 言い直しは置き場所の前置きと種類ごとの文で始まる() {
        assertThat(summary(InquiryType.HOW_TO, SYSTEMS.get(0), POSTING))
                .isEqualTo("求人管理画面の求人の掲載について、使い方を知りたい。");
        assertThat(summary(InquiryType.HOW_IT_WORKS, SYSTEMS.get(0), null))
                .isEqualTo("求人管理画面について、いまの仕組みがどうなっているかを知りたい。");
        assertThat(summary(InquiryType.LOOKS_WRONG, null, null))
                .isEqualTo("動きがおかしいかもしれない。意図した動きかどうかを確認したい。");
        assertThat(summary(InquiryType.DO_FOR_ME, SYSTEMS.get(1), null)).isEqualTo("請求管理について、作業をしてほしい。");
        assertThat(summary(InquiryType.CHANGE_REQUEST, null, null)).isEqualTo("変えてほしい点がある。");
        assertThat(summary(null, null, null)).isEqualTo("相談したいことがある。");
    }

    private String summary(InquiryType type, SystemOption system, FunctionOption function) {
        return restater.restate(null, type, system, function, List.of(), List.of()).value().summary();
    }

    @Test
    void 言い直しは文章のタグを取り除いて続け_goalにはそのまま入れる() {
        var result = restater
                .restate("[質問なし] [遅延] 期限", InquiryType.HOW_TO, null, null, List.of(), List.of())
                .value();

        assertThat(result.summary()).isEqualTo("使い方を知りたい。 書かれた内容: 期限");
        assertThat(result.goal()).isEqualTo("[質問なし] [遅延] 期限");
    }

    @Test
    void 言い直しは文章がなければgoalに種類ごとの文を入れる() {
        var result = restater.restate(null, InquiryType.DO_FOR_ME, null, null, List.of(), List.of()).value();

        assertThat(result.goal()).isEqualTo("作業をしてほしい。");
    }

    @Test
    void 言い直しは詳しい質問の答えのうちわからない以外を続ける() {
        var result = restater
                .restate(
                        null,
                        InquiryType.LOOKS_WRONG,
                        SYSTEMS.get(0),
                        POSTING,
                        answers("いつから起きていますか", "今日から", "どの場面での話ですか", null, "対象の取引先はどこですか", "A社"),
                        List.of())
                .value();

        assertThat(result.summary())
                .isEqualTo("求人管理画面の求人の掲載について、動きがおかしいかもしれない。意図した動きかどうかを確認したい。"
                        + " （いつから起きていますか: 今日から） （対象の取引先はどこですか: A社）");
    }

    @Test
    void 言い直しは置き場所と文章と答えと訂正を順につなぐ() {
        var result = restater
                .restate(
                        "表示がおかしい",
                        InquiryType.LOOKS_WRONG,
                        SYSTEMS.get(0),
                        null,
                        answers("いつから起きていますか", "今日から"),
                        List.of("一昨日から"))
                .value();

        assertThat(result.summary())
                .isEqualTo("求人管理画面について、動きがおかしいかもしれない。意図した動きかどうかを確認したい。"
                        + " 書かれた内容: 表示がおかしい （いつから起きていますか: 今日から） 補足: 一昨日から");
    }

    @Test
    void 訂正があれば伝えられた順に補足を足しgoalを変えない() {
        var first = restater.restate("期限", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of()).value();

        var revised = restater
                .restate("期限", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of("一昨日から", "画面は管理画面"))
                .value();

        assertThat(revised.summary()).isEqualTo(first.summary() + " 補足: 一昨日から 補足: 画面は管理画面");
        assertThat(revised.goal()).isEqualTo(first.goal());
    }

    @Test
    void 言い直しエラータグは言い直しが失敗する() {
        assertThatThrownBy(() -> restater.restate("[言い直しエラー] x", null, null, null, List.of(), List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 調査・説明（パイプライン経由） ----

    @Test
    void 正常系は事実2件がすべて確かめられ説明は事実の文をつないだものになる() {
        List<String> progress = new ArrayList<>();

        var result = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "期限の仕様"), scope, progress::add);

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
    void おかしいかもしれないの説明は現在の動作として書き仕様の正否を断定しない() {
        var answer = pipeline().run(inquiry(InquiryType.LOOKS_WRONG, "表示がおかしい"), scope, m -> {}).answer();

        assertThat(answer.askerExplanation())
                .startsWith("現在の実装では、")
                .endsWith("この動作が意図した仕様かどうかは、担当者が確認します。")
                .doesNotContain("バグです")
                .doesNotContain("誤りです");
    }

    @Test
    void 説明は通常は問いに答えていると判定し的外れタグでは答えていないと判定する() {
        var normal = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "期限"), scope, m -> {}).answer();
        var offTopic = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[的外れ] 期限"), scope, m -> {}).answer();

        assertThat(normal.grounded()).isTrue();
        assertThat(normal.answered()).isTrue();
        // 調査は通常の事実を返すので根拠はあるが、答えとしては見せない
        assertThat(offTopic.grounded()).isTrue();
        assertThat(offTopic.answered()).isFalse();
        assertThat(offTopic.askerExplanation()).isNotBlank();
        assertThat(offTopic.citations()).hasSize(2);
    }

    @Test
    void 一部幽霊タグは確かめられた事実だけから説明を書き落ちた事実は説明に現れない() {
        Investigation raw = investigator
                .investigate(inquiry(InquiryType.HOW_IT_WORKS, "[一部幽霊] 期限"), scope, m -> {})
                .value();
        String ghostStatement = raw.findings().get(1).statement();

        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[一部幽霊] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.askerExplanation()).contains(raw.findings().get(0).statement()).doesNotContain(ghostStatement);
        assertThat(answer.memo().findings()).containsExactly(raw.findings().get(0).statement());
        assertThat(answer.memo().unconfirmed()).containsExactly(ghostStatement);
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    void 幽霊出典タグは事実が全部落ちて根拠なしになりunconfirmedに残る() {
        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[幽霊出典] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.askerExplanation()).isNull();
        assertThat(answer.memo().findings()).isEmpty();
        assertThat(answer.memo().unconfirmed()).hasSize(2);
    }

    @Test
    void 根拠なしタグは事実を返さず根拠なしになる() {
        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[根拠なし] 期限"), scope, m -> {}).answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.memo().unconfirmed()).isEmpty();
        assertThat(answer.memo().notFound()).isNotEmpty();
    }

    @Test
    void エラータグは調査で_拒否タグは調査で_説明エラータグは説明で失敗する() {
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[エラー] x"), scope, m -> {}))
                .isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[拒否] x"), scope, m -> {}))
                .isInstanceOf(AiRefusalException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[説明エラー] x"), scope, m -> {}))
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

        investigator.investigate(inquiry(InquiryType.HOW_IT_WORKS, "[遅延] x"), scope, m -> times.add(System.nanoTime()));

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

        investigator.investigate(inquiry(InquiryType.HOW_IT_WORKS, "x"), scope, m -> {});

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELAY);
    }

    @Test
    void 調査は調べない種類を渡すと拒否し_種類が決まっていなければ調べる() {
        assertThatThrownBy(() -> investigator.investigate(inquiry(InquiryType.CHANGE_REQUEST, "x"), scope, m -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(investigator.investigate(inquiry(null, "x"), scope, m -> {}).value().findings()).hasSize(2);
        assertThat(investigator.investigate(inquiry(InquiryType.DO_FOR_ME, "x"), scope, m -> {}).value().findings())
                .hasSize(2);
    }
}
