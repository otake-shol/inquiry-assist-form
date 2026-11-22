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
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.QuestionKind;
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
                .ask("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, scope, "ja")
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
        assertThat(interviewer.ask(null, null, SYSTEMS.get(0), POSTING, scope, "ja").value().questions()).hasSize(1);
    }

    @Test
    void 質問なしタグは詳しい質問を出さず質問エラータグは聞き手が失敗する() {
        assertThat(interviewer.ask("[質問なし] 仕様", InquiryType.HOW_IT_WORKS, null, null, scope, "ja").value().questions())
                .isEmpty();
        assertThatThrownBy(() -> interviewer.ask("[質問エラー] 仕様", InquiryType.HOW_IT_WORKS, null, null, scope, "ja"))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 聞き手（答えを読み取る） ----

    private static final IntakeQuestion SINCE = new IntakeQuestion(
            "since",
            QuestionKind.DETAIL,
            "いつから",
            List.of(new Choice("today", "今日から"), new Choice("week", "今週から"), new Choice("earlier", "もっと前から")),
            false,
            false);
    private static final IntakeQuestion PARTNER =
            new IntakeQuestion("partner", QuestionKind.DETAIL, "取引先", List.of(), false, false);

    @Test
    void 読み取りは選択肢の名前が文章に含まれていればその選択肢を返し_複数あれば設定の順で先のもの() {
        var one = interviewer.prefill("今朝ではなく今日から出ません", List.of(SINCE)).value();
        var both = interviewer.prefill("今週からというか、今日からです", List.of(SINCE)).value();

        assertThat(one.answers()).singleElement().satisfies(a -> {
            assertThat(a.questionId()).isEqualTo("since");
            assertThat(a.optionId()).isEqualTo("today");
            assertThat(a.text()).isNull();
        });
        assertThat(both.answers()).singleElement().satisfies(a -> assertThat(a.optionId()).isEqualTo("today"));
    }

    @Test
    void 読み取りは名前が含まれなければ何も返さず_文章で答える質問は読み取らない() {
        assertThat(interviewer.prefill("表示がおかしい", List.of(SINCE)).value().answers()).isEmpty();
        assertThat(interviewer.prefill("A社の件です", List.of(PARTNER)).value().answers()).isEmpty();
        assertThat(interviewer.prefill("今日から。A社", List.of(PARTNER, SINCE)).value().answers())
                .extracting(a -> a.questionId())
                .containsExactly("since");
    }

    @Test
    void 質問エラータグは読み取りでもAI利用不可になる() {
        assertThatThrownBy(() -> interviewer.prefill("[質問エラー] 今日から", List.of(SINCE)))
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
                .restate("[遅延] x", InquiryType.HOW_IT_WORKS, SYSTEMS.get(1), null, List.of(), List.of(), "ja")
                .value();
        var without = restater.restate("x", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja").value();

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
        return restater.restate(null, type, system, function, List.of(), List.of(), "ja").value().summary();
    }

    @Test
    void 言い直しは文章のタグを取り除いて続け_goalにはそのまま入れる() {
        var result = restater
                .restate("[質問なし] [遅延] 期限", InquiryType.HOW_TO, null, null, List.of(), List.of(), "ja")
                .value();

        assertThat(result.summary()).isEqualTo("使い方を知りたい。 書かれた内容: 期限");
        assertThat(result.goal()).isEqualTo("[質問なし] [遅延] 期限");
    }

    @Test
    void 言い直しは文章がなければgoalに種類ごとの文を入れる() {
        var result = restater.restate(null, InquiryType.DO_FOR_ME, null, null, List.of(), List.of(), "ja").value();

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
                        List.of(), "ja")
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
                        List.of("一昨日から"), "ja")
                .value();

        assertThat(result.summary())
                .isEqualTo("求人管理画面について、動きがおかしいかもしれない。意図した動きかどうかを確認したい。"
                        + " 書かれた内容: 表示がおかしい （いつから起きていますか: 今日から） 補足: 一昨日から");
    }

    @Test
    void 訂正があれば伝えられた順に補足を足しgoalを変えない() {
        var first = restater.restate("期限", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja").value();

        var revised = restater
                .restate("期限", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of("一昨日から", "画面は管理画面"), "ja")
                .value();

        assertThat(revised.summary()).isEqualTo(first.summary() + " 補足: 一昨日から 補足: 画面は管理画面");
        assertThat(revised.goal()).isEqualTo(first.goal());
    }

    @Test
    void 言い直しエラータグは言い直しが失敗する() {
        assertThatThrownBy(() -> restater.restate("[言い直しエラー] x", null, null, null, List.of(), List.of(), "ja"))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- 調査・説明（パイプライン経由） ----

    @Test
    void 正常系は事実2件がすべて確かめられ説明は事実の文をつないだものになる() {
        List<String> progress = new ArrayList<>();

        var result = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "期限の仕様"), scope, progress::add, "ja");

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
        var answer = pipeline().run(inquiry(InquiryType.LOOKS_WRONG, "表示がおかしい"), scope, m -> {}, "ja").answer();

        assertThat(answer.askerExplanation())
                .startsWith("現在の実装では、")
                .endsWith("この動作が意図した仕様かどうかは、担当者が確認します。")
                .doesNotContain("バグです")
                .doesNotContain("誤りです");
    }

    @Test
    void 説明は通常は問いに答えていると判定し的外れタグでは答えていないと判定する() {
        var normal = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "期限"), scope, m -> {}, "ja").answer();
        var offTopic = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[的外れ] 期限"), scope, m -> {}, "ja").answer();

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
                .investigate(inquiry(InquiryType.HOW_IT_WORKS, "[一部幽霊] 期限"), scope, m -> {}, "ja")
                .value();
        String ghostStatement = raw.findings().get(1).statement();

        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[一部幽霊] 期限"), scope, m -> {}, "ja").answer();

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.askerExplanation()).contains(raw.findings().get(0).statement()).doesNotContain(ghostStatement);
        assertThat(answer.memo().findings()).containsExactly(raw.findings().get(0).statement());
        assertThat(answer.memo().unconfirmed()).containsExactly(ghostStatement);
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    void 幽霊出典タグは事実が全部落ちて根拠なしになりunconfirmedに残る() {
        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[幽霊出典] 期限"), scope, m -> {}, "ja").answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.askerExplanation()).isNull();
        assertThat(answer.memo().findings()).isEmpty();
        assertThat(answer.memo().unconfirmed()).hasSize(2);
    }

    @Test
    void 根拠なしタグは事実を返さず根拠なしになる() {
        var answer = pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[根拠なし] 期限"), scope, m -> {}, "ja").answer();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.memo().unconfirmed()).isEmpty();
        assertThat(answer.memo().notFound()).isNotEmpty();
    }

    @Test
    void エラータグは調査で_拒否タグは調査で_説明エラータグは説明で失敗する() {
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[エラー] x"), scope, m -> {}, "ja"))
                .isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[拒否] x"), scope, m -> {}, "ja"))
                .isInstanceOf(AiRefusalException.class);
        assertThatThrownBy(() -> pipeline().run(inquiry(InquiryType.HOW_IT_WORKS, "[説明エラー] x"), scope, m -> {}, "ja"))
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

        investigator.investigate(inquiry(InquiryType.HOW_IT_WORKS, "[遅延] x"), scope, m -> times.add(System.nanoTime()), "ja");

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

        investigator.investigate(inquiry(InquiryType.HOW_IT_WORKS, "x"), scope, m -> {}, "ja");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELAY);
    }

    @Test
    void 調査は調べない種類を渡すと拒否し_種類が決まっていなければ調べる() {
        assertThatThrownBy(() -> investigator.investigate(inquiry(InquiryType.CHANGE_REQUEST, "x"), scope, m -> {}, "ja"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(investigator.investigate(inquiry(null, "x"), scope, m -> {}, "ja").value().findings()).hasSize(2);
        assertThat(investigator.investigate(inquiry(InquiryType.DO_FOR_ME, "x"), scope, m -> {}, "ja").value().findings())
                .hasSize(2);
    }

    // ---- 英語（契約書「モックのシナリオ」の英語の節） ----

    private static final List<SystemOption> BILINGUAL = List.of(
            new SystemOption(
                    "job-admin",
                    "求人管理画面",
                    List.of("求人管理画面", "Job admin"),
                    List.of(new FunctionOption("posting", "求人の掲載", List.of("求人の掲載", "Posting")))),
            new SystemOption("billing", "請求管理", List.of("請求管理", "Billing"), List.of()));

    @ParameterizedTest
    @CsvSource({
        "Could you change this please, CHANGE_REQUEST", // 変えてほしい系より、依頼の語は後
        "I have a feature request, CHANGE_REQUEST",
        "Please extend the deadline, DO_FOR_ME",
        "It looks WRONG to me, LOOKS_WRONG",
        "There is a Bug, LOOKS_WRONG",
        "How to register a posting, HOW_TO",
        "How do I register, HOW_TO",
        "What is the deadline rule, HOW_IT_WORKS"
    })
    void 受付は英語のキーワードも大文字と小文字を区別せずに種類を決める(String text, InquiryType expected) {
        // 依頼の語（please）は変えてほしい（improve）より後に判定するため、"Could you change this please" は依頼
        InquiryType actual = reception.receive(text, SYSTEMS, Placement.NONE).value().type();
        assertThat(actual).isEqualTo(text.startsWith("Could you change") ? InquiryType.DO_FOR_ME : expected);
    }

    @Test
    void 受付はどの言語のシステムと機能の名前でも大文字と小文字を区別せずに置く() {
        var result = reception.receive("billing is wrong", BILINGUAL, Placement.NONE).value();
        assertThat(result.systemId()).isEqualTo("billing");
        var fn = reception.receive("The POSTING in JOB ADMIN", BILINGUAL, Placement.NONE).value();
        assertThat(fn.systemId()).isEqualTo("job-admin");
        assertThat(fn.functionId()).isEqualTo("posting");
        // 日本語の名前も、英語の設定のままで読み取れる
        assertThat(reception.receive("請求管理の件", BILINGUAL, Placement.NONE).value().systemId()).isEqualTo("billing");
    }

    @Test
    void 聞き手は英語なら質問と選択肢を英語で返し_それ以外は日本語() {
        var en = interviewer.ask("x", InquiryType.HOW_IT_WORKS, null, null, scope, "en").value().questions().get(0);
        assertThat(en.text()).isEqualTo("In what situation does this happen?");
        assertThat(en.options()).containsExactly("When opening a screen", "When saving", "When viewing a list");
        var enUs = interviewer.ask("x", InquiryType.HOW_IT_WORKS, null, null, scope, "en-US").value().questions().get(0);
        assertThat(enUs.text()).isEqualTo("In what situation does this happen?");
        var ja = interviewer.ask("x", InquiryType.HOW_IT_WORKS, null, null, scope, "ja").value().questions().get(0);
        assertThat(ja.text()).isEqualTo("どの場面での話ですか");
        var other = interviewer.ask("x", InquiryType.HOW_IT_WORKS, null, null, scope, "fr").value().questions().get(0);
        assertThat(other.text()).isEqualTo("どの場面での話ですか");
    }

    @Test
    void 言い直しは英語なら英語で組み立て_本人の文章はそのまま() {
        var result = restater.restate(
                        "Deadline question",
                        InquiryType.HOW_IT_WORKS,
                        new SystemOption("job-admin", "Job admin"),
                        null,
                        List.of(),
                        List.of("since Monday"),
                        "en")
                .value();
        assertThat(result.summary())
                .startsWith("About Job admin: I want to know how it currently works.")
                .contains("What was written: Deadline question")
                .contains("Note: since Monday");
    }

    @Test
    void 調査は英語なら事実と次に確認することと進捗を英語で返し_根拠の引用は変えない() {
        var progress = new ArrayList<String>();
        var inv = investigator
                .investigate(inquiry(InquiryType.HOW_IT_WORKS, "deadline"), scope, progress::add, "en")
                .value();
        var jaInv = investigator
                .investigate(inquiry(InquiryType.HOW_IT_WORKS, "deadline"), scope, m -> {}, "ja")
                .value();

        assertThat(progress).containsExactly("Searching the code", "Checking the FAQ", "Matching the glossary");
        assertThat(inv.findings()).extracting(Finding::statement).allSatisfy(s -> assertThat(s).matches("^[\\x00-\\x7F]+$"));
        assertThat(inv.nextChecks()).allSatisfy(s -> assertThat(s).matches("^[\\x00-\\x7F]+$"));
        // 根拠は、言語によらず同じ
        assertThat(inv.findings().stream().map(Finding::evidence).toList())
                .isEqualTo(jaInv.findings().stream().map(Finding::evidence).toList());
    }

    @Test
    void 説明は英語ならつなぎの文も英語() {
        var pipelineResult = new AnswerPipeline(investigator, verifier, explainer)
                .run(inquiry(InquiryType.LOOKS_WRONG, "looks wrong"), scope, m -> {}, "en");
        String text = pipelineResult.answer().askerExplanation();
        assertThat(text).startsWith("In the current implementation: ");
        assertThat(text).endsWith("Whether this behavior is intended will be confirmed by the support team.");
        assertThat(text).matches("^[\\x00-\\x7F]+$");
    }

    // ---- 助言と下書き（契約書「助言と下書き」） ----

    private static List<dev.inquiryassist.core.skill.Finding> twoFindings() {
        var citation = new dev.inquiryassist.core.Citation(
                dev.inquiryassist.core.Citation.SourceType.FAQ, "faq", "a.md", 1, 1, "x", null);
        return List.of(
                new dev.inquiryassist.core.skill.Finding("事実1", List.of(citation)),
                new dev.inquiryassist.core.skill.Finding("事実2", List.of(citation)));
    }

    private final MockAdvisorSkill advisor = new MockAdvisorSkill();
    private final MockDrafterSkill drafter = new MockDrafterSkill();

    @Test
    void 助言はタグがなければ判断の案を1つ返し_1件目の事実に基づく() {
        var advice = advisor.advise(inquiry(InquiryType.HOW_IT_WORKS, "期限"), InquiryType.HOW_IT_WORKS, twoFindings(), "ja")
                .value();

        assertThat(advice.judgment().text()).isEqualTo("資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。");
        assertThat(advice.judgment().needsConsultation()).isFalse();
        assertThat(advice.judgment().consultationReason()).isNull();
        assertThat(advice.judgment().basedOn()).containsExactly(1);
        // 種類が DO_FOR_ME と CHANGE_REQUEST 以外は、手順の案を返さない
        assertThat(advice.procedure()).isNull();
    }

    @Test
    void 助言はDO_FOR_MEとCHANGE_REQUESTで手順の案も返し_最後の事実に基づく() {
        for (InquiryType type : List.of(InquiryType.DO_FOR_ME, InquiryType.CHANGE_REQUEST)) {
            var procedure = advisor.advise(inquiry(type, "延長"), type, twoFindings(), "ja").value().procedure();

            assertThat(procedure.steps()).hasSize(2);
            assertThat(procedure.rollback()).isEqualTo("掲載期限を、元の日付に戻す");
            assertThat(procedure.basedOn()).containsExactly(2);
        }
    }

    @Test
    void 助言の相談_手順_根拠なしの案のタグ() {
        var consult = advisor.advise(inquiry(InquiryType.HOW_TO, "[相談] 期限"), InquiryType.HOW_TO, twoFindings(), "ja")
                .value()
                .judgment();
        assertThat(consult.needsConsultation()).isTrue();
        assertThat(consult.consultationReason()).isNotBlank();

        // [手順] は、種類にかかわらず手順の案を返す
        var withProcedure = advisor.advise(inquiry(InquiryType.HOW_TO, "[手順] 期限"), InquiryType.HOW_TO, twoFindings(), "ja")
                .value();
        assertThat(withProcedure.procedure()).isNotNull();

        // [根拠なしの案] は、存在しない番号だけに基づく案を返す（コードが捨てる）
        var ungrounded = advisor.advise(
                        inquiry(InquiryType.HOW_TO, "[根拠なしの案] 期限"), InquiryType.HOW_TO, twoFindings(), "ja")
                .value();
        assertThat(ungrounded.judgment().basedOn()).containsExactly(999);
        var verified = dev.inquiryassist.core.AdviceVerifier.verify(ungrounded, 2);
        assertThat(verified.judgment()).isNull();
    }

    @Test
    void 助言のエラータグはAI利用不可になる() {
        assertThatThrownBy(() -> advisor.advise(
                        inquiry(InquiryType.HOW_TO, "[案エラー] 期限"), InquiryType.HOW_TO, twoFindings(), "ja"))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 助言は英語なら英語で書く() {
        var advice = advisor.advise(inquiry(InquiryType.DO_FOR_ME, "x"), InquiryType.DO_FOR_ME, twoFindings(), "en").value();

        assertThat(advice.judgment().text()).matches("^[\\x00-\\x7F]+$");
        assertThat(advice.procedure().steps()).allSatisfy(s -> assertThat(s.action()).matches("^[\\x00-\\x7F]+$"));
        assertThat(advice.procedure().rollback()).matches("^[\\x00-\\x7F]+$");
    }

    private dev.inquiryassist.core.skill.DraftRequest draftRequest(
            dev.inquiryassist.core.lifecycle.ReplyKind kind, String goal, String note, String judgment, String language) {
        return new dev.inquiryassist.core.skill.DraftRequest(
                kind,
                goal == null ? null : inquiry(InquiryType.HOW_IT_WORKS, goal),
                twoFindings(),
                judgment,
                List.of(),
                note,
                language);
    }

    @Test
    void 下書きの聞き返しは決まった文章で_noteがあればそのまま入る() {
        var kind = dev.inquiryassist.core.lifecycle.ReplyKind.ASK;

        assertThat(drafter.draft(draftRequest(kind, "期限", null, null, "ja")).value().text())
                .isEqualTo("確認させてください。起きている場面を、もう少し詳しく教えていただけますか。");
        assertThat(drafter.draft(draftRequest(kind, "期限", "画面名を教えてください", null, "ja")).value().text())
                .isEqualTo("確認させてください。画面名を教えてください");
        assertThat(drafter.draft(draftRequest(kind, "期限", "Which screen?", null, "en")).value().text())
                .isEqualTo("Let me check one thing. Which screen?");
        assertThat(drafter.draft(draftRequest(kind, "期限", null, null, "en")).value().text())
                .startsWith("Let me check one thing. ")
                .matches("^[\\x00-\\x7F]+$");
    }

    @Test
    void 下書きの結果はnote_判断の案_1件目の事実の順で入る() {
        var kind = dev.inquiryassist.core.lifecycle.ReplyKind.RESULT;
        String lead = "お待たせしました。確認した結果をお伝えします。";

        assertThat(drafter.draft(draftRequest(kind, "期限", "仕様どおりです", "判断の案", "ja")).value().text())
                .isEqualTo(lead + "仕様どおりです");
        assertThat(drafter.draft(draftRequest(kind, "期限", null, "判断の案", "ja")).value().text())
                .isEqualTo(lead + "判断の案");
        assertThat(drafter.draft(draftRequest(kind, "期限", null, null, "ja")).value().text()).isEqualTo(lead + "事実1");
        assertThat(drafter.draft(draftRequest(kind, "期限", "It works as documented.", null, "en")).value().text())
                .isEqualTo("Thank you for waiting. Here is what we found. It works as documented.");
        // 言い直しがなくても書ける
        assertThat(drafter.draft(draftRequest(kind, null, null, null, "ja")).value().text()).isEqualTo(lead + "事実1");
    }

    @Test
    void 下書きのエラータグはAI利用不可になる() {
        assertThatThrownBy(() -> drafter.draft(draftRequest(
                        dev.inquiryassist.core.lifecycle.ReplyKind.ASK, "[下書きエラー] 期限", null, null, "ja")))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 新しいタグは言い直しの文章から取り除かれる() {
        var restated = restater.restate(
                        "[相談] [手順] [根拠なしの案] [案エラー] [下書きエラー] 期限",
                        InquiryType.HOW_IT_WORKS,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "ja")
                .value();

        assertThat(restated.summary()).doesNotContain("[相談]", "[手順]", "[根拠なしの案]", "[案エラー]", "[下書きエラー]");
    }

    // ---- 説明が出す手順（契約書「説明が出す手順」） ----

    private dev.inquiryassist.core.skill.Explanation explain(InquiryType type, String goal, String language) {
        return explainer.explain(inquiry(type, goal), twoFindings(), language).value();
    }

    @Test
    void 説明は使い方とやってほしいで手順を2つ返す() {
        for (InquiryType type : List.of(InquiryType.HOW_TO, InquiryType.DO_FOR_ME)) {
            var steps = explain(type, "期限", "ja").steps();

            assertThat(steps).hasSize(2);
            assertThat(steps.get(0).action()).isEqualTo("求人管理画面で、対象の求人の編集を開く");
            assertThat(steps.get(0).check()).isNull();
            assertThat(steps.get(1).action()).isEqualTo("掲載期限を変えて、保存する");
            assertThat(steps.get(1).check()).isEqualTo("応募者向けの一覧に、求人が表示される");
        }
    }

    @Test
    void 説明は英語なら英語の手順を返す() {
        var steps = explain(InquiryType.HOW_TO, "x", "en").steps();

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo("Open the edit screen for the job posting in the job admin screen");
        assertThat(steps.get(0).check()).isNull();
        assertThat(steps.get(1).action()).isEqualTo("Change the posting deadline and save");
        assertThat(steps.get(1).check()).isEqualTo("The job posting appears in the list for applicants");
    }

    @Test
    void 説明は仕組みと不具合と種類なしと変えてほしいでは手順を返さない() {
        for (InquiryType type : new InquiryType[] {
            InquiryType.HOW_IT_WORKS, InquiryType.LOOKS_WRONG, InquiryType.CHANGE_REQUEST, null
        }) {
            assertThat(explain(type, "期限", "ja").steps()).as(String.valueOf(type)).isEmpty();
        }
    }

    @Test
    void 手順なしのタグと的外れのタグでは手順を返さない() {
        assertThat(explain(InquiryType.HOW_TO, "[手順なし] 期限", "ja").steps()).isEmpty();
        var offTopic = explain(InquiryType.HOW_TO, "[的外れ] 期限", "ja");
        assertThat(offTopic.answersQuestion()).isFalse();
        assertThat(offTopic.steps()).isEmpty();
        // 手順がなくても、説明の文章は返る
        assertThat(explain(InquiryType.HOW_TO, "[手順なし] 期限", "ja").text()).isNotBlank();
    }

    @Test
    void 手順なしのタグは言い直しの文章から取り除かれる() {
        var restated = restater.restate("[手順なし] 期限", InquiryType.HOW_TO, null, null, List.of(), List.of(), "ja").value();

        assertThat(restated.summary()).doesNotContain("[手順なし]");
    }

    // ---- 訳（契約書「訳」） ----

    private final MockTranslatorSkill translator = new MockTranslatorSkill();

    @Test
    void 訳は原文の前に印を付けて返し_訳す先の言語で印が変わる() {
        var toJa = translator.translate(List.of("The posting disappeared.", "Thanks"), "en", "ja").value();
        var toEn = translator.translate(List.of("求人が消えました。"), "ja", "en").value();
        var toEnUs = translator.translate(List.of("x"), "ja", "en-US").value();

        assertThat(toJa.texts()).containsExactly("【訳の見本】The posting disappeared.", "【訳の見本】Thanks");
        assertThat(toEn.texts()).containsExactly("[Sample translation] 求人が消えました。");
        assertThat(toEnUs.texts()).containsExactly("[Sample translation] x");
        // 渡した数と同じ数を、渡した順に返す
        assertThat(translator.translate(List.of(), "en", "ja").value().texts()).isEmpty();
    }

    @Test
    void 訳のエラータグは渡された文章のどれかにあれば失敗する() {
        assertThatThrownBy(() -> translator.translate(List.of("普通の文章", "[訳エラー] 失敗する文章"), "en", "ja"))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(translator.translate(List.of("普通の文章"), "en", "ja").value().texts()).hasSize(1);
    }

    @Test
    void 訳のタグは言い直しの文章から取り除かれる() {
        var restated = restater.restate("[訳エラー] 期限", InquiryType.HOW_TO, null, null, List.of(), List.of(), "ja").value();

        assertThat(restated.summary()).doesNotContain("[訳エラー]");
    }
}
