package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 送った後のやり取り（往復）を確認する。DB は他のテストと共有するため、件数は前後の差、一覧は id で絞って確かめる。
 */
class RoundTripApiTest extends ApiSupport {

    /** 作って今すぐ担当者に送り、id を返す。 */
    private String filed(String utterance) throws Exception {
        String id = idOf(create(utterance, null));
        sendNow(id).andExpect(status().isOk());
        return id;
    }

    private ResultActions reply(String id, String body) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + json(body) + "}"));
    }

    private ResultActions append(String id, String body) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + json(body) + "}"));
    }

    private ResultActions setStatus(String id, String status) throws Exception {
        return mvc.perform(put("/api/operator/inquiries/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String myList() throws Exception {
        return text(mvc.perform(get("/api/inquiries")).andExpect(status().isOk()));
    }

    private int unreadReplyCount() throws Exception {
        return JsonPath.read(myList(), "$.unreadReplyCount");
    }

    /** 本人向けの一覧から、id が一致する1件の項目を取り出す。 */
    private Object listed(String id, String path) throws Exception {
        List<Object> found = JsonPath.read(myList(), "$.items[?(@.id=='" + id + "')]." + path);
        return found.get(0);
    }

    private String operatorList(String query) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries" + query)).andExpect(status().isOk()));
    }

    private List<String> operatorIds(String query) throws Exception {
        return JsonPath.read(operatorList(query), "$.items[*].id");
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult subscribe() throws Exception {
        return mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** ストリームから、指定した event の data のうち id が一致する最初のものを返す。なければ null。 */
    private static String eventData(String stream, String event, String id) {
        List<String> lines = stream.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("event:" + event) && i + 1 < lines.size() && lines.get(i + 1).startsWith("data:")) {
                String data = lines.get(i + 1).substring("data:".length()).trim();
                if (id.equals(JsonPath.read(data, "$.id"))) {
                    return data;
                }
            }
        }
        return null;
    }

    // ---- 対応状況 ----

    @Test
    void 担当者に送った問い合わせはOPEN_送っていない問い合わせはnull() throws Exception {
        String notFiled = idOf(create("期限の仕様", null));
        String id = filed("期限の仕様");

        mvc.perform(get("/api/inquiries/" + notFiled)).andExpect(jsonPath("$.handlingStatus").value(nullValue()));
        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.handlingStatus").value("OPEN"))
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.handlingStatus").value("OPEN"));
        assertThat(listed(notFiled, "handlingStatus")).isNull();
        assertThat(listed(id, "handlingStatus")).isEqualTo("OPEN");
    }

    @Test
    void 担当者の返信でOPENはIN_PROGRESSになり_本人の取得で未読が消える() throws Exception {
        String id = filed("返信の確認");
        int before = unreadReplyCount();

        reply(id, "  確認します  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].from").value("OPERATOR"))
                .andExpect(jsonPath("$.messages[0].authorName").value("担当者（開発用）"))
                .andExpect(jsonPath("$.messages[0].body").value("確認します"))
                .andExpect(jsonPath("$.messages[0].id").isNotEmpty())
                .andExpect(jsonPath("$.messages[0].createdAt").isNotEmpty());

        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        assertThat(listed(id, "messageCount")).isEqualTo(1);
        assertThat(listed(id, "handlingStatus")).isEqualTo("IN_PROGRESS");
        assertThat(unreadReplyCount()).isEqualTo(before + 1);

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.messages[0].body").value("確認します"));

        assertThat(listed(id, "unreadReply")).isEqualTo(false);
        assertThat(unreadReplyCount()).isEqualTo(before);
    }

    @Test
    void 返信は対応中と完了の状況を変えない() throws Exception {
        String inProgress = filed("対応中への返信");
        setStatus(inProgress, "IN_PROGRESS").andExpect(status().isOk());
        String done = filed("完了への返信");
        setStatus(done, "DONE").andExpect(status().isOk());

        reply(inProgress, "追って連絡します").andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"));
        reply(done, "補足です").andExpect(jsonPath("$.handlingStatus").value("DONE"));
    }

    @Test
    void 本人の追記で担当者側の既読が外れ_DONEはIN_PROGRESSに戻り_ストリームにupdatedが流れる() throws Exception {
        String id = filed("追記の確認");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(jsonPath("$.read").value(true));
        setStatus(id, "DONE").andExpect(jsonPath("$.handlingStatus").value("DONE"));
        MvcResult started = subscribe();

        append(id, "  まだ直っていません  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.messages[0].from").value("ASKER"))
                .andExpect(jsonPath("$.messages[0].authorName").value("開発用ユーザー"))
                .andExpect(jsonPath("$.messages[0].body").value("まだ直っていません"));

        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.messages[0].from").value("ASKER"));
        assertThat(waitFor(() -> eventData(content(started), "updated", id) != null, 5000)).isTrue();
        String data = eventData(content(started), "updated", id);
        assertThat((Boolean) JsonPath.read(data, "$.read")).isFalse();
        assertThat((String) JsonPath.read(data, "$.handlingStatus")).isEqualTo("IN_PROGRESS");
        assertThat((Integer) JsonPath.read(data, "$.messageCount")).isEqualTo(1);
        // 追記は本人の未読を増やさない
        assertThat(listed(id, "unreadReply")).isEqualTo(false);
    }

    @Test
    void 担当者の返信と対応状況の変更もupdatedで流れ_filedとは別の種類になる() throws Exception {
        MvcResult started = subscribe();
        String id = filed("更新の通知");
        assertThat(waitFor(() -> eventData(content(started), "filed", id) != null, 5000)).isTrue();

        setStatus(id, "IN_PROGRESS").andExpect(status().isOk());
        assertThat(waitFor(() -> eventData(content(started), "updated", id) != null, 5000)).isTrue();
        reply(id, "返信です").andExpect(status().isOk());

        assertThat(waitFor(() -> content(started).lines().filter(l -> l.equals("event:updated")).count() >= 2, 5000))
                .isTrue();
        assertThat(eventData(content(started), "updated", id)).isNotNull();
    }

    @Test
    void 担当者に送られていない問い合わせへの追記はINVALID_STATE() throws Exception {
        String inProgress = idOf(create("期限の仕様", null));
        String resolved = toAnswering("期限の仕様", null);
        stream(resolved);
        resolve(resolved, true);

        for (String id : List.of(inProgress, resolved)) {
            append(id, "追記").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
    }

    @Test
    void 追記と返信の本文は前後の空白を除いて1から4000文字() throws Exception {
        String id = filed("本文の検証");

        for (String bad : List.of("", "   ", "\n\t ", "あ".repeat(4001), " " + "あ".repeat(4001))) {
            append(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
            reply(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        mvc.perform(post("/api/inquiries/" + id + "/messages").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 検証に落ちた入力は何も残さない
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.messages").isEmpty());

        append(id, "あ".repeat(4000)).andExpect(status().isOk());
        append(id, " 空白を除けば4000文字 " + "い".repeat(3984) + "   ").andExpect(status().isOk());
    }

    @Test
    void メッセージは古い順に1本の時系列で返る() throws Exception {
        String id = filed("時系列");

        reply(id, "1つ目");
        append(id, "2つ目");
        reply(id, "3つ目");

        String view = text(mvc.perform(get("/api/inquiries/" + id)));
        assertThat((List<String>) JsonPath.read(view, "$.messages[*].body")).containsExactly("1つ目", "2つ目", "3つ目");
        assertThat((List<String>) JsonPath.read(view, "$.messages[*].from"))
                .containsExactly("OPERATOR", "ASKER", "OPERATOR");
        String detail = text(mvc.perform(get("/api/operator/inquiries/" + id)));
        assertThat((List<String>) JsonPath.read(detail, "$.messages[*].body")).containsExactly("1つ目", "2つ目", "3つ目");
    }

    // ---- 担当者向けの対応状況 ----

    @Test
    void 対応状況を変えられ_一覧の絞り込みとcountsが合う() throws Exception {
        String counts = "$.counts.";
        String base = operatorList("");
        int open0 = JsonPath.read(base, counts + "OPEN");
        int progress0 = JsonPath.read(base, counts + "IN_PROGRESS");
        int done0 = JsonPath.read(base, counts + "DONE");
        String a = filed("状況A");
        String b = filed("状況B");
        String c = filed("状況C");

        setStatus(a, "IN_PROGRESS").andExpect(status().isOk()).andExpect(jsonPath("$.handlingStatus").value("IN_PROGRESS"));
        setStatus(b, "DONE").andExpect(jsonPath("$.handlingStatus").value("DONE"));
        mvc.perform(post("/api/operator/inquiries/" + c + "/read")).andExpect(status().isOk());

        String all = operatorList("");
        assertThat((Integer) JsonPath.read(all, counts + "OPEN")).isEqualTo(open0 + 1);
        assertThat((Integer) JsonPath.read(all, counts + "IN_PROGRESS")).isEqualTo(progress0 + 1);
        assertThat((Integer) JsonPath.read(all, counts + "DONE")).isEqualTo(done0 + 1);
        assertThat(operatorIds("?status=OPEN")).contains(c).doesNotContain(a, b);
        assertThat(operatorIds("?status=IN_PROGRESS")).contains(a).doesNotContain(b, c);
        assertThat(operatorIds("?status=DONE")).contains(b).doesNotContain(a, c);
        // 絞り込みでも件数は全体のまま。未読との併用もできる
        assertThat((Integer) JsonPath.read(operatorList("?status=DONE"), counts + "OPEN")).isEqualTo(open0 + 1);
        assertThat(operatorIds("?status=OPEN&unreadOnly=true")).doesNotContain(a, b, c);
        assertThat(operatorIds("?status=IN_PROGRESS&unreadOnly=true")).contains(a);
        assertThat(JsonPath.<Object>read(all, "$.items[?(@.id=='" + a + "')].handlingStatus"))
                .isEqualTo(List.of("IN_PROGRESS"));
        assertThat(JsonPath.<Object>read(all, "$.items[?(@.id=='" + a + "')].messageCount")).isEqualTo(List.of(0));

        setStatus(b, "OPEN").andExpect(jsonPath("$.handlingStatus").value("OPEN"));
        assertThat(operatorIds("?status=OPEN")).contains(b, c);
    }

    @Test
    void 対応状況の値が不正なら400_送られていない問い合わせは404() throws Exception {
        String id = filed("状況の検証");
        String notFiled = idOf(create("期限の仕様", null));

        setStatus(id, "ARCHIVED").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(put("/api/operator/inquiries/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(get("/api/operator/inquiries?status=ARCHIVED")).andExpect(status().isBadRequest());
        setStatus(notFiled, "DONE").andExpect(status().isNotFound());
        reply(notFiled, "返信").andExpect(status().isNotFound());
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.handlingStatus").value("OPEN"));
    }

    // ---- 本人の一覧と他人の問い合わせ ----

    @Test
    void 一覧は自分の問い合わせだけを新しい順に返す() throws Exception {
        String first = idOf(create("一覧の1件目", null));
        String second = filed("一覧の2件目");
        String third = idOf(create("一覧の3件目", null));
        jdbc.update("update inquiries set asker_id = 'someone-else' where id = ?::uuid", second);

        String body = myList();
        List<String> ids = JsonPath.read(body, "$.items[*].id");

        assertThat(ids).startsWith(third, first).doesNotContain(second);
        assertThat(ids.size()).isLessThanOrEqualTo(50);
        assertThat(listed(third, "step")).isEqualTo("TRIAGED");
        assertThat(listed(third, "outcome")).isEqualTo("IN_PROGRESS");
        assertThat(listed(third, "summary")).isEqualTo("一覧の3件目");
        assertThat(listed(third, "createdAt")).isNotNull();
        assertThat(listed(third, "unreadReply")).isEqualTo(false);
        assertThat(listed(third, "messageCount")).isEqualTo(0);
    }

    @Test
    void 他人の問い合わせは取得も本人向けのすべての操作もNOT_FOUND() throws Exception {
        String id = filed("他人の問い合わせ");
        reply(id, "返信").andExpect(status().isOk());
        jdbc.update("update inquiries set asker_id = 'someone-else' where id = ?::uuid", id);
        String json = "application/json";

        mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/inquiries/" + id + "/category").contentType(json).content("{\"category\":\"WORK_REQUEST\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/inquiries/" + id + "/system").contentType(json).content("{\"systemId\":null}"))
                .andExpect(status().isNotFound());
        answers(id, EMPTY_ANSWERS).andExpect(status().isNotFound());
        confirm(id).andExpect(status().isNotFound());
        mvc.perform(post("/api/inquiries/" + id + "/revision").contentType(json).content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/inquiries/" + id + "/reopen")).andExpect(status().isNotFound());
        mvc.perform(get("/api/inquiries/" + id + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        resolve(id, true).andExpect(status().isNotFound());
        sendNow(id).andExpect(status().isNotFound());
        append(id, "追記").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 取得できないので、返信の未読も消えない。担当者側の API は影響を受けない
        assertThat(row(id).get("asker_unread_reply")).isEqualTo(true);
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk());
    }

    // ---- 送信した回答 ----

    @Test
    void submittedAnswersは送信前null_送信後は回答_5bの後はnull() throws Exception {
        String id = idOf(create("期限の仕様", null));
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.submittedAnswers").value(nullValue()));

        String sent = text(answers(
                        id,
                        "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"求人管理画面\"},{\"questionId\":\"q2\",\"answer\":null}]}")
                .andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(sent, "$.submittedAnswers[*].questionId")).containsExactly("q1", "q2");
        assertThat((String) JsonPath.read(sent, "$.submittedAnswers[0].answer")).isEqualTo("求人管理画面");
        assertThat((Object) JsonPath.read(sent, "$.submittedAnswers[1].answer")).isNull();
        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.submittedAnswers[0].answer").value("求人管理画面"))
                .andExpect(jsonPath("$.submittedAnswers[1].answer").value(nullValue()));

        mvc.perform(post("/api/inquiries/" + id + "/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("TRIAGED"))
                .andExpect(jsonPath("$.submittedAnswers").value(nullValue()));
    }

    @Test
    void 答えずに送った問い合わせのsubmittedAnswersはnullで_担当者向けの未回答の判定は変わらない() throws Exception {
        String id = filed("答える前に送る");

        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.submittedAnswers").value(nullValue()));
        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.followUps[0].status").value("NOT_ANSWERED"));
    }
}
