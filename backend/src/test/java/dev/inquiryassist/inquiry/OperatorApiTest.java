package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** オペレータ向けの一覧・詳細・既読化・ストリームを確認する。DB は他のテストと共有するため、id で絞って確認する。 */
class OperatorApiTest extends ApiSupport {

    private List<String> listedIds(boolean unreadOnly) throws Exception {
        String body = text(mvc.perform(get("/api/operator/inquiries" + (unreadOnly ? "?unreadOnly=true" : ""))));
        return JsonPath.read(body, "$.items[*].id");
    }

    /** 一覧から id が一致する1件の項目を取り出す。 */
    private static Object field(String body, String id, String path) {
        List<Object> found = JsonPath.read(body, "$.items[?(@.id=='" + id + "')]." + path);
        return found.get(0);
    }

    private int unreadCount() throws Exception {
        return JsonPath.read(text(mvc.perform(get("/api/operator/inquiries"))), "$.unreadCount");
    }

    @Test
    void 担当者に送られた問い合わせだけが一覧に現れる() throws Exception {
        // 自己解決
        String resolved = toAnswering("期限の仕様", null);
        stream(resolved);
        resolve(resolved, true);
        // 障害の案内
        String incident = idOf(create("サイトが止まっていて障害になっている", null));
        answers(incident, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"はい\"}]}");
        // 進行中
        String inProgress = idOf(create("期限の仕様", null));
        // 起票: 未解決、今すぐ送る、不具合の疑い、作業依頼
        String unresolved = toAnswering("期限の仕様", null);
        stream(unresolved);
        resolve(unresolved, false);
        String immediate = idOf(create("期限の仕様", null));
        sendNow(immediate);
        String bug = toAnswering("表示がおかしい", null);
        stream(bug);
        String work = idOf(create("延長をお願いします", null));
        answers(work, EMPTY_ANSWERS);
        confirm(work);

        List<String> ids = listedIds(false);

        assertThat(ids).contains(unresolved, immediate, bug, work);
        assertThat(ids).doesNotContain(resolved, incident, inProgress);
        for (String notFiled : List.of(resolved, incident, inProgress)) {
            mvc.perform(get("/api/operator/inquiries/" + notFiled))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 一覧は新しい順で要約は言い直しの文章_整形前は発話の先頭80文字() throws Exception {
        String first = idOf(create("最初に送る", null));
        sendNow(first);
        String longUtterance = "あ".repeat(120);
        String second = idOf(create(longUtterance, null));
        sendNow(second);
        String third = toAnswering("三番目の仕様", "billing");
        stream(third);
        resolve(third, false);

        String body = text(mvc.perform(get("/api/operator/inquiries")));
        List<String> ids = JsonPath.read(body, "$.items[*].id");

        assertThat(ids.indexOf(third)).isLessThan(ids.indexOf(second));
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first));
        assertThat(field(body, second, "summary")).isEqualTo("あ".repeat(80));
        assertThat(field(body, third, "summary")).isEqualTo("「三番目の仕様」について、いまの仕組みがどうなっているかを知りたい。");
        assertThat(field(body, third, "system.label")).isEqualTo("請求管理");
        assertThat(field(body, second, "sentImmediately")).isEqualTo(true);
        assertThat(field(body, third, "sentImmediately")).isEqualTo(false);
    }

    @Test
    void 未読数と既読化とunreadOnly() throws Exception {
        String id = idOf(create("既読の確認", null));
        int before = unreadCount();
        sendNow(id);

        assertThat(unreadCount()).isEqualTo(before + 1);
        assertThat(listedIds(true)).contains(id);
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.read").value(false));

        mvc.perform(post("/api/operator/inquiries/" + id + "/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        assertThat(unreadCount()).isEqualTo(before);
        assertThat(listedIds(true)).doesNotContain(id);
        assertThat(listedIds(false)).contains(id);
        java.time.Instant firstRead = jdbc.queryForObject(
                "select read_at from inquiries where id = ?::uuid", java.time.OffsetDateTime.class, id).toInstant();
        // 2回目の既読化で既読の時刻は変わらない
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                                "select read_at from inquiries where id = ?::uuid", java.time.OffsetDateTime.class, id)
                        .toInstant())
                .isEqualTo(firstRead);
        mvc.perform(post("/api/operator/inquiries/00000000-0000-0000-0000-000000000000/read"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 詳細は全内容を返し照合に落ちた出典もverifiedつきで見える() throws Exception {
        String id = toAnswering("[幽霊出典] 期限の仕様", "job-admin");
        mvc.perform(post("/api/inquiries/" + id + "/revision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"一言\"}")).andExpect(status().isConflict()); // ANSWERING では作り直せない
        stream(id);
        resolve(id, false);

        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.askerId").value("local-user"))
                .andExpect(jsonPath("$.askerName").value("開発用ユーザー"))
                .andExpect(jsonPath("$.utterance").value("[幽霊出典] 期限の仕様"))
                .andExpect(jsonPath("$.system.id").value("job-admin"))
                .andExpect(jsonPath("$.systemGuessed").value(false))
                .andExpect(jsonPath("$.filedAt").isNotEmpty())
                .andExpect(jsonPath("$.followUps[0].text").value("求人の状態はどれですか"))
                .andExpect(jsonPath("$.structured.summary").isNotEmpty())
                .andExpect(jsonPath("$.revisionNotes").isEmpty())
                .andExpect(jsonPath("$.answer.grounded").value(false))
                .andExpect(jsonPath("$.answer.memo.findings").isEmpty())
                .andExpect(jsonPath("$.answer.memo.unconfirmed.length()").value(2))
                .andExpect(jsonPath("$.answer.citations[0].verified").value(false))
                .andExpect(jsonPath("$.answer.citations[0].path").value(org.hamcrest.Matchers.containsString("GhostService")))
                .andExpect(jsonPath("$.slackStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.slackNotified").doesNotExist());
    }

    @Test
    void 詳細には照合できた出典がverifiedつきで残り一言も見える() throws Exception {
        String id = idOf(create("期限の仕様", null));
        answers(id, EMPTY_ANSWERS);
        mvc.perform(post("/api/inquiries/" + id + "/revision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"消えたのは一昨日から\"}"));
        confirm(id);
        stream(id);
        resolve(id, false);

        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.revisionNotes[0]").value("消えたのは一昨日から"))
                .andExpect(jsonPath("$.answer.grounded").value(true))
                .andExpect(jsonPath("$.answer.citations.length()").value(2))
                .andExpect(jsonPath("$.answer.citations[*].verified").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    void ストリームに送付のfiledが流れ接続維持のコメント行も流れる() throws Exception {
        MvcResult started = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String id = idOf(create("ストリームの確認", "billing"));

        sendNow(id);

        assertThat(waitFor(() -> content(started).contains("event:filed"), 5000)).isTrue();
        String content = content(started);
        String data = content.lines()
                .dropWhile(l -> !l.startsWith("event:filed"))
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring("data:".length())
                .trim();
        assertThat((String) JsonPath.read(data, "$.id")).isEqualTo(id);
        assertThat((String) JsonPath.read(data, "$.system.id")).isEqualTo("billing");
        assertThat((Boolean) JsonPath.read(data, "$.sentImmediately")).isTrue();
        assertThat((Boolean) JsonPath.read(data, "$.read")).isFalse();
        assertThat(waitFor(() -> content(started).contains(":keepalive"), 5000)).isTrue();
    }

    @Test
    void 自己解決や障害の案内ではストリームに流れない() throws Exception {
        MvcResult started = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String incident = idOf(create("サイトが止まっていて障害になっている", null));
        answers(incident, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"はい\"}]}");
        String resolved = toAnswering("流れない確認", null);
        stream(resolved);
        resolve(resolved, true);
        String filed = idOf(create("流れる確認", null));
        sendNow(filed);

        assertThat(waitFor(() -> content(started).contains(filed), 5000)).isTrue();
        assertThat(content(started)).doesNotContain(incident).doesNotContain(resolved);
    }

    @Test
    void Slackが未設定なら投稿せず失敗でもなく記録だけが残る() throws Exception {
        String id = idOf(create("Slack未設定", "job-admin"));

        sendNow(id).andExpect(jsonPath("$.outcome").value("FILED"));

        var row = jdbc.queryForMap("select slack_notified, slack_error from inquiries where id = ?::uuid", id);
        assertThat(row.get("slack_notified")).isEqualTo(false);
        assertThat(row.get("slack_error")).isNull();
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.slackStatus").value("SKIPPED"));
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
