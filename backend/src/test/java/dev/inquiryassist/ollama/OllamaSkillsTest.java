package dev.inquiryassist.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
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
    private static final List<SystemOption> SYSTEMS =
            List.of(new SystemOption("job-admin", "求人管理画面"), new SystemOption("billing", "請求管理"));
    private static final AccessScope SCOPE = new AccessScope("u", Set.of("faq"));
    private static final String RECEPTION_OK = "{\"category\":\"SPEC_QUESTION\",\"systemId\":null}";
    private static final String RESTATE_OK =
            "{\"summary\":\"言い直し。\",\"targetSystem\":null,\"goal\":\"目的\",\"whatHappened\":null,"
                    + "\"expectedResult\":null,\"urgency\":\"NORMAL\"}";

    private final FakeOllama ollama = new FakeOllama();

    @AfterEach
    void stop() {
        ollama.close();
    }

    private InquiryProperties properties(boolean think, Duration timeout) {
        SkillSettings settings = new SkillSettings("ollama", "test-model", 0, think, timeout, "v1");
        return new InquiryProperties(
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of(
                        "reception", settings,
                        "interviewer", settings,
                        "restater", settings,
                        "explainer", settings),
                new InquiryProperties.Ollama(ollama.baseUrl(), "30m", 42));
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
        return new StructuredInquiry("本人の問い。", InquiryCategory.SPEC_QUESTION, null, "目的", null, null, null);
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

        reception().receive("期限の仕様", SYSTEMS, null);

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
        assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo(Prompts.systemOf(Skill.RECEPTION, "v1"));
        assertThat(body.path("messages").get(1).path("role").asString()).isEqualTo("user");
    }

    @Test
    void thinkは設定のとおりに送られる() {
        ollama.reply("{\"explanation\":\"説明です。\"}", 1, 1);

        explainer().explain(structured(), findings("事実"));

        assertThat(request(0).path("think").asBoolean(false)).isTrue();
        assertThat(request(0).has("format")).isFalse();
        assertThat(request(0).path("messages").get(0).path("content").asString())
                .isEqualTo(Prompts.systemOf(Skill.EXPLAINER, "v1"));
    }

    // ---- (b) 入力の組み立て ----

    @Test
    void 受付の入力は問い合わせ_システムの候補_本人が選んだシステムの見出しで組み立てる() {
        ollama.reply(RECEPTION_OK, 1, 1).reply(RECEPTION_OK, 1, 1);

        reception().receive("期限の仕様", SYSTEMS, null);
        reception().receive("期限の仕様", SYSTEMS, SYSTEMS.get(1));

        assertThat(userMessage(0))
                .isEqualTo("## 問い合わせ\n期限の仕様\n\n## システムの候補\n- job-admin: 求人管理画面\n- billing: 請求管理\n\n"
                        + "## 本人が選んだシステム\nなし");
        assertThat(userMessage(1)).endsWith("## 本人が選んだシステム\nbilling");
    }

    @Test
    void 聞き手の入力は問い合わせ_種別_対象のシステムの見出しで組み立てる() {
        ollama.reply("{\"questions\":[]}", 1, 1).reply("{\"questions\":[]}", 1, 1);

        interviewer().ask("期限の仕様", InquiryCategory.SUSPECTED_BUG, SYSTEMS.get(0), SCOPE);
        interviewer().ask("期限の仕様", InquiryCategory.SPEC_QUESTION, null, SCOPE);

        assertThat(userMessage(0))
                .isEqualTo("## 問い合わせ\n期限の仕様\n\n## 種別\nSUSPECTED_BUG\n\n## 対象のシステム\n求人管理画面");
        assertThat(userMessage(1)).endsWith("## 対象のシステム\nなし");
    }

    @Test
    void 言い直しの入力は回答と訂正を見出しごとに組み立て訂正は伝えられた順に並ぶ() {
        ollama.reply(RESTATE_OK, 1, 1).reply(RESTATE_OK, 1, 1).reply(RESTATE_OK, 1, 1);
        var answers = List.of(
                new AnsweredQuestion(new FollowUpQuestion("q1", "どの画面ですか", List.of()), "求人管理画面"),
                new AnsweredQuestion(new FollowUpQuestion("q2", "いつからですか", List.of()), null));

        restater().restate("期限の仕様", InquiryCategory.SPEC_QUESTION, answers, SYSTEMS.get(0), List.of("一昨日から", "3件です"));
        restater().restate("期限の仕様", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of());
        restater().restate("期限の仕様", InquiryCategory.SPEC_QUESTION, answers, null, List.of());

        assertThat(userMessage(0))
                .isEqualTo("## 問い合わせ\n期限の仕様\n\n## 種別\nSPEC_QUESTION\n\n## 対象のシステム\n求人管理画面\n\n"
                        + "## 追加の質問と回答\n- どの画面ですか: 求人管理画面\n- いつからですか: （わからない）\n\n"
                        + "## 本人が後から直したこと\n- 一昨日から\n- 3件です");
        // 質問がなければ「（追加の質問なし）」。訂正がなければ見出しごと出さない
        assertThat(userMessage(1))
                .isEqualTo("## 問い合わせ\n期限の仕様\n\n## 種別\nSPEC_QUESTION\n\n## 対象のシステム\nなし\n\n"
                        + "## 追加の質問と回答\n（追加の質問なし）");
        assertThat(userMessage(2)).doesNotContain("本人が後から直したこと");
    }

    @Test
    void 説明の入力は本人の問い_種別_確かめられた事実の見出しで組み立てる() {
        ollama.reply("{\"explanation\":\"説明です。\"}", 1, 1);

        explainer().explain(structured(), findings("事実A", "事実B"));

        assertThat(userMessage(0))
                .isEqualTo("## 本人の問い\n本人の問い。\n\n## 種別\nSPEC_QUESTION\n\n## 確かめられた事実\n- 事実A\n- 事実B");
    }

    // ---- (c) JSON の読み取り ----

    @Test
    void そのまま読めるJSONも_コードブロックや前置きつきのJSONも読める() {
        ollama.reply("{\"category\":\"SUSPECTED_BUG\",\"systemId\":\"job-admin\"}", 1, 1)
                .reply("```json\n{\"category\":\"WORK_REQUEST\",\"systemId\":null}\n```", 1, 1)
                .reply("以下のとおりです。\n{\"category\":\"INCIDENT\",\"systemId\":\"billing\"}\n以上です。", 1, 1);

        var plain = reception().receive("a", SYSTEMS, null).value();
        var fenced = reception().receive("b", SYSTEMS, null).value();
        var prefixed = reception().receive("c", SYSTEMS, null).value();

        assertThat(plain.category()).isEqualTo(InquiryCategory.SUSPECTED_BUG);
        assertThat(plain.guessedSystemId()).isEqualTo("job-admin");
        assertThat(fenced.category()).isEqualTo(InquiryCategory.WORK_REQUEST);
        assertThat(prefixed.category()).isEqualTo(InquiryCategory.INCIDENT);
        assertThat(prefixed.guessedSystemId()).isEqualTo("billing");
        assertThat(ollama.requests()).hasSize(3); // 呼び直しは起きていない
    }

    // ---- (d) 呼び直し ----

    @Test
    void 読めない出力は1回だけ呼び直し2回目が正しければ成功して利用量は合計される() {
        ollama.reply("すみません、分かりません", 100, 10).reply(RECEPTION_OK, 120, 20);

        var result = reception().receive("期限の仕様", SYSTEMS, null);

        assertThat(result.value().category()).isEqualTo(InquiryCategory.SPEC_QUESTION);
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

        assertThatThrownBy(() -> reception().receive("期限の仕様", SYSTEMS, null))
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
        ollama.reply("読めない出力その1", 1, 1).reply("{\"category\":\"NOPE\",\"systemId\":null}", 1, 1);

        assertThatThrownBy(() -> reception().receive("期限の仕様", SYSTEMS, null))
                .isInstanceOfSatisfying(UnreadableOutputException.class, e -> {
                    assertThat(e.reasons()).containsExactly("JSON として読めない", "形が指定と違う");
                    assertThat(e.rawOutputs())
                            .containsExactly("読めない出力その1", "{\"category\":\"NOPE\",\"systemId\":null}");
                    // 詳しい内容には、不正な値（モデルの出力の一部）が入る。調べるときに要る
                    assertThat(e.details().get(1)).contains("NOPE");
                    // メッセージは画面への応答やログに出ることがある。理由の種類は入れ、モデルの出力は
                    // 生の形でも、不正な値の説明を通じても入れない
                    assertThat(e.getMessage()).contains("1回目: JSON として読めない").contains("2回目: 形が指定と違う");
                    assertThat(e.getMessage()).doesNotContain("読めない出力その1").doesNotContain("NOPE");
                });
    }

    @Test
    void 出力が空のときは理由に空であることが残る() {
        ollama.reply("", 1, 0).reply("   ", 1, 0);

        assertThatThrownBy(() -> reception().receive("期限の仕様", SYSTEMS, null))
                .isInstanceOfSatisfying(UnreadableOutputException.class, e -> assertThat(e.reasons())
                        .containsExactly("出力が空", "出力が空"));
    }

    // ---- (e) 型の不変条件 ----

    @Test
    void 種別が不正な出力は読めない場合と同じ扱いで呼び直す() {
        ollama.reply("{\"category\":\"NOPE\",\"systemId\":null}", 1, 1).reply(RECEPTION_OK, 1, 1);

        assertThat(reception().receive("x", SYSTEMS, null).value().category()).isEqualTo(InquiryCategory.SPEC_QUESTION);
        assertThat(ollama.requests()).hasSize(2);
    }

    @Test
    void 言い直しが空の出力は読めない場合と同じ扱いで2回続けば失敗する() {
        String empty = "{\"summary\":\"  \",\"goal\":\"g\"}";
        ollama.reply(empty, 1, 1).reply(empty, 1, 1);

        assertThatThrownBy(() -> restater().restate("x", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of()))
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
            results.add(explainer().explain(structured(), findings("事実")).value().answersQuestion());
        }

        assertThat(results).containsExactly(true, false, false, false, false, false);
        assertThat(ollama.requests()).hasSize(6); // 判定の欠けは読めない出力とはせず、呼び直さない
    }

    @Test
    void 説明が空の出力も読めない場合と同じ扱い() {
        ollama.reply("{\"explanation\":\"\"}", 1, 1).reply("{\"explanation\":\"書き直しました。\"}", 1, 1);

        assertThat(explainer().explain(structured(), findings("事実")).value().text()).isEqualTo("書き直しました。");
    }

    @Test
    void オブジェクトでないJSONは読めない() {
        ollama.reply("[1,2]", 1, 1).reply("{\"category\":null}", 1, 1);

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, null)).isInstanceOf(AiUnavailableException.class);
    }

    // ---- (f) 信用しない箇所 ----

    @Test
    void 受付は候補にないシステムidをnullにし本人が選んだシステムを優先する() {
        ollama.reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":\"made-up\"}", 1, 1)
                .reply("{\"category\":\"SPEC_QUESTION\",\"systemId\":\"billing\"}", 1, 1);

        var unknownId = reception().receive("x", SYSTEMS, null).value();
        var selected = reception().receive("x", SYSTEMS, SYSTEMS.get(0)).value();

        assertThat(unknownId.guessedSystemId()).isNull();
        assertThat(selected.guessedSystemId()).isEqualTo("job-admin");
    }

    @Test
    void 聞き手は質問を3個に切り詰め空の質問文を捨て選択肢のわからないとその他を除き4個に切り詰める() {
        ollama.reply(
                "{\"questions\":["
                        + "{\"text\":\"\",\"options\":[\"a\"]},"
                        + "{\"text\":\"質問1\",\"options\":[\"一\",\"わからない\",\"二\",\"その他\",\"三\",\"四\",\"五\"]},"
                        + "{\"text\":\"質問2\"},"
                        + "{\"text\":\"質問3\",\"options\":[\"はい\",\"いいえ\"]},"
                        + "{\"text\":\"質問4\",\"options\":[]}]}",
                1,
                1);

        var questions = interviewer().ask("x", InquiryCategory.SPEC_QUESTION, null, SCOPE).value().questions();

        assertThat(questions).extracting(FollowUpQuestion::id).containsExactly("q1", "q2", "q3");
        assertThat(questions).extracting(FollowUpQuestion::text).containsExactly("質問1", "質問2", "質問3");
        assertThat(questions.get(0).options()).containsExactly("一", "二", "三", "四");
        assertThat(questions.get(1).options()).isEmpty();
        assertThat(questions.get(2).options()).containsExactly("はい", "いいえ");
    }

    @Test
    void 聞き手の質問が0個でもよい() {
        ollama.reply("{\"questions\":[]}", 1, 1);

        assertThat(interviewer().ask("x", InquiryCategory.SPEC_QUESTION, null, SCOPE).value().questions()).isEmpty();
    }

    @Test
    void 言い直しは対象システムが決まっていればその名前を使い緊急度が不正ならNORMALにする() {
        ollama.reply(
                "{\"summary\":\"言い直し。\",\"targetSystem\":\"モデルが書いた別の名前\",\"goal\":\"目的\","
                        + "\"whatHappened\":\"起きたこと\",\"expectedResult\":\"\",\"urgency\":\"URGENT\"}",
                1,
                1);

        var forced = restater().restate("x", InquiryCategory.SPEC_QUESTION, List.of(), SYSTEMS.get(0), List.of()).value();

        assertThat(forced.targetSystem()).isEqualTo("求人管理画面");
        assertThat(forced.urgency()).isEqualTo(StructuredInquiry.Urgency.NORMAL);
        assertThat(forced.whatHappened()).isEqualTo("起きたこと");
        assertThat(forced.expectedResult()).isNull();
        assertThat(forced.category()).isEqualTo(InquiryCategory.SPEC_QUESTION);
    }

    @Test
    void 言い直しは対象システムが未定ならモデルの出力を使い緊急度は3つのどれかなら使う() {
        ollama.reply(
                "{\"summary\":\"言い直し。\",\"targetSystem\":\"請求の画面\",\"goal\":\"目的\",\"urgency\":\"high\"}", 1, 1);

        var result = restater().restate("x", InquiryCategory.SUSPECTED_BUG, List.of(), null, List.of()).value();

        assertThat(result.targetSystem()).isEqualTo("請求の画面");
        assertThat(result.urgency()).isEqualTo(StructuredInquiry.Urgency.HIGH);
        assertThat(result.goal()).isEqualTo("目的");
    }

    // ---- (g) 通信の失敗 ----

    @Test
    void 接続できない場合はAiUnavailableExceptionになる() {
        ollama.close();

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, null))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("接続");
    }

    @Test
    void 時間切れはAiUnavailableExceptionになる() {
        ollama.slowReply(RECEPTION_OK, 2000);
        var skill = new OllamaReceptionSkill(client(), JSON, properties(false, Duration.ofMillis(300)));

        assertThatThrownBy(() -> skill.receive("x", SYSTEMS, null))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("時間切れ");
    }

    @Test
    void HTTPエラー500はAiUnavailableExceptionになり呼び直さない() {
        ollama.status(500);

        assertThatThrownBy(() -> reception().receive("x", SYSTEMS, null))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("500");
        assertThat(ollama.requests()).hasSize(1);
    }

    @Test
    void format未対応の501も通信の失敗として扱う() {
        ollama.status(501);

        assertThatThrownBy(() -> restater().restate("x", InquiryCategory.SPEC_QUESTION, List.of(), null, List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ---- (h) 利用量 ----

    @Test
    void トークン数とモデルとプロンプトの版が利用量に入る() {
        ollama.reply(RECEPTION_OK, 321, 45);

        var usage = reception().receive("x", SYSTEMS, null).usage();

        assertThat(usage.inputTokens()).isEqualTo(321);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.model()).isEqualTo("test-model");
        assertThat(usage.promptVersion()).isEqualTo("v1");
        assertThat(usage.mock()).isFalse();
    }
}
