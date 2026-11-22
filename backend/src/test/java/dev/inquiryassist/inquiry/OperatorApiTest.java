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
        // 進行中
        String inProgress = idOf(create("期限の仕様"));
        // 担当者に送った: 未解決、答えを待たずに送る、おかしいかもしれない、変えてほしい
        String unresolved = toAnswering("期限の仕様", null);
        stream(unresolved);
        resolve(unresolved, false);
        String immediate = filedNow("期限の仕様", null);
        String bug = toAnswering("表示がおかしい", null);
        stream(bug);
        String change = toStructured("画面を変えてほしい", null);
        confirm(change);

        List<String> ids = listedIds(false);

        assertThat(ids).contains(unresolved, immediate, bug, change);
        assertThat(ids).doesNotContain(resolved, inProgress);
        for (String notFiled : List.of(resolved, inProgress)) {
            mvc.perform(get("/api/operator/inquiries/" + notFiled))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 一覧は新しい順で要約は言い直しの文章_言い直しがなければ本人が書いた文章の先頭80文字() throws Exception {
        String first = filedNow("最初に送る", null);
        // 業務が止まっているときは、言い直しに失敗しても組み立てなしで担当者に送られる
        String longText = "[言い直しエラー]" + "あ".repeat(120);
        String second = idOf(create(longText, "BLOCKED"));
        finishIntake(second, null);
        String third = filedNow("三番目の仕様", "billing");
        // 時刻が同じになって順序が揺れないようにする
        stream(third);

        String body = text(mvc.perform(get("/api/operator/inquiries")));
        List<String> ids = JsonPath.read(body, "$.items[*].id");

        // 第二の問い合わせは緊急度が BLOCKED のため、先頭に寄る
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(third));
        assertThat(ids.indexOf(third)).isLessThan(ids.indexOf(first));
        assertThat(field(body, second, "summary")).isEqualTo(longText.substring(0, 80));
        assertThat(field(body, third, "summary"))
                .isEqualTo("請求管理について、いまの仕組みがどうなっているかを知りたい。 書かれた内容: 三番目の仕様");
        assertThat(field(body, third, "system.label")).isEqualTo("請求管理");
        assertThat(field(body, second, "sentImmediately")).isEqualTo(true);
        assertThat(field(body, third, "sentImmediately")).isEqualTo(true);
    }

    @Test
    void 一覧の項目は種類_システム_機能_緊急度を持ち_決まっていなければnull() throws Exception {
        String full = idOf(create("求人管理画面の求人の掲載がおかしい", "HURRY"));
        finishIntake(full, null);
        confirm(full);
        stream(full);
        String bare = filedNow("期限の仕様", null);

        String body = text(mvc.perform(get("/api/operator/inquiries")));

        assertThat(field(body, full, "type.id")).isEqualTo("LOOKS_WRONG");
        assertThat(field(body, full, "type.label")).isEqualTo("おかしいかもしれない");
        assertThat(field(body, full, "system.id")).isEqualTo("job-admin");
        assertThat(field(body, full, "function.id")).isEqualTo("posting");
        assertThat(field(body, full, "function.label")).isEqualTo("求人の掲載");
        assertThat(field(body, full, "urgency")).isEqualTo("HURRY");
        assertThat(field(body, bare, "urgency")).isEqualTo("NORMAL");
        assertThat(field(body, bare, "system")).isNull();
        assertThat(field(body, bare, "function")).isNull();
        // 廃止した項目
        assertThat(body).doesNotContain("\"counts\"").doesNotContain("\"category\"");
    }

    @Test
    void 業務が止まっている件が先頭に寄り_それぞれの中は新しい順() throws Exception {
        String oldNormal = filedNow("古い通常", null);
        String blockedOld = idOf(create("止まっている1", "BLOCKED"));
        finishIntake(blockedOld, null);
        String newNormal = filedNow("新しい通常", null);
        String blockedNew = idOf(create("止まっている2", "BLOCKED"));
        finishIntake(blockedNew, null);

        List<String> ids = JsonPath.read(text(mvc.perform(get("/api/operator/inquiries"))), "$.items[*].id");

        assertThat(ids.indexOf(blockedNew)).isLessThan(ids.indexOf(blockedOld));
        assertThat(ids.indexOf(blockedOld)).isLessThan(ids.indexOf(newNormal));
        assertThat(ids.indexOf(newNormal)).isLessThan(ids.indexOf(oldNormal));
        // 対応状況の絞り込みと件数は廃止した。status を付けても絞らない
        String filtered = text(mvc.perform(get("/api/operator/inquiries?status=DONE")));
        assertThat((List<String>) JsonPath.read(filtered, "$.items[*].id")).contains(oldNormal, newNormal);
    }

    @Test
    void 未読数と既読化とunreadOnly() throws Exception {
        String id = toAnswering("既読の確認", null);
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
                .andExpect(jsonPath("$.urgency").value("NORMAL"))
                .andExpect(jsonPath("$.type.id").value("HOW_IT_WORKS"))
                .andExpect(jsonPath("$.type.label").value("仕組みを知りたい"))
                .andExpect(jsonPath("$.type.by").value("AI"))
                .andExpect(jsonPath("$.system.id").value("job-admin"))
                .andExpect(jsonPath("$.system.by").value("ASKER"))
                .andExpect(jsonPath("$.function").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.filedAt").isNotEmpty())
                .andExpect(jsonPath("$.intake[0].questionId").value("type"))
                .andExpect(jsonPath("$.intake[0].answer.kind").value("INFERRED"))
                .andExpect(jsonPath("$.intake[1].answer.kind").value("OPTION"))
                .andExpect(jsonPath("$.followUps").doesNotExist())
                .andExpect(jsonPath("$.systemGuessed").doesNotExist())
                .andExpect(jsonPath("$.structured.summary").isNotEmpty())
                .andExpect(jsonPath("$.structured.urgency").doesNotExist())
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
        String id = toStructured("期限の仕様", null);
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
        String id = filedNow("ストリームの確認", "billing");

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
    void 自己解決ではストリームに流れない() throws Exception {
        MvcResult started = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String resolved = toAnswering("流れない確認", null);
        stream(resolved);
        resolve(resolved, true);
        String filed = filedNow("流れる確認", null);

        assertThat(waitFor(() -> content(started).contains(filed), 5000)).isTrue();
        assertThat(content(started)).doesNotContain(resolved);
    }

    @Test
    void Slackが未設定なら投稿せず失敗でもなく記録だけが残る() throws Exception {
        String id = filedNow("Slack未設定", "job-admin");

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
