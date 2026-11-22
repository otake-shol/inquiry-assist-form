package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.lifecycle.BusinessDays;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 返事がないときの知らせと回答済み（契約書「返事がないとき」）を API から確かめる。判定は {@code run(now)} を直接呼ぶ
 * （テストの設定では定期的な実行を止めている）。DB は他のテストと共有するため、各テストは自分の件の起点を過去の日付に置き、
 * その直後の時刻で判定することで、他のテストの件を巻き込まない。
 */
class NoReplyApiTest extends ApiSupport {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** 月曜 12:00（東京）。ほかのテストの件の起点（実行時刻）より十分に前。 */
    private static final Instant ORIGIN = Instant.parse("2026-01-05T03:00:00Z");

    @Autowired
    TimeRuleService rules;

    @BeforeEach
    @AfterEach
    void releaseBackdated() {
        // 過去に置いた起点を残すと、あとの判定に巻き込まれる。結果を確かめている件の起点を、いまに戻す
        jdbc.update(
                "update inquiries set awaiting_since = now(), reminded_at = null"
                        + " where awaiting = 'RESULT_CONFIRMATION' and awaiting_since < '2026-06-01'");
    }

    private void backdate(String id, Instant at) {
        jdbc.update("update inquiries set awaiting_since = ? where id = ?::uuid", Timestamp.from(at), id);
    }

    private Instant remindAfter(Instant since) {
        return BusinessDays.plus(since, 5, TOKYO);
    }

    private String answeredByAi() throws Exception {
        String id = toAnswering("期限の仕様", "job-admin");
        stream(id);
        assertThat((String) JsonPath.read(view(id), "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
        return id;
    }

    private String operatorResult() throws Exception {
        String id = filedNow("期限の仕様", "job-admin");
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT", "body", "結果です")))
                .andExpect(status().isOk());
        return id;
    }

    private String operatorDetail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private Object listed(String id, String field) throws Exception {
        String body = text(mvc.perform(get("/api/inquiries")).andExpect(status().isOk()));
        return ((List<Object>) JsonPath.read(body, "$.items[?(@.id=='" + id + "')]." + field)).get(0);
    }

    private static List<String> historyEvents(String detail) {
        return JsonPath.read(detail, "$.history[*].event");
    }

    private static List<String> historyActors(String detail) {
        return JsonPath.read(detail, "$.history[*].actor");
    }

    // ---- AI の答えの後 ----

    @Test
    void 結果を確かめている間だけnoReplyを持ち_知らせる予定は起点の5営業日後() throws Exception {
        String intake = idOf(start());
        assertThat((Object) JsonPath.read(view(intake), "$.noReply")).isNull();

        String id = answeredByAi();
        backdate(id, ORIGIN);
        String noReply = text(mvc.perform(get("/api/inquiries/" + id)));
        // 起点は2026-01-05（月）12:00 東京。5営業日後は翌週の月曜の同じ時刻
        assertThat((String) JsonPath.read(noReply, "$.noReply.remindAt")).isEqualTo("2026-01-12T03:00:00Z");
        assertThat((Object) JsonPath.read(noReply, "$.noReply.remindedAt")).isNull();
        assertThat((Object) JsonPath.read(noReply, "$.noReply.closesAt")).isNull();
        assertThat(Instant.parse(JsonPath.read(noReply, "$.noReply.remindAt"))).isEqualTo(remindAfter(ORIGIN));
    }

    @Test
    void 開いただけでは起点は変わらない() throws Exception {
        String id = answeredByAi();
        backdate(id, ORIGIN);

        view(id);
        view(id);

        assertThat(((Timestamp) row(id).get("awaiting_since")).toInstant()).isEqualTo(ORIGIN);
    }

    @Test
    void AIの答えに返事がなければ_知らせてから回答済みで終わり_担当者には渡らない() throws Exception {
        String id = answeredByAi();
        backdate(id, ORIGIN);
        Instant remindAt = remindAfter(ORIGIN);

        // 知らせる時刻の直前は何もしない
        assertThat(rules.run(remindAt.minusSeconds(1))).isEqualTo(new TimeRuleService.Result(0, 0));
        assertThat((Boolean) row(id).get("asker_unread_reply")).isFalse();

        // 知らせる時刻ちょうどに知らせる。状態は変わらず、本人側は未読になる
        assertThat(rules.run(remindAt)).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        assertThat(listed(id, "awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat(((Timestamp) row(id).get("reminded_at")).toInstant()).isEqualTo(remindAt);
        // 本人が開くと既読になる。知らせた時刻と、終わる予定の時刻が見える
        String reminded = view(id);
        assertThat((Boolean) JsonPath.read(reminded, "$.unreadReply")).isFalse();
        assertThat((String) JsonPath.read(reminded, "$.noReply.remindedAt")).isEqualTo(remindAt.toString());
        assertThat((String) JsonPath.read(reminded, "$.noReply.closesAt"))
                .isEqualTo(BusinessDays.plus(remindAt, 5, TOKYO).toString());
        assertThat((String) JsonPath.read(reminded, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");

        // 知らせた直後には終えない。同じ時刻の2回目の判定でも何も起きない
        assertThat(rules.run(remindAt)).isEqualTo(new TimeRuleService.Result(0, 0));

        // 知らせてから5営業日後に終える
        Instant closesAt = BusinessDays.plus(remindAt, 5, TOKYO);
        assertThat(rules.run(closesAt)).isEqualTo(new TimeRuleService.Result(0, 1));
        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        String closed = view(id);
        assertThat((String) JsonPath.read(closed, "$.phase")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.ending")).isEqualTo("ANSWERED");
        assertThat((Object) JsonPath.read(closed, "$.awaiting")).isNull();
        assertThat((Object) JsonPath.read(closed, "$.noReply")).isNull();
        assertThat((Object) JsonPath.read(closed, "$.handedOverAt")).isNull();
        // 担当者に渡していない件は、担当者側には現れない
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 長く止まっていた後でも_知らせていない件は先に知らせるだけで終えない() throws Exception {
        String id = answeredByAi();
        backdate(id, ORIGIN);

        assertThat(rules.run(ORIGIN.plusSeconds(86400L * 200))).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat((String) JsonPath.read(view(id), "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
    }

    @Test
    void 本人が答えるか追記すれば対象から外れる() throws Exception {
        String resolved = answeredByAi();
        backdate(resolved, ORIGIN);
        resolve(resolved, true).andExpect(status().isOk());

        String notResolved = answeredByAi();
        backdate(notResolved, ORIGIN);
        resolve(notResolved, false).andExpect(status().isOk());

        assertThat(rules.run(ORIGIN.plusSeconds(86400L * 200))).isEqualTo(new TimeRuleService.Result(0, 0));
        assertThat((String) JsonPath.read(view(resolved), "$.ending")).isEqualTo("RESOLVED");
        assertThat((String) JsonPath.read(view(notResolved), "$.awaiting")).isEqualTo("HANDLING");
    }

    // ---- 担当者の結果の後 ----

    @Test
    void 担当者の結果にも同じ決まりが働き_履歴に時間が起こした出来事が残り_担当者側の未読は変わらない() throws Exception {
        String id = operatorResult();
        backdate(id, ORIGIN);
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        Instant remindAt = remindAfter(ORIGIN);

        assertThat(rules.run(remindAt)).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        String reminded = operatorDetail(id);
        assertThat((Boolean) JsonPath.read(reminded, "$.read")).isTrue();
        assertThat((String) JsonPath.read(reminded, "$.noReply.remindedAt")).isEqualTo(remindAt.toString());
        assertThat(historyEvents(reminded)).endsWith("RESULT_DELIVERED", "REMINDED");
        assertThat((List<String>) JsonPath.read(reminded, "$.history[-1:].at")).containsExactly(remindAt.toString());
        assertThat((String) JsonPath.read(reminded, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");

        MvcResult stream = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        Instant closesAt = BusinessDays.plus(remindAt, 5, TOKYO);
        assertThat(rules.run(closesAt)).isEqualTo(new TimeRuleService.Result(0, 1));

        String closed = operatorDetail(id);
        assertThat(historyEvents(closed)).endsWith("RESULT_DELIVERED", "REMINDED", "ANSWERED");
        assertThat(historyActors(closed)).endsWith("OPERATOR", "TIME", "TIME");
        assertThat((String) JsonPath.read(closed, "$.phase")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.ending")).isEqualTo("ANSWERED");
        assertThat((Object) JsonPath.read(closed, "$.noReply")).isNull();
        // 担当者の側の未読は変えない
        assertThat((Boolean) JsonPath.read(closed, "$.read")).isTrue();
        // 担当者に渡したことのある件が終わったので、担当者のストリームに updated が流れる
        assertThat(waitFor(() -> content(stream).contains("event:updated") && content(stream).contains(id), 5000))
                .isTrue();
        assertThat(listedOperator(id, "ending")).isEqualTo("ANSWERED");
        assertThat(listed(id, "unreadReply")).isEqualTo(true);
    }

    @Test
    void 担当者がもう一度結果を伝えると起点も知らせたかどうかも最初からになる() throws Exception {
        String id = operatorResult();
        backdate(id, ORIGIN);
        Instant remindAt = remindAfter(ORIGIN);
        assertThat(rules.run(remindAt)).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat(row(id).get("reminded_at")).isNotNull();

        // 本人が「解決していない」と答えて担当者の番に戻り、担当者がもう一度結果を伝える
        resolve(id, false).andExpect(status().isOk());
        assertThat(row(id).get("reminded_at")).isNull();
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT", "body", "もう一度結果です")))
                .andExpect(status().isOk());

        Instant newSince = ((Timestamp) row(id).get("awaiting_since")).toInstant();
        assertThat(newSince).isAfter(ORIGIN.plusSeconds(86400L * 100));
        assertThat(row(id).get("reminded_at")).isNull();
        String view = view(id);
        assertThat((Object) JsonPath.read(view, "$.noReply.remindedAt")).isNull();
        assertThat((String) JsonPath.read(view, "$.noReply.remindAt")).isEqualTo(remindAfter(newSince).toString());
        // 前の起点での時刻では、何も起きない
        assertThat(rules.run(remindAt.plusSeconds(86400L * 30))).isEqualTo(new TimeRuleService.Result(0, 0));
    }

    // ---- 対象外 ----

    @Test
    void 聞き返しへの返事を待つ件と入口の途中の件は対象外() throws Exception {
        String asked = filedNow("期限の仕様", "job-admin");
        mvc.perform(post("/api/operator/inquiries/" + asked + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "詳しく教えてください")))
                .andExpect(status().isOk());
        jdbc.update("update inquiries set awaiting_since = ? where id = ?::uuid", Timestamp.from(ORIGIN), asked);
        String intake = idOf(start());
        jdbc.update("update inquiries set awaiting_since = ? where id = ?::uuid", Timestamp.from(ORIGIN), intake);
        String handling = filedNow("期限の仕様", "job-admin");
        jdbc.update("update inquiries set awaiting_since = ? where id = ?::uuid", Timestamp.from(ORIGIN), handling);

        assertThat(rules.run(ORIGIN.plusSeconds(86400L * 200))).isEqualTo(new TimeRuleService.Result(0, 0));

        assertThat((String) JsonPath.read(view(asked), "$.awaiting")).isEqualTo("ASKER_REPLY");
        assertThat((String) JsonPath.read(view(intake), "$.awaiting")).isEqualTo("INTAKE_ANSWER");
        assertThat((String) JsonPath.read(view(handling), "$.awaiting")).isEqualTo("HANDLING");
        assertThat(row(asked).get("reminded_at")).isNull();
    }

    // ---- 追記による開き直し ----

    @Test
    void 担当者に渡したことのない回答済みの件に追記すると担当者に初めて渡る() throws Exception {
        String id = answeredByAi();
        backdate(id, ORIGIN);
        Instant remindAt = remindAfter(ORIGIN);
        rules.run(remindAt);
        rules.run(BusinessDays.plus(remindAt, 5, TOKYO));
        assertThat((String) JsonPath.read(view(id), "$.ending")).isEqualTo("ANSWERED");
        assertThat(row(id).get("handed_over_at")).isNull();
        notifier.notices().clear();

        String reopened = text(mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "やはり担当者に確認したいです")))
                .andExpect(status().isOk()));

        // 区間は種類で決まる（HOW_IT_WORKS は KNOW）。番は担当者に移り、初めて渡した時刻が入る
        assertThat((String) JsonPath.read(reopened, "$.phase")).isEqualTo("KNOW");
        assertThat((String) JsonPath.read(reopened, "$.turn")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(reopened, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((Object) JsonPath.read(reopened, "$.ending")).isNull();
        assertThat((String) JsonPath.read(reopened, "$.handedOverAt")).isNotNull();
        assertThat((Boolean) JsonPath.read(reopened, "$.sentImmediately")).isFalse();
        // 初めて渡したときの通知が働く
        assertThat(notifier.notices()).extracting(n -> n.inquiryId().toString()).containsExactly(id);
        // 担当者の画面から見え、未読で、履歴は REOPENED（本人）
        String detail = operatorDetail(id);
        assertThat((Boolean) JsonPath.read(detail, "$.read")).isFalse();
        assertThat(historyEvents(detail)).endsWith("REMINDED", "ANSWERED", "REOPENED");
        assertThat(historyActors(detail)).endsWith("TIME", "TIME", "ASKER");
        assertThat((List<String>) JsonPath.read(detail, "$.messages[*].body")).contains("やはり担当者に確認したいです");
    }

    @Test
    void 担当者に渡したことがなく解決で終わった件への追記は引き続き受け付けない() throws Exception {
        String id = answeredByAi();
        resolve(id, true).andExpect(status().isOk());

        mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "追記")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ---- 開発用の口 ----

    @Test
    void 開発用の口は時刻を指定して判定し_件数を返す() throws Exception {
        String id = answeredByAi();
        backdate(id, ORIGIN);
        Instant remindAt = remindAfter(ORIGIN);

        mvc.perform(post("/api/dev/rules/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("now", remindAt.minusSeconds(1).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminded").value(0))
                .andExpect(jsonPath("$.closed").value(0));
        mvc.perform(post("/api/dev/rules/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("now", remindAt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminded").value(1))
                .andExpect(jsonPath("$.closed").value(0));
        // 知らせた時刻には、与えた時刻を使う
        assertThat(((Timestamp) row(id).get("reminded_at")).toInstant()).isEqualTo(remindAt);
        mvc.perform(post("/api/dev/rules/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("now", BusinessDays.plus(remindAt, 5, TOKYO).toString())))
                .andExpect(jsonPath("$.reminded").value(0))
                .andExpect(jsonPath("$.closed").value(1));
    }

    @Test
    void 開発用の口は時刻を省くと現在時刻で判定し_形が崩れた時刻はVALIDATION() throws Exception {
        // 現在時刻では、どの件の期間も過ぎていない（起点を過去に置いた件は、事前に戻している）
        mvc.perform(post("/api/dev/rules/run").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminded").value(0))
                .andExpect(jsonPath("$.closed").value(0));
        mvc.perform(post("/api/dev/rules/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminded").value(0));
        ResultActions bad = mvc.perform(post("/api/dev/rules/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("now", "昨日")));
        bad.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    private Object listedOperator(String id, String field) throws Exception {
        String body = text(mvc.perform(get("/api/operator/inquiries")).andExpect(status().isOk()));
        return ((List<Object>) JsonPath.read(body, "$.items[?(@.id=='" + id + "')]." + field)).get(0);
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
