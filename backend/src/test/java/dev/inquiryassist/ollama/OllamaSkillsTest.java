package dev.inquiryassist.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ollama の4つのスキルを、テスト内の HTTP サーバーを Ollama の代わりにして確認する。 */
class OllamaSkillsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<SystemOption> SYSTEMS = List.of(
            new SystemOption(
                    "job-admin",
                    "求人管理画面",
                    List.of(new FunctionOption("posting", "求人の掲載"), new FunctionOption("applicants", "応募者の一覧"))),
            new SystemOption("billing", "請求管理", List.of(new FunctionOption("invoice", "請求書"))),
            new SystemOption("reports", "レポート"));
    private static final FunctionOption POSTING = SYSTEMS.get(0).functions().get(0);
    private static final AccessScope SCOPE = new AccessScope("u", Set.of("faq"));
    private static final String RECEPTION_OK = "{\"type\":\"HOW_IT_WORKS\",\"systemId\":null,\"functionId\":null}";
    private static final String RESTATE_OK =
            "{\"summary\":\"言い直し。\",\"targetSystem\":null,\"goal\":\"目的\",\"whatHappened\":null,"
                    + "\"expectedResult\":null}";

    private final FakeOllama ollama = new FakeOllama();

    @AfterEach
    void stop() {
        ollama.close();
    }

    private InquiryProperties properties(boolean think, Duration timeout) {
        SkillSettings settings = new SkillSettings("ollama", "test-model", 0, think, timeout, "v2");
        return new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of(
                        "reception", settings,
                        "interviewer", settings,
                        "restater", settings,
                        "explainer", settings),
                new InquiryProperties.Ollama(ollama.baseUrl(), "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("開発用ユーザー", "担当者（開発用）"),
                java.util.Map.of(),
                InquiryProperties.Rules.defaults());
    }

    private OllamaClient client() {
        return new OllamaClient(ollama.baseUrl(), "30m", 42, JSON);
    }

    private OllamaReceptionSkill reception() {
        return new OllamaReceptionSkill(client(), JSON, properties(false, Duration.ofSeconds(5)));
    }

    private OllamaInterviewerSkill interviewer() {
        return new OllamaInterviewerSkill(client(), JSON, properties(false, Duration.ofSeconds(5)));
    }

    private OllamaRestaterSkill restater() {
        return new OllamaRestaterSkill(client(), JSON, properties(false, Duration.ofSeconds(5)));
    }

    private OllamaExplainerSkill explainer() {
        return new OllamaExplainerSkill(client(), JSON, properties(true, Duration.ofSeconds(5)));
    }

    private JsonNode request(int index) {
        return JSON.readTree(ollama.requests().get(index));
    }

    private String userMessage(int index) {
        return request(index).path("messages").get(1).path("content").asString();
    }

    private static StructuredInquiry structured() {
        return new StructuredInquiry("本人の問い。", InquiryType.HOW_IT_WORKS, null, "目的", null, null);
    }

    private static List<Finding> findings(String... statements) {
        var citation = new dev.inquiryassist.core.Citation(
                dev.inquiryassist.core.Citation.SourceType.FAQ, "faq", "a.md", 1, 1, "x", null);
        return java.util.Arrays.stream(statements).map(s -> new Finding(s, List.of(citation))).toList();
    }

    // ---- (a) リクエストの形 ----

    @Test
    void リクエストにformatは付かずthink_temperature_keep_alive_streamとモデルとメッセージが入る() {
        ollama.reply(RECEPTION_OK, 1, 1);

        reception().receive("期限の仕様", SYSTEMS, Placement.NONE);

        JsonNode body = request(0);
        assertThat(ollama.paths()).containsExactly("/api/chat");
        assertThat(body.has("format")).isFalse();
        assertThat(body.path("think").asBoolean(true)).isFalse();
        assertThat(body.path("options").path("temperature").asDouble(-1)).isEqualTo(0.0);
        assertThat(body.path("keep_alive").asString()).isEqualTo("30m");
        assertThat(body.path("options").path("seed").asInt(-1)).isEqualTo(42);
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("model").asString()).isEqualTo("test-model");
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo(Prompts.systemOf(Skill.RECEPTION, "v2"));
        assertThat(body.path("messages").get(1).path("role").asString()).isEqualTo("user");
    }

    @Test
    void thinkは設定のとおりに送られる() {
        ollama.reply("{\"explanation\":\"説明です。\"}", 1, 1);

        explainer().explain(structured(), findings("事実"), "ja");

        assertThat(request(0).path("think").asBoolean(false)).isTrue();
        assertThat(request(0).has("format")).isFalse();
        assertThat(request(0).path("messages").get(0).path("content").asString())
                .isEqualTo(Prompts.systemOf(Skill.EXPLAINER, "v2"));
    }

    // ---- (b) 入力の組み立て ----

    @Test
    void 受付の入力は文章_システムの候補_既に決まっていることの見出しで組み立てる() {
        ollama.reply(RECEPTION_OK, 1, 1).reply(RECEPTION_OK, 1, 1).reply(RECEPTION_OK, 1, 1);

        reception().receive("期限の仕様", SYSTEMS, Placement.NONE);
        reception().receive("期限の仕様", SYSTEMS, new Placement(InquiryType.HOW_TO, "billing", null));
        reception().receive("期限の仕様", SYSTEMS, new Placement(null, "job-admin", "posting"));

        // 機能は、システムの下に2文字の字下げで並ぶ
        assertThat(userMessage(0))
                .isEqualTo("## 文章\n期限の仕様\n\n## システムの候補\n- job-admin: 求人管理画面\n  - posting: 求人の掲載\n"
                        + "  - applicants: 応募者の一覧\n- billing: 請求管理\n  - invoice: 請求書\n- reports: レポート\n\n"
                        + "## 既に決まっていること\nなし");
        assertThat(userMessage(1)).endsWith("## 既に決まっていること\n- 種類: HOW_TO\n- システム: billing");
        assertThat(userMessage(2)).endsWith("## 既に決まっていること\n- システム: job-admin\n- 機能: posting");
    }

    @Test
    void 聞き手の入力は種類_システム_機能_本人が書いた文章の見出しで組み立てる() {
        ollama.reply("{\"questions\":[]}", 1, 1).reply("{\"questions\":[]}", 1, 1);

        interviewer().ask("期限の仕様", InquiryType.LOOKS_WRONG, SYSTEMS.get(0), POSTING, SCOPE, "ja");
        interviewer().ask(null, null, null, null, SCOPE, "ja");

        assertThat(userMessage(0))
                .isEqualTo("## 種類\nLOOKS_WRONG\n\n## システム\n求人管理画面\n\n## 機能\n求人の掲載\n\n## 本人が書いた文章\n期限の仕様");
        assertThat(userMessage(1))
                .isEqualTo("## 種類\n（未定）\n\n## システム\nなし\n\n## 機能\nなし\n\n## 本人が書いた文章\n（なし）");
    }

    @Test
    void 言い直しの入力は置き場所_質問と答え_文章_訂正を見出しごとに組み立て訂正は伝えられた順に並ぶ() {
        ollama.reply(RESTATE_OK, 1, 1).reply(RESTATE_OK, 1, 1).reply(RESTATE_OK, 1, 1);
        var answers = List.of(
                new AnsweredQuestion(new FollowUpQuestion("q1", "どの画面ですか", List.of()), "求人管理画面"),
                new AnsweredQuestion(new FollowUpQuestion("q2", "いつからですか", List.of()), null));

        restater().restate("期限の仕様", InquiryType.HOW_IT_WORKS, SYSTEMS.get(0), POSTING, answers, List.of("一昨日から", "3件です"), "ja");
        restater().restate(null, null, null, null, List.of(), List.of(), "ja");
        restater().restate("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, answers, List.of(), "ja");

        assertThat(userMessage(0))
                .isEqualTo("## 種類\nHOW_IT_WORKS\n\n## システム\n求人管理画面\n\n## 機能\n求人の掲載\n\n"
                        + "## 質問と答え\n- どの画面ですか: 求人管理画面\n- いつからですか: （わからない）\n\n"
                        + "## 本人が書いた文章\n期限の仕様\n\n"
                        + "## 本人が後から直したこと\n- 一昨日から\n- 3件です");
        // 質問や文章がなければ「（なし）」。訂正がなければ見出しごと出さない
        assertThat(userMessage(1))
                .isEqualTo("## 種類\n（未定）\n\n## システム\nなし\n\n## 機能\nなし\n\n## 質問と答え\n（なし）\n\n"
                        + "## 本人が書いた文章\n（なし）");
        assertThat(userMessage(2)).doesNotContain("本人が後から直したこと");
    }

    @Test
    void 説明の入力は本人の問い_種類_確かめられた事実の見出しで組み立てる() {
        ollama.reply("{\"explanation\":\"説明です。\"}", 1, 1).reply("{\"explanation\":\"説明です。\"}", 1, 1);

        explainer().explain(structured(), findings("事実A", "事実B"), "ja");
        explainer().explain(new StructuredInquiry("本人の問い。", null, null, "目的", null, null), findings("事実A"), "ja");

        assertThat(userMessage(0))
                .isEqualTo("## 本人の問い\n本人の問い。\n\n## 種類\nHOW_IT_WORKS\n\n## 確かめられた事実\n- 事実A\n- 事実B");
        assertThat(userMessage(1)).contains("## 種類\n（未定）");
    }

    // ---- 聞き手（答えを読み取る） ----

    private static final IntakeQuestion SINCE = new IntakeQuestion(
            "since",
            QuestionKind.DETAIL,
            "いつから起きていますか",
            List.of(new Choice("today", "今日から"), new Choice("earlier", "もっと前から")),
            false,
            false);
    private static final IntakeQuestion PARTNER =
            new IntakeQuestion("partner", QuestionKind.DETAIL, "対象の取引先はどこですか", List.of(), false, false);

    @Test
    void 読み取りは専用の指示文と見出しの入力で呼び_利用量のpromptVersionにprefill_v1を入れる() {
        ollama.reply("{\"answers\":[{\"questionId\":\"since\",\"optionId\":\"today\",\"text\":null}]}", 12, 3);

        var result = interviewer().prefill("今朝から出ません", List.of(SINCE, PARTNER));

        assertThat(request(0).path("messages").get(0).path("content").asString())
                .isEqualTo(Prompts.prefillOf(Skill.INTERVIEWER, "v1"));
        assertThat(userMessage(0))
                .isEqualTo("## 本人が書いた文章\n今朝から出ません\n\n## 質問\n- since: いつから起きていますか\n"
                        + "  - today: 今日から\n  - earlier: もっと前から\n- partner: 対象の取引先はどこですか\n  - （文章で答える）");
        assertThat(result.value().answers()).singleElement().satisfies(a -> {
            assertThat(a.questionId()).isEqualTo("since");
            assertThat(a.optionId()).isEqualTo("today");
            assertThat(a.text()).isNull();
        });
        assertThat(result.usage().promptVersion()).isEqualTo("prefill-v1");
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().model()).isEqualTo("test-model");
    }

    @Test
    void 読み取りの出力は形だけを読み_文章の答えと空の答えも読め_答えがなければ空() {
        ollama.reply(
                        "{\"answers\":[{\"questionId\":\"partner\",\"optionId\":null,\"text\":\" A社 \"},{\"optionId\":\"x\"}]}",
                        1,
                        1)
                .reply("{\"answers\":[]}", 1, 1);

        var withText = interviewer().prefill("A社", List.of(PARTNER)).value();
        var none = interviewer().prefill("A社", List.of(PARTNER)).value();

        // 質問の id のない答えは捨て、文章は前後の空白を除く。選択肢にあるか、文章に含まれるかは、ここでは確かめない
        assertThat(withText.answers()).singleElement().satisfies(a -> {
            assertThat(a.questionId()).isEqualTo("partner");
            assertThat(a.text()).isEqualTo("A社");
        });
        assertThat(none.answers()).isEmpty();
    }

    @Test
    void 読み取りの出力が読めなければ1回だけ求め直し_それでも読めなければAiUnavailableException() {
        ollama.reply("読めません", 5, 1).reply("{\"answers\":[{\"questionId\":\"since\",\"optionId\":\"today\"}]}", 6, 2);

        var retried = interviewer().prefill("今日から", List.of(SINCE));

        assertThat(ollama.requests()).hasSize(2);
        assertThat(retried.value().answers()).hasSize(1);
        assertThat(retried.usage().inputTokens()).isEqualTo(11);

        ollama.reply("{\"noAnswers\":1}", 1, 1).reply("{\"noAnswers\":2}", 1, 1);
        assertThatThrownBy(() -> interviewer().prefill("今日から", List.of(SINCE)))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- (c) JSON の読み取り ----

    @Test
    void そのまま読めるJSONも_コードブロックや前置きつきのJSONも読める() {
        ollama.reply("{\"type\":\"LOOKS_WRONG\",\"systemId\":\"job-admin\",\"functionId\":\"posting\"}", 1, 1)
                .reply("```json\n{\"type\":\"DO_FOR_ME\",\"systemId\":null,\"functionId\":null}\n```", 1, 1)
                .reply("以下のとおりです。\n{\"type\":\"CHANGE_REQUEST\",\"systemId\":\"billing\",\"functionId\":\"invoice\"}\n以上です。", 1, 1);

        var plain = reception().receive("a", SYSTEMS, Placement.NONE).value();
        var fenced = reception().receive("b", SYSTEMS, Placement.NONE).value();
        var prefixed = reception().receive("c", SYSTEMS, Placement.NONE).value();

        assertThat(plain.type()).isEqualTo(InquiryType.LOOKS_WRONG);
        assertThat(plain.systemId()).isEqualTo("job-admin");
        assertThat(plain.functionId()).isEqualTo("posting");
        assertThat(fenced.type()).isEqualTo(InquiryType.DO_FOR_ME);
        assertThat(prefixed.type()).isEqualTo(InquiryType.CHANGE_REQUEST);
        assertThat(prefixed.systemId()).isEqualTo("billing");
        assertThat(ollama.requests()).hasSize(3); // 呼び直しは起きていない
    }

    // ---- (d) 呼び直し ----

    @Test
    void 読めない出力は1回だけ呼び直し2回目が正しければ成功して利用量は合計される() {
        ollama.reply("すみません、分かりません", 100, 10).reply(RECEPTION_OK, 120, 20);

        var result = reception().receive("期限の仕様", SYSTEMS, Placement.NONE);

        assertThat(result.value().type()).isEqualTo(InquiryType.HOW_IT_WORKS);
        assertThat(ollama.requests()).hasSize(2);
        JsonNode retry = request(1).path("messages");
        assertThat(retry.size()).isEqualTo(4);
        assertThat(retry.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(retry.get(2).path("content").asString()).isEqualTo("すみません、分かりません");
        assertThat(retry.get(3).path("role").asString()).isEqualTo("user");
        assertThat(retry.get(3).path("content").asString()).isEqualTo("指定の形の JSON だけを出力してください");
        assertThat(result.usage().inputTokens()).isEqualTo(220);
        assertThat(result.usage().outputTokens()).isEqualTo(30);
    }

    @Test
    void 二度とも読めなければ利用量つきのAiUnavailableExceptionになる() {
        ollama.reply("読めない1", 100, 10).reply("読めない2", 50, 5);

        assertThatThrownBy(() -> reception().receive("期限の仕様", SYSTEMS, Placement.NONE))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                    assertThat(e.usage()).isNotNull();
                    assertThat(e.usage().inputTokens()).isEqualTo(150);
                    assertThat(e.usage().outputTokens()).isEqualTo(15);
                    assertThat(e.usage().model()).isEqualTo("test-model");
                    assertThat(e.usage().mock()).isFalse();
                });
        assertThat(ollama.requests()).hasSize(2); // 呼び直しは1回だけ
    }

    @Test
    void 二度とも読めなかったときは試行ごとの理由と生の出力を持ち生の出力はメッセージに入れない() {
        String invalid = "{\"summary\":\" \",\"goal\":\"NOPE\"}";
        ollama.reply("読めない出力その1", 1, 1).reply(invalid, 1, 1);

        assertThatThrownBy(() -> restater().restate("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja"))
                .isInstanceOfSatisfying(UnreadableOutputException.class, e -> {
                    assertThat(e.reasons()).containsExactly("JSON として読めない", "形が指定と違う");
                    assertThat(e.rawOutputs()).containsExactly("読めない出力その1", invalid);
                    // 詳しい内容には、型に入れられなかった理由が入る。調べるときに要る
                    assertThat(e.details().get(1)).contains("summary が空");
                    // メッセージは画面への応答やログに出ることがある。理由の種類は入れ、モデルの出力は入れない
                    assertThat(e.getMessage()).contains("1回目: JSON として読めない").contains("2回目: 形が指定と違う");
                    assertThat(e.getMessage()).doesNotContain("読めない出力その1").doesNotContain("NOPE");
                });
    }

    @Test
    void 出力が空のときは理由に空であることが残る() {
        ollama.reply("", 1, 0).reply("   ", 1, 0);

        assertThatThrownBy(() -> reception().receive("期限の仕様", SYSTEMS, Placement.NONE))
                .isInstanceOfSatisfying(UnreadableOutputException.class, e -> assertThat(e.reasons())
                        .containsExactly("出力が空", "出力が空"));
    }

    // ---- (e) 型の不変条件 ----

    @Test
    void 種類が不正な値は読み取れなかったものとしてnullにし_呼び直さない() {
        ollama.reply("{\"type\":\"INCIDENT\",\"systemId\":null,\"functionId\":null}", 1, 1)
                .reply("{\"type\":\"how_to\",\"systemId\":null,\"functionId\":null}", 1, 1)
                .reply("{\"type\":null,\"systemId\":\"\",\"functionId\":null}", 1, 1)
                .reply("{}", 1, 1);

        var unknown = reception().receive("x", SYSTEMS, Placement.NONE).value();
        var lowerCase = reception().receive("x", SYSTEMS, Placement.NONE).value();
        var blank = reception().receive("x", SYSTEMS, Placement.NONE).value();
        var empty = reception().receive("x", SYSTEMS, Placement.NONE).value();

        assertThat(unknown.type()).isNull();
        assertThat(lowerCase.type()).isEqualTo(InquiryType.HOW_TO);
        assertThat(blank.systemId()).isNull();
        assertThat(empty.type()).isNull();
        assertThat(ollama.requests()).hasSize(4);
    }

    @Test
    void 言い直しが空の出力は読めない場合と同じ扱いで2回続けば失敗する() {
        String empty = "{\"summary\":\"  \",\"goal\":\"g\"}";
        ollama.reply(empty, 1, 1).reply(empty, 1, 1);

        assertThatThrownBy(() -> restater().restate("x", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja"))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(ollama.requests()).hasSize(2);
    }

    @Test
    void 説明のanswersQuestionはtrueとfalseをそのまま読み_欠けているか真偽値でなければfalseにする() {
        ollama.reply("{\"explanation\":\"答えです。\",\"answersQuestion\":true}", 1, 1)
                .reply("{\"explanation\":\"確認できませんでした。\",\"answersQuestion\":false}", 1, 1)
                .reply("{\"explanation\":\"欠けています。\"}", 1, 1)
                .reply("{\"explanation\":\"文字列です。\",\"answersQuestion\":\"true\"}", 1, 1)
                .reply("{\"explanation\":\"数値です。\",\"answersQuestion\":1}", 1, 1)
                .reply("{\"explanation\":\"nullです。\",\"answersQuestion\":null}", 1, 1);

        var results = new java.util.ArrayList<Boolean>();
        for (int i = 0; i < 6; i++) {
            results.add(explainer().explain(structured(), findings("事実"), "ja").value().answersQuestion());
        }

        assertThat(results).containsExactly(true, false, false, false, false, false);
        assertThat(ollama.requests()).hasSize(6); // 判定の欠けは読めない出力とはせず、呼び直さない
    }

    @Test
    void 説明が空の出力も読めない場合と同じ扱い() {
        ollama.reply("{\"explanation\":\"\"}", 1, 1).reply("{\"explanation\":\"書き直しました。\"}", 1, 1);

        assertThat(explainer().explain(structured(), findings("事実"), "ja").value().text()).isEqualTo("書き直しました。");
    }

    @Test
    void オブジェクトでないJSONは読めない() {
        ollama.reply("[1,2]", 1, 1).reply("[3]", 1, 1);

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, Placement.NONE)).isInstanceOf(AiUnavailableException.class);
    }

    // ---- (f) 信用しない箇所 ----

    @Test
    void 受付は候補にないidをnullにし本人が選んだ置き場所を優先し決まったシステムの機能でなければ機能をnullにする() {
        ollama.reply("{\"type\":\"HOW_TO\",\"systemId\":\"made-up\",\"functionId\":\"posting\"}", 1, 1)
                .reply("{\"type\":\"HOW_TO\",\"systemId\":\"billing\",\"functionId\":\"invoice\"}", 1, 1)
                .reply("{\"type\":\"HOW_TO\",\"systemId\":\"job-admin\",\"functionId\":\"invoice\"}", 1, 1);

        var unknownId = reception().receive("x", SYSTEMS, Placement.NONE).value();
        var selected = reception()
                .receive("x", SYSTEMS, new Placement(InquiryType.DO_FOR_ME, "job-admin", null))
                .value();
        var wrongFunction = reception().receive("x", SYSTEMS, Placement.NONE).value();

        assertThat(unknownId.systemId()).isNull();
        assertThat(unknownId.functionId()).isNull();
        assertThat(selected.type()).isEqualTo(InquiryType.DO_FOR_ME);
        assertThat(selected.systemId()).isEqualTo("job-admin");
        assertThat(selected.functionId()).isNull(); // invoice は billing の機能
        assertThat(wrongFunction.systemId()).isEqualTo("job-admin");
        assertThat(wrongFunction.functionId()).isNull();
    }

    @Test
    void 聞き手は質問を2個に切り詰め空の質問文を捨て選択肢のわからないとその他を除き4個に切り詰める() {
        ollama.reply(
                "{\"questions\":["
                        + "{\"text\":\"\",\"options\":[\"a\"]},"
                        + "{\"text\":\"質問1\",\"options\":[\"一\",\"わからない\",\"二\",\"その他\",\"三\",\"四\",\"五\"]},"
                        + "{\"text\":\"質問2\"},"
                        + "{\"text\":\"質問3\",\"options\":[\"はい\",\"いいえ\"]},"
                        + "{\"text\":\"質問4\",\"options\":[]}]}",
                1,
                1);

        var questions = interviewer().ask("x", InquiryType.HOW_IT_WORKS, null, null, SCOPE, "ja").value().questions();

        assertThat(questions).extracting(FollowUpQuestion::id).containsExactly("q1", "q2");
        assertThat(questions).extracting(FollowUpQuestion::text).containsExactly("質問1", "質問2");
        assertThat(questions.get(0).options()).containsExactly("一", "二", "三", "四");
        assertThat(questions.get(1).options()).isEmpty();
    }

    @Test
    void 聞き手の質問が0個でもよい() {
        ollama.reply("{\"questions\":[]}", 1, 1);

        assertThat(interviewer().ask("x", InquiryType.HOW_IT_WORKS, null, null, SCOPE, "ja").value().questions())
                .isEmpty();
    }

    @Test
    void 言い直しは対象システムが決まっていればその名前を使い緊急度は読まない() {
        ollama.reply(
                "{\"summary\":\"言い直し。\",\"targetSystem\":\"モデルが書いた別の名前\",\"goal\":\"目的\","
                        + "\"whatHappened\":\"起きたこと\",\"expectedResult\":\"\",\"urgency\":\"HIGH\"}",
                1,
                1);

        var forced = restater()
                .restate("x", InquiryType.HOW_IT_WORKS, SYSTEMS.get(0), POSTING, List.of(), List.of(), "ja")
                .value();

        assertThat(forced.targetSystem()).isEqualTo("求人管理画面");
        assertThat(forced.whatHappened()).isEqualTo("起きたこと");
        assertThat(forced.expectedResult()).isNull();
        assertThat(forced.type()).isEqualTo(InquiryType.HOW_IT_WORKS);
    }

    @Test
    void 言い直しは対象システムが未定ならモデルの出力を使い_goalがなければ言い直しの文章で代える() {
        ollama.reply("{\"summary\":\"言い直し。\",\"targetSystem\":\"請求の画面\"}", 1, 1);

        var result = restater().restate("x", InquiryType.LOOKS_WRONG, null, null, List.of(), List.of(), "ja").value();

        assertThat(result.targetSystem()).isEqualTo("請求の画面");
        assertThat(result.goal()).isEqualTo("言い直し。");
    }

    // ---- (g) 通信の失敗 ----

    @Test
    void 接続できない場合はAiUnavailableExceptionになる() {
        ollama.close();

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, Placement.NONE))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("接続");
    }

    @Test
    void 時間切れはAiUnavailableExceptionになる() {
        ollama.slowReply(RECEPTION_OK, 2000);
        var skill = new OllamaReceptionSkill(client(), JSON, properties(false, Duration.ofMillis(300)));

        assertThatThrownBy(() -> skill.receive("x", SYSTEMS, Placement.NONE))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("時間切れ");
    }

    @Test
    void HTTPエラー500はAiUnavailableExceptionになり呼び直さない() {
        ollama.status(500);

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, Placement.NONE))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("500");
        assertThat(ollama.requests()).hasSize(1);
    }

    @Test
    void format未対応の501も通信の失敗として扱う() {
        ollama.status(501);

        assertThatThrownBy(() -> restater().restate("x", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja"))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- (h) 利用量 ----

    @Test
    void トークン数とモデルとプロンプトの版が利用量に入る() {
        ollama.reply(RECEPTION_OK, 321, 45);

        var usage = reception().receive("x", SYSTEMS, Placement.NONE).usage();

        assertThat(usage.inputTokens()).isEqualTo(321);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.model()).isEqualTo("test-model");
        assertThat(usage.promptVersion()).isEqualTo("v2");
        assertThat(usage.mock()).isFalse();
    }

    // ---- 言語（契約書「言語」。日本語のときは、モデルに送る内容を変えない） ----

    private static final String OUTPUT_LANGUAGE =
            Prompts.withOutputLanguage("", "en");

    private String systemMessage(int index) {
        return request(index).path("messages").get(0).path("content").asString();
    }

    @Test
    void 書く言語の指示は共通のファイルの言語名を英語に置き換えて足す() {
        assertThat(OUTPUT_LANGUAGE).startsWith("\n\n## 書く言語");
        assertThat(OUTPUT_LANGUAGE).contains("英語（English）").doesNotContain("{{language}}");
        assertThat(Prompts.withOutputLanguage("本文", "ja")).isEqualTo("本文");
        assertThat(Prompts.withOutputLanguage("本文", null)).isEqualTo("本文");
        assertThat(Prompts.withOutputLanguage("本文", "en")).isEqualTo("本文" + OUTPUT_LANGUAGE);
    }

    @Test
    void 日本語なら聞き手と言い直しと説明に送る指示文は従来と同じで_英語なら末尾に言語の指示だけが増える() {
        ollama.reply("{\"questions\":[]}", 1, 1).reply("{\"questions\":[]}", 1, 1);
        interviewer().ask("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, SCOPE, "ja");
        interviewer().ask("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, SCOPE, "en");
        assertThat(systemMessage(0)).isEqualTo(Prompts.systemOf(Skill.INTERVIEWER, "v2"));
        assertThat(systemMessage(1)).isEqualTo(Prompts.systemOf(Skill.INTERVIEWER, "v2") + OUTPUT_LANGUAGE);
        assertThat(userMessage(1)).isEqualTo(userMessage(0));

        ollama.reply(RESTATE_OK, 1, 1).reply(RESTATE_OK, 1, 1);
        restater().restate("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "ja");
        restater().restate("期限の仕様", InquiryType.HOW_IT_WORKS, null, null, List.of(), List.of(), "en");
        assertThat(systemMessage(2)).isEqualTo(Prompts.systemOf(Skill.RESTATER, "v2"));
        assertThat(systemMessage(3)).isEqualTo(Prompts.systemOf(Skill.RESTATER, "v2") + OUTPUT_LANGUAGE);
        assertThat(userMessage(3)).isEqualTo(userMessage(2));

        ollama.reply("{\"explanation\":\"説明です。\"}", 1, 1).reply("{\"explanation\":\"説明です。\"}", 1, 1);
        explainer().explain(structured(), findings("事実"), "ja");
        explainer().explain(structured(), findings("事実"), "en");
        assertThat(systemMessage(4)).isEqualTo(Prompts.systemOf(Skill.EXPLAINER, "v2"));
        assertThat(systemMessage(5)).isEqualTo(Prompts.systemOf(Skill.EXPLAINER, "v2") + OUTPUT_LANGUAGE);
        assertThat(userMessage(5)).isEqualTo(userMessage(4));
    }

    @Test
    void 受付と答えの読み取りには言語の指示を足さない() {
        ollama.reply(RECEPTION_OK, 1, 1);
        reception().receive("期限の仕様", SYSTEMS, Placement.NONE);
        assertThat(systemMessage(0)).isEqualTo(Prompts.systemOf(Skill.RECEPTION, "v2")).doesNotContain("書く言語");

        ollama.reply("{\"answers\":[]}", 1, 1);
        interviewer().prefill("今朝から出ません", List.of(SINCE));
        assertThat(systemMessage(1)).isEqualTo(Prompts.prefillOf(Skill.INTERVIEWER, "v1")).doesNotContain("書く言語");
    }
}
