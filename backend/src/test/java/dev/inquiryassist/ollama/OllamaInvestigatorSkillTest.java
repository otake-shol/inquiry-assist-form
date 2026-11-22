package dev.inquiryassist.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.skill.Investigation;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import dev.inquiryassist.mock.MockKnowledgeSearch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 調査の Ollama 実装を、テスト内の HTTP サーバーを Ollama の代わりに、見本の資料を一次情報にして確認する。 */
class OllamaInvestigatorSkillTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AccessScope ALL = new AccessScope("u", Set.of("job-board", "faq", "glossary"));
    private static final String SERVICE = "src/main/java/com/example/jobboard/JobPostingService.java";
    private static final String CODE_QUOTE = "return !now.isBefore(posting.expiresAt());";
    private static final String FAQ_QUOTE = "掲載期限を過ぎた求人は応募者向けの一覧に表示されません。";

    private final FakeOllama ollama = new FakeOllama();
    private final KnowledgeSearch knowledge = new MockKnowledgeSearch();
    private final List<String> progress = new ArrayList<>();

    @AfterEach
    void stop() {
        ollama.close();
    }

    private OllamaInvestigatorSkill skill() {
        SkillSettings settings = new SkillSettings("ollama", "test-model", 0, false, Duration.ofSeconds(5), "v1");
        InquiryProperties properties = new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of("investigator", settings),
                new InquiryProperties.Ollama(ollama.baseUrl(), "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("開発用ユーザー", "担当者（開発用）"),
                java.util.Map.of(),
                InquiryProperties.Rules.defaults(),
                List.of());
        return new OllamaInvestigatorSkill(new OllamaClient(ollama.baseUrl(), "30m", 42, JSON), JSON, properties, knowledge);
    }

    private static StructuredInquiry inquiry(InquiryType type) {
        return new StructuredInquiry("掲載期限を過ぎた求人がどうなるかを知りたい。", type, "求人管理画面", "目的", "一覧に出ない", null);
    }

    private AiResult<Investigation> investigate(AccessScope scope) {
        return skill().investigate(inquiry(InquiryType.HOW_IT_WORKS), scope, progress::add, "ja");
    }

    /** 道具の呼び出しを求める応答を積む。calls は「名前, 引数」の並び。 */
    private void replyToolCalls(Object... calls) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (int i = 0; i < calls.length; i += 2) {
            toolCalls.add(Map.of("function", Map.of("name", calls[i], "arguments", calls[i + 1])));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", toolCalls);
        ollama.raw(JSON.writeValueAsString(Map.of("message", message, "prompt_eval_count", 10, "eval_count", 2)));
    }

    private void replySearch(String query) {
        replyToolCalls("search", Map.of("query", query));
    }

    private static String finding(String statement, String repository, String path, String quote) {
        return JSON.writeValueAsString(Map.of(
                "statement", statement, "evidence", List.of(Map.of("repository", repository, "path", path, "quote", quote))));
    }

    private static String result(String... findings) {
        return "{\"findings\":[" + String.join(",", findings) + "],\"notFound\":[\"延長の上限は見つからなかった\"],\"nextChecks\":[\"仕様の持ち主に確認する\"]}";
    }

    private JsonNode request(int index) {
        return JSON.readTree(ollama.requests().get(index));
    }

    private JsonNode lastToolResult(int requestIndex) {
        JsonNode messages = request(requestIndex).path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        assertThat(last.path("role").asString()).isEqualTo("tool");
        return JSON.readTree(last.path("content").asString());
    }

    // ---- 道具を使って調べる ----

    @Test
    void 検索と読み取りの結果から_行番号つきの出典を持つ事実を返し_その出典は照合を通る() {
        replySearch("掲載期限");
        replyToolCalls("read", Map.of("repository", "job-board", "path", SERVICE, "startLine", 9, "endLine", 13));
        ollama.reply(
                result(
                        finding("求人は、掲載期限の時刻ちょうどから期限切れになる", "job-board", SERVICE, CODE_QUOTE),
                        finding("期限切れの求人は応募者向けの一覧に出ない", "faq", "posting-expiry.md", FAQ_QUOTE)),
                30,
                20);

        AiResult<Investigation> result = investigate(ALL);

        Investigation investigation = result.value();
        assertThat(investigation.findings()).hasSize(2);
        Citation code = investigation.findings().get(0).evidence().get(0);
        assertThat(code.sourceType()).isEqualTo(Citation.SourceType.CODE);
        assertThat(code.startLine()).isEqualTo(12);
        assertThat(code.endLine()).isEqualTo(12);
        assertThat(code.revision()).isEqualTo("mock");
        Citation faq = investigation.findings().get(1).evidence().get(0);
        assertThat(faq.sourceType()).isEqualTo(Citation.SourceType.FAQ);
        assertThat(faq.startLine()).isEqualTo(5);
        assertThat(investigation.notFound()).containsExactly("延長の上限は見つからなかった");
        assertThat(investigation.nextChecks()).containsExactly("仕様の持ち主に確認する");

        // 行番号はモデルに書かせていないが、求めた出典は独立した照合を通る
        assertThat(new CitationVerifier(knowledge).verify(List.of(code, faq), ALL))
                .allMatch(CitationVerifier.Result::verified);

        // 利用量は3回の合計
        assertThat(result.usage().inputTokens()).isEqualTo(50);
        assertThat(result.usage().outputTokens()).isEqualTo(24);
        assertThat(result.usage().model()).isEqualTo("test-model");
        assertThat(result.usage().promptVersion()).isEqualTo("v1");
        assertThat(result.usage().mock()).isFalse();
        assertThat(progress).containsExactly("資料を検索しています", "コードを読んでいます");
    }

    @Test
    void リクエストには道具の定義とプロンプトと問いが入り_2回目には道具の呼び出しと結果が足される() {
        replySearch("掲載期限");
        ollama.reply(result(), 1, 1);

        investigate(ALL);

        JsonNode first = request(0);
        assertThat(first.path("tools").size()).isEqualTo(2);
        assertThat(first.path("tools").get(0).path("function").path("name").asString()).isEqualTo("search");
        assertThat(first.path("tools").get(1).path("function").path("name").asString()).isEqualTo("read");
        assertThat(first.has("format")).isFalse();
        assertThat(first.path("options").path("seed").asInt(-1)).isEqualTo(42);
        assertThat(first.path("messages").get(0).path("content").asString())
                .isEqualTo(Prompts.systemOf(Skill.INVESTIGATOR, "v1"));
        String user = first.path("messages").get(1).path("content").asString();
        assertThat(user)
                .contains("## 本人の問い\n掲載期限を過ぎた求人がどうなるかを知りたい。")
                .contains("## 種類\nHOW_IT_WORKS")
                .contains("## 対象のシステム\n求人管理画面")
                .contains("## 起きていること\n一覧に出ない")
                .doesNotContain("期待していた結果");

        JsonNode messages = request(1).path("messages");
        assertThat(messages.size()).isEqualTo(4);
        assertThat(messages.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(2).path("tool_calls").get(0).path("function").path("name").asString())
                .isEqualTo("search");
        assertThat(messages.get(3).path("tool_name").asString()).isEqualTo("search");
        JsonNode hits = lastToolResult(1).path("hits");
        assertThat(hits.size()).isPositive();
        assertThat(hits.get(0).has("repository")).isTrue();
        assertThat(hits.get(0).has("path")).isTrue();
        assertThat(hits.get(0).path("line").isNumber()).isTrue();
    }

    // ---- 出力を信用しない ----

    @Test
    void 調査の間に見ていない引用は1行目を指す出典になり_照合で落ちる() {
        replySearch("掲載期限");
        ollama.reply(result(finding("期限切れの求人は再掲載ボタンで戻せる", "job-board", SERVICE, "republish(posting, now);")), 1, 1);

        Investigation investigation = investigate(ALL).value();

        Citation citation = investigation.findings().get(0).evidence().get(0);
        assertThat(citation.startLine()).isEqualTo(1);
        assertThat(citation.endLine()).isEqualTo(1);
        assertThat(new CitationVerifier(knowledge).verify(List.of(citation), ALL))
                .noneMatch(CitationVerifier.Result::verified);
    }

    @Test
    void 根拠のない事実_引用が短すぎる事実_文が空の事実は捨て_事実は5件までにする() {
        replySearch("掲載期限");
        String good = finding("期限切れの求人は一覧に出ない", "faq", "posting-expiry.md", FAQ_QUOTE);
        ollama.reply(
                result(
                        "{\"statement\":\"根拠のない事実\",\"evidence\":[]}",
                        "{\"statement\":\"根拠の欄がない事実\"}",
                        finding("引用が短い事実", "faq", "posting-expiry.md", "求人は"),
                        finding(" ", "faq", "posting-expiry.md", FAQ_QUOTE),
                        good, good, good, good, good, good),
                1,
                1);

        Investigation investigation = investigate(ALL).value();

        assertThat(investigation.findings()).hasSize(OllamaInvestigatorSkill.MAX_FINDINGS);
        assertThat(investigation.findings()).allMatch(f -> f.statement().equals("期限切れの求人は一覧に出ない"));
    }

    @Test
    void コードブロックの記号つきの出力も読める() {
        replySearch("掲載期限");
        ollama.reply("```json\n" + result(finding("一覧に出ない", "faq", "posting-expiry.md", FAQ_QUOTE)) + "\n```", 1, 1);

        assertThat(investigate(ALL).value().findings()).hasSize(1);
    }

    @Test
    void 読めない出力は1回だけ求め直し_2回続けば理由と生の出力つきで失敗する() {
        replySearch("掲載期限");
        ollama.reply("調べてみます。", 5, 1);
        ollama.reply("{\"notFound\":[]}", 7, 2);

        assertThatThrownBy(() -> investigate(ALL))
                .isInstanceOfSatisfying(UnreadableOutputException.class, e -> {
                    assertThat(e.reasons()).containsExactly("JSON として読めない", "形が指定と違う");
                    assertThat(e.rawOutputs()).containsExactly("調べてみます。", "{\"notFound\":[]}");
                    assertThat(e.getMessage()).doesNotContain("調べてみます");
                    assertThat(e.usage().inputTokens()).isEqualTo(22);
                    assertThat(e.usage().outputTokens()).isEqualTo(5);
                });
        JsonNode messages = request(2).path("messages");
        assertThat(messages.get(messages.size() - 1).path("content").asString()).isEqualTo(JsonSkillCall.RETRY_INSTRUCTION);
    }

    @Test
    void 読めない出力の後に読める出力が返れば成功する() {
        replySearch("掲載期限");
        ollama.reply("調べてみます。", 1, 1);
        ollama.reply(result(finding("一覧に出ない", "faq", "posting-expiry.md", FAQ_QUOTE)), 1, 1);

        assertThat(investigate(ALL).value().findings()).hasSize(1);
    }

    // ---- 資料を調べずに出した結果 ----

    @Test
    void 資料を調べずに出した結果は受け取らず_調べるよう1度だけ求める() {
        ollama.reply(result(finding("締め日は取引先ごとに設定できる", "data", "customer.csv", "No,Name,ClosingDay")), 1, 1);
        replySearch("掲載期限");
        ollama.reply(result(finding("一覧に出ない", "faq", "posting-expiry.md", FAQ_QUOTE)), 1, 1);

        Investigation investigation = investigate(ALL).value();

        assertThat(investigation.findings()).hasSize(1);
        assertThat(investigation.findings().get(0).statement()).isEqualTo("一覧に出ない");
        JsonNode messages = request(1).path("messages");
        assertThat(messages.get(messages.size() - 1).path("content").asString())
                .isEqualTo(OllamaInvestigatorSkill.SEARCH_FIRST_INSTRUCTION);
    }

    @Test
    void 求められても調べなかった場合は_事実をすべて捨てる() {
        String made = result(finding("締め日は取引先ごとに設定できる", "data", "customer.csv", "No,Name,ClosingDay"));
        ollama.reply(made, 1, 1);
        ollama.reply(made, 1, 1);

        Investigation investigation = investigate(ALL).value();

        assertThat(investigation.findings()).isEmpty();
        assertThat(investigation.notFound()).containsExactly("延長の上限は見つからなかった");
        assertThat(progress).isEmpty();
    }

    // ---- 上限 ----

    @Test
    void 回数の上限に達したら_道具を渡さずに結果だけを求める() {
        for (int i = 0; i < OllamaInvestigatorSkill.MAX_ROUNDS; i++) {
            replySearch("語" + i);
        }
        ollama.reply(result(), 1, 1);

        Investigation investigation = investigate(ALL).value();

        assertThat(investigation.findings()).isEmpty();
        assertThat(ollama.requests()).hasSize(OllamaInvestigatorSkill.MAX_ROUNDS + 1);
        JsonNode last = request(OllamaInvestigatorSkill.MAX_ROUNDS);
        assertThat(last.has("tools")).isFalse();
        JsonNode messages = last.path("messages");
        assertThat(messages.get(messages.size() - 1).path("content").asString())
                .isEqualTo(OllamaInvestigatorSkill.LIMIT_INSTRUCTION);
        // 同じ文言の進捗は続けて出さない
        assertThat(progress).containsExactly("資料を検索しています");
    }

    @Test
    void 一度に呼べる数を超えた道具は実行せず_その旨を返す() {
        Object[] calls = new Object[(OllamaInvestigatorSkill.MAX_CALLS_PER_ROUND + 1) * 2];
        for (int i = 0; i <= OllamaInvestigatorSkill.MAX_CALLS_PER_ROUND; i++) {
            calls[i * 2] = "search";
            calls[i * 2 + 1] = Map.of("query", "掲載期限" + i);
        }
        replyToolCalls(calls);
        ollama.reply(result(), 1, 1);

        investigate(ALL);

        JsonNode messages = request(1).path("messages");
        // system, user, assistant に続けて、呼び出しと同じ数の結果が並ぶ
        assertThat(messages.size()).isEqualTo(3 + OllamaInvestigatorSkill.MAX_CALLS_PER_ROUND + 1);
        assertThat(lastToolResult(1).path("error").asString()).contains("つまで");
    }

    @Test
    void 同じ語での検索には_検索済みであることを添える() {
        replySearch("掲載期限");
        replySearch(" 掲載期限 ");
        ollama.reply(result(), 1, 1);

        investigate(ALL);

        assertThat(lastToolResult(1).has("note")).isFalse();
        assertThat(lastToolResult(2).path("note").asString()).contains("検索済み");
    }

    // ---- 道具の失敗と閲覧範囲 ----

    @Test
    void 閲覧範囲の外の資料は検索に出ず_読もうとしても閲覧できないと返る() {
        AccessScope faqOnly = new AccessScope("u", Set.of("faq"));
        replySearch("掲載期限");
        replyToolCalls("read", Map.of("repository", "job-board", "path", SERVICE, "startLine", 1, "endLine", 20));
        ollama.reply(result(finding("期限ちょうどから期限切れ", "job-board", SERVICE, CODE_QUOTE)), 1, 1);

        Investigation investigation = investigate(faqOnly).value();

        for (JsonNode hit : lastToolResult(1).path("hits")) {
            assertThat(hit.path("repository").asString()).isEqualTo("faq");
        }
        assertThat(lastToolResult(2).path("error").asString()).isEqualTo("この資料は閲覧できません");
        assertThat(ollama.requests().get(2)).doesNotContain("isBefore");
        // 読めなかった資料の引用は、見た行にないので照合を通らない
        assertThat(new CitationVerifier(knowledge).verify(investigation.findings().get(0).evidence(), faqOnly))
                .noneMatch(CitationVerifier.Result::verified);
    }

    @Test
    void 存在しないファイル_引数の不足_知らない道具は_失敗させずに道具の結果として伝える() {
        replyToolCalls(
                "read", Map.of("repository", "faq", "path", "no-such.md", "startLine", 1, "endLine", 5),
                "read", Map.of("path", "posting-expiry.md"),
                "search", Map.of("query", " "),
                "delete", Map.of("path", "posting-expiry.md"));
        // どの道具も資料を見られていないので、1度は調べるよう求められる
        ollama.reply(result(), 1, 1);
        ollama.reply(result(), 1, 1);

        investigate(ALL);

        JsonNode messages = request(1).path("messages");
        List<String> errors = new ArrayList<>();
        for (int i = 3; i < messages.size(); i++) {
            errors.add(JSON.readTree(messages.get(i).path("content").asString()).path("error").asString());
        }
        assertThat(errors).hasSize(4).allMatch(e -> !e.isBlank());
        assertThat(errors.get(3)).contains("search と read");
    }

    @Test
    void 行の指定が文字列でも読め_1回に読む行数は上限で切る() {
        replySearch("掲載期限");
        replyToolCalls("read", Map.of("repository", "job-board", "path", SERVICE, "startLine", "10", "endLine", "9999"));
        ollama.reply(result(), 1, 1);

        investigate(ALL);

        JsonNode lines = lastToolResult(2).path("lines");
        assertThat(lines.get(0).path("line").asInt()).isEqualTo(10);
        assertThat(lines.size()).isLessThanOrEqualTo(OllamaInvestigatorSkill.MAX_READ_LINES);
        assertThat(lines.get(2).path("text").asString()).contains(CODE_QUOTE);
    }

    @Test
    void 途中で呼び出しが失敗したら_そこまでの利用量つきで失敗する() {
        replySearch("掲載期限");
        ollama.status(500);

        assertThatThrownBy(() -> investigate(ALL))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                    assertThat(e.usage().inputTokens()).isEqualTo(10);
                    assertThat(e.usage().outputTokens()).isEqualTo(2);
                    assertThat(e.usage().model()).isEqualTo("test-model");
                });
    }

    @Test
    void 調べない種類を渡すと_モデルを呼ばずに拒む() {
        assertThatThrownBy(() -> skill().investigate(inquiry(InquiryType.CHANGE_REQUEST), ALL, progress::add, "ja"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ollama.requests()).isEmpty();
    }

    @Test
    void 英語なら指示文の末尾に言語の指示を足し_進捗も英語で_日本語なら従来のまま() {
        replySearch("掲載期限");
        ollama.reply(result(finding("求人は期限切れになる", "faq", "posting-expiry.md", FAQ_QUOTE)), 30, 20);
        replySearch("掲載期限");
        ollama.reply(result(finding("求人は期限切れになる", "faq", "posting-expiry.md", FAQ_QUOTE)), 30, 20);

        skill().investigate(inquiry(InquiryType.HOW_IT_WORKS), ALL, progress::add, "ja");
        List<String> jaProgress = List.copyOf(progress);
        progress.clear();
        skill().investigate(inquiry(InquiryType.HOW_IT_WORKS), ALL, progress::add, "en");

        String base = Prompts.systemOf(dev.inquiryassist.core.skill.Skill.INVESTIGATOR, "v1");
        String jaSystem = request(0).path("messages").get(0).path("content").asString();
        String enSystem = request(2).path("messages").get(0).path("content").asString();
        assertThat(jaSystem).isEqualTo(base);
        assertThat(enSystem).isEqualTo(Prompts.withOutputLanguage(base, "en")).contains("英語（English）");
        assertThat(request(2).path("messages").get(1).path("content").asString())
                .isEqualTo(request(0).path("messages").get(1).path("content").asString());
        assertThat(jaProgress).containsExactly("資料を検索しています");
        assertThat(progress).containsExactly("Searching the documents");
    }
}
