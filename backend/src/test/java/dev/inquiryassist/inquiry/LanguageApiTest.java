package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 日本語と英語の切り替え（契約書「言語」）を API から確かめる。見る人の言語はリクエストごとに決まり、
 * 問い合わせの {@code language} は本人が状態を変えるリクエストのたびに合わせ直される。
 */
class LanguageApiTest extends ApiSupport {

    private static final String JA = "ja";
    private static final String EN = "en";

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String language) {
        return builder.header("Accept-Language", language);
    }

    private String language(String id) {
        return (String) row(id).get("language");
    }

    private String createAs(String language, String text) throws Exception {
        return text(mvc.perform(as(post("/api/inquiries"), language)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("text", text)))
                .andExpect(status().isCreated()));
    }

    private String viewAs(String language, String id) throws Exception {
        return text(mvc.perform(as(get("/api/inquiries/" + id), language)).andExpect(status().isOk()));
    }

    /** 入口の質問を、その言語で、「わからない」（システムだけ指定）で最後まで進める。 */
    private String finishAs(String language, String id, String systemId) throws Exception {
        String current = viewAs(language, id);
        for (int guard = 0; guard < 20 && JsonPath.read(current, "$.intake.next") != null; guard++) {
            String questionId = JsonPath.read(current, "$.intake.next.questionId");
            String kind = JsonPath.read(current, "$.intake.next.kind");
            String body;
            if ("SYSTEM".equals(kind) && systemId != null) {
                body = json("questionId", questionId, "optionId", systemId);
            } else if ("NOTE".equals(kind)) {
                boolean canSkip = JsonPath.read(current, "$.intake.next.canSkip");
                body = canSkip
                        ? json("questionId", questionId, "skipped", true)
                        : json("questionId", questionId, "text", "Something is happening");
            } else {
                body = json("questionId", questionId, "unknown", true);
            }
            current = text(mvc.perform(as(post("/api/inquiries/" + id + "/intake/answers"), language)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk()));
        }
        return current;
    }

    private String streamAs(String path, String id) throws Exception {
        MvcResult started = mvc.perform(get("/api/inquiries/" + id + "/answer-stream" + path)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(15_000);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        return started.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String doneData(String streamBody) {
        String[] lines = streamBody.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("event:done")) {
                return lines[i + 1].substring("data:".length()).trim();
            }
        }
        throw new IllegalStateException("done がない: " + streamBody);
    }

    // ---- 設定 ----

    @Test
    void 設定は言語ごとの名前と案内文を返し_言語を決められなければ既定の言語() throws Exception {
        mvc.perform(as(get("/api/config"), "en-US"))
                .andExpect(jsonPath("$.languages").value(contains("ja", "en")))
                .andExpect(jsonPath("$.types[0].label").value("How to use something"))
                .andExpect(jsonPath("$.types[0].hint").value("Steps for an operation"))
                .andExpect(jsonPath("$.urgencies[1].label").value("Urgent"))
                .andExpect(jsonPath("$.systems[0].label").value("Job admin"))
                .andExpect(jsonPath("$.systems[0].functions[0].label").value("Posting"))
                .andExpect(jsonPath("$.filedGuidance").value(org.hamcrest.Matchers.startsWith("The support team")));
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.types[0].label").value("使い方を知りたい"))
                .andExpect(jsonPath("$.systems[0].label").value("求人管理画面"))
                .andExpect(jsonPath("$.filedGuidance").value(org.hamcrest.Matchers.startsWith("担当者が内容を確認")));
        // 使えない言語は既定の言語
        mvc.perform(as(get("/api/config"), "fr, de;q=0.5"))
                .andExpect(jsonPath("$.types[0].label").value("使い方を知りたい"));
        // 品質値の大きい言語を選ぶ
        mvc.perform(as(get("/api/config"), "fr, en;q=0.8"))
                .andExpect(jsonPath("$.types[0].label").value("How to use something"));
    }

    @Test
    void 設定のlangクエリはAccept_Languageより優先する() throws Exception {
        mvc.perform(as(get("/api/config?lang=en"), "ja"))
                .andExpect(jsonPath("$.types[0].label").value("How to use something"));
        mvc.perform(as(get("/api/config?lang=ja"), "en"))
                .andExpect(jsonPath("$.types[0].label").value("使い方を知りたい"));
        mvc.perform(as(get("/api/config?lang=zz"), "en"))
                .andExpect(jsonPath("$.types[0].label").value("How to use something"));
    }

    // ---- 同じ問い合わせを2つの言語で見る ----

    @Test
    void 同じ問い合わせを言語ごとに見ると名前と質問文が切り替わり_本人の文章と要約は変わらず_見るだけでは言語を変えない() throws Exception {
        String id = idOf(create("求人管理画面の求人の掲載がおかしい。今日から出ません"));
        assertThat(language(id)).isEqualTo(JA);

        String ja = viewAs(JA, id);
        String en = viewAs(EN, id);

        assertThat((String) JsonPath.read(ja, "$.type.label")).isEqualTo("おかしいかもしれない");
        assertThat((String) JsonPath.read(en, "$.type.label")).isEqualTo("Something looks wrong");
        assertThat((String) JsonPath.read(en, "$.system.label")).isEqualTo("Job admin");
        assertThat((String) JsonPath.read(en, "$.function.label")).isEqualTo("Posting");
        assertThat((String) JsonPath.read(en, "$.intake.entries[0].text")).isEqualTo("What can we help you with?");
        assertThat((String) JsonPath.read(en, "$.intake.entries[0].answer.label")).isEqualTo("Something looks wrong");
        assertThat((String) JsonPath.read(en, "$.intake.entries[3].text")).isEqualTo("Since when has this been happening?");
        assertThat((String) JsonPath.read(en, "$.intake.entries[3].answer.label")).isEqualTo("Since today");
        assertThat((String) JsonPath.read(ja, "$.intake.entries[3].text")).isEqualTo("いつから起きていますか");
        // 本人の文章と、言い直しの要約は、書かれたまま
        assertThat((String) JsonPath.read(en, "$.utterance")).isEqualTo(JsonPath.read(ja, "$.utterance"));
        assertThat((String) JsonPath.read(en, "$.structured.summary")).isEqualTo(JsonPath.read(ja, "$.structured.summary"));
        // 見るだけでは、問い合わせの言語は変わらない
        assertThat(language(id)).isEqualTo(JA);
    }

    @Test
    void 入口の質問と選択肢は見る人の言語で返り_答えの名前も切り替わる() throws Exception {
        String id = idOf(start());

        String en = viewAs(EN, id);
        assertThat((String) JsonPath.read(en, "$.intake.next.text")).isEqualTo("What can we help you with?");
        assertThat((List<String>) JsonPath.read(en, "$.intake.next.options[*].label"))
                .containsExactly(
                        "How to use something",
                        "How something works",
                        "Something looks wrong",
                        "Do something for me",
                        "Change something");
        assertThat((String) JsonPath.read(viewAs(JA, id), "$.intake.next.text")).isEqualTo("何をしたいですか");
        assertThat(language(id)).isEqualTo(JA);
    }

    // ---- 英語で作る ----

    @Test
    void 英語で作って答えると言語はenになり_モックの言い直しと説明は英語で_根拠の引用は日本語のまま() throws Exception {
        String created = createAs(EN, "What is the rule for the posting deadline?");
        String id = idOf(created);
        assertThat(language(id)).isEqualTo(EN);

        String structured = finishAs(EN, id, "job-admin");
        assertThat((String) JsonPath.read(structured, "$.awaiting")).isEqualTo("QUESTION_CONFIRMATION");
        assertThat((String) JsonPath.read(structured, "$.structured.summary"))
                .contains("About Job admin")
                .contains("I want to know how it currently works.")
                .contains("What was written: What is the rule for the posting deadline?");
        assertThat((String) JsonPath.read(structured, "$.structured.targetSystem")).isEqualTo("Job admin");
        // 本人の文章は、書かれたまま
        assertThat((String) JsonPath.read(structured, "$.utterance")).isEqualTo("What is the rule for the posting deadline?");

        mvc.perform(as(put("/api/inquiries/" + id + "/structured"), EN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        String done = doneData(streamAs("", id));

        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).contains("A job posting is treated as expired");
        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).matches("^[\\x00-\\x7F]+$");
        assertThat((List<String>) JsonPath.read(done, "$.answer.memo.findings"))
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s).matches("^[\\x00-\\x7F]+$"));
        assertThat((List<String>) JsonPath.read(done, "$.answer.memo.notFound"))
                .allSatisfy(s -> assertThat(s).matches("^[\\x00-\\x7F]+$"));
        // 根拠の引用は、資料の原文のまま（日本語）
        assertThat((List<String>) JsonPath.read(done, "$.answer.citations[*].quote"))
                .anySatisfy(q -> assertThat(q).matches("(?s).*[\\u3040-\\u30FF\\u4E00-\\u9FFF].*"));
        assertThat(language(id)).isEqualTo(EN);
    }

    @Test
    void 英語の文章でもモックの受付は種類とシステムと機能を決める() throws Exception {
        String created = createAs(EN, "There is a bug in the POSTING of the job admin screen");

        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("LOOKS_WRONG");
        assertThat((String) JsonPath.read(created, "$.system.id")).isEqualTo("job-admin");
        assertThat((String) JsonPath.read(created, "$.function.id")).isEqualTo("posting");
        assertThat((String) JsonPath.read(created, "$.system.label")).isEqualTo("Job admin");
    }

    @Test
    void 英語の選択肢の名前が文章にあれば設定の質問は聞かれず読み取られる() throws Exception {
        String created = createAs(EN, "Bug in Posting of Job admin. It has been like this since today");

        assertThat((List<String>) JsonPath.read(created, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function", "since");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.kind")).isEqualTo("INFERRED");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.optionId")).isEqualTo("today");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.label")).isEqualTo("Since today");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].text")).isEqualTo("Since when has this been happening?");
    }

    // ---- 言語の合わせ直し ----

    @Test
    void 日本語で作った問い合わせを英語で操作すると言語がenに変わり_前の文章は変わらない() throws Exception {
        String created = create("期限の仕様を教えて");
        String id = idOf(created);
        assertThat(language(id)).isEqualTo(JA);
        String before = JsonPath.read(created, "$.utterance");

        mvc.perform(as(put("/api/inquiries/" + id + "/urgency"), EN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("urgency", "HURRY")))
                .andExpect(status().isOk());

        assertThat(language(id)).isEqualTo(EN);
        assertThat((String) JsonPath.read(viewAs(JA, id), "$.utterance")).isEqualTo(before);
        // 日本語で見ても、言語は戻らない（見るだけでは変わらない）
        assertThat(language(id)).isEqualTo(EN);
        // 日本語で操作すると、日本語に戻る
        mvc.perform(as(put("/api/inquiries/" + id + "/urgency"), JA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("urgency", "NORMAL")))
                .andExpect(status().isOk());
        assertThat(language(id)).isEqualTo(JA);
    }

    @Test
    void 担当者の操作と答えのストリームは問い合わせの言語を変えない() throws Exception {
        String id = toAnswering("期限の仕様", "job-admin");
        assertThat(language(id)).isEqualTo(JA);

        // 答えのストリームは、langで見る人の言語を決めても、問い合わせの言語は変えない
        String done = doneData(streamAs("?lang=en", id));
        assertThat((String) JsonPath.read(done, "$.system.label")).isEqualTo("Job admin");
        assertThat((String) JsonPath.read(done, "$.type.label")).isEqualTo("How something works");
        assertThat(language(id)).isEqualTo(JA);

        sendNow(id).andExpect(status().isOk());
        mvc.perform(as(post("/api/operator/inquiries/" + id + "/read"), EN)).andExpect(status().isOk());
        mvc.perform(as(post("/api/operator/inquiries/" + id + "/replies"), EN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "Could you tell us more?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value(JA))
                .andExpect(jsonPath("$.system.label").value("Job admin"));
        assertThat(language(id)).isEqualTo(JA);
    }

    // ---- 一覧と担当者 ----

    @Test
    void 本人の一覧は言語を持ち_文章のない問い合わせの要約は見る人の言語の名前() throws Exception {
        String id = idOf(start());
        mvc.perform(as(post("/api/inquiries/" + id + "/intake/answers"), EN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("questionId", "type", "optionId", "HOW_TO")))
                .andExpect(status().isOk());
        String written = idOf(createAs(EN, "How does the deadline work?"));

        String listEn = text(mvc.perform(as(get("/api/inquiries"), EN)).andExpect(status().isOk()));
        String listJa = text(mvc.perform(as(get("/api/inquiries"), JA)).andExpect(status().isOk()));

        assertThat(((List<Object>) JsonPath.read(listEn, "$.items[?(@.id=='" + id + "')].language")).get(0)).isEqualTo(EN);
        assertThat(((List<Object>) JsonPath.read(listEn, "$.items[?(@.id=='" + id + "')].summary")).get(0))
                .isEqualTo("How to use something");
        assertThat(((List<Object>) JsonPath.read(listJa, "$.items[?(@.id=='" + id + "')].summary")).get(0))
                .isEqualTo("使い方を知りたい");
        // 書かれた文章や言い直しの要約は、見る人の言語で変わらない
        assertThat(((List<Object>) JsonPath.read(listJa, "$.items[?(@.id=='" + written + "')].summary")).get(0))
                .isEqualTo(((List<Object>) JsonPath.read(listEn, "$.items[?(@.id=='" + written + "')].summary")).get(0));
    }

    @Test
    void 担当者の一覧と詳細は問い合わせの言語を持ち_名前は担当者の見る言語() throws Exception {
        String id = idOf(createAs(EN, "How does the deadline work in Billing?"));
        finishAs(EN, id, "billing");
        mvc.perform(as(put("/api/inquiries/" + id + "/structured"), EN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/inquiries/" + id + "/send-now"), EN)).andExpect(status().isOk());

        String listJa = text(mvc.perform(as(get("/api/operator/inquiries"), JA)));
        String listEn = text(mvc.perform(as(get("/api/operator/inquiries"), EN)));
        assertThat(((List<Object>) JsonPath.read(listJa, "$.items[?(@.id=='" + id + "')].language")).get(0)).isEqualTo(EN);
        assertThat(((List<Object>) JsonPath.read(listJa, "$.items[?(@.id=='" + id + "')].system.label")).get(0))
                .isEqualTo("請求管理");
        assertThat(((List<Object>) JsonPath.read(listEn, "$.items[?(@.id=='" + id + "')].system.label")).get(0))
                .isEqualTo("Billing");

        mvc.perform(as(get("/api/operator/inquiries/" + id), JA))
                .andExpect(jsonPath("$.language").value(EN))
                .andExpect(jsonPath("$.system.label").value("請求管理"));
        mvc.perform(as(get("/api/operator/inquiries/" + id), EN))
                .andExpect(jsonPath("$.language").value(EN))
                .andExpect(jsonPath("$.system.label").value("Billing"));
    }

    @Test
    void 担当者のストリームもlangで言語を決める() throws Exception {
        MvcResult started = mvc.perform(get("/api/operator/stream?lang=en").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String id = filedNow("ストリームの言語", "billing");

        assertThat(waitFor(() -> content(started).contains(id), 5000)).isTrue();
        String data = content(started).lines()
                .dropWhile(l -> !l.startsWith("event:filed"))
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring("data:".length())
                .trim();
        assertThat((String) JsonPath.read(data, "$.system.label")).isEqualTo("Billing");
        assertThat((String) JsonPath.read(data, "$.language")).isEqualTo(JA);
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
