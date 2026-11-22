package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Urgency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 種類ごとの経路、言い直しの確定と作り直し、答えを待たずに送る条件、進捗ストリーム、入力検証を、
 * 実際の PostgreSQL とモック実装に対して確認する。入口の質問そのものは {@link IntakeApiTest}。
 */
class InquiryApiTest extends ApiSupport {

    private static final String SPEC = "求人の掲載期限の仕様";

    @Value("${inquiry.filed-guidance}")
    String filedGuidance;

    @BeforeEach
    void clearNotices() {
        notifier.notices().clear();
    }

    // ---- ヘルパー ----

    /**
     * 押して答えるだけで、問いの組み立て（STRUCTURED）まで進める。システムは機能の設定がない「レポート」を選び、
     * 聞き手の質問に答え、補足に {@code note} を書く（null なら書かずに進む）。受付は呼ばれない。
     */
    private String pushToStructured(String type, String note) throws Exception {
        String id = idOf(start());
        pick(id, "type", type).andExpect(status().isOk());
        pick(id, "system", "reports").andExpect(status().isOk());
        pick(id, "q1", "o1").andExpect(status().isOk());
        ResultActions last = note == null ? skip(id, "note") : write(id, "note", note);
        last.andExpect(status().isOk()).andExpect(jsonPath("$.step").value("STRUCTURED"));
        return id;
    }

    private String pushToAnswering(String type, String note) throws Exception {
        String id = pushToStructured(type, note);
        confirm(id).andExpect(status().isOk()).andExpect(jsonPath("$.step").value("ANSWERING"));
        return id;
    }

    private ResultActions confirmSummary(String id, String summary) throws Exception {
        return mvc.perform(put("/api/inquiries/" + id + "/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("summary", summary)));
    }

    private ResultActions revise(String id, String note) throws Exception {
        return postJson("/api/inquiries/" + id + "/revision", json("note", note));
    }

    private ResultActions reopen(String id) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/reopen"));
    }

    /** SSE の本文から、(event名, data) の並びを取り出す。 */
    private static List<Map.Entry<String, String>> events(String body) {
        List<Map.Entry<String, String>> events = new ArrayList<>();
        String name = null;
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && name != null) {
                events.add(Map.entry(name, line.substring("data:".length()).trim()));
                name = null;
            }
        }
        return events;
    }

    private static String doneView(String streamBody) {
        return events(streamBody).stream().filter(e -> e.getKey().equals("done")).findFirst().orElseThrow().getValue();
    }

    /** 進捗の後に failed が最後に1回だけ流れ、done は流れないことを確かめる。 */
    private static void assertFailedLast(List<Map.Entry<String, String>> events, String code) {
        assertThat(events.stream().map(Map.Entry::getKey).filter(k -> !k.equals("progress")).toList())
                .containsExactly("failed");
        var last = events.get(events.size() - 1);
        assertThat(last.getKey()).isEqualTo("failed");
        assertThat((String) JsonPath.read(last.getValue(), "$.code")).isEqualTo(code);
    }

    // ---- 種類ごとの経路 ----

    @Test
    void 使い方を知りたいは本人が解決と判定すると自己解決で終わる() throws Exception {
        String id = pushToStructured("HOW_TO", null);
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));

        String done = doneView(stream(id));

        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("IN_PROGRESS");
        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isTrue();
        assertThat((Boolean) JsonPath.read(done, "$.answer.answered")).isTrue();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).hasSize(2);
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.findings")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.notFound")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.nextChecks")).isNotEmpty();
        assertThat(notifier.notices()).isEmpty();

        resolve(id, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("SELF_RESOLVED"));
        assertThat(notifier.notices()).isEmpty();
        assertThat(row(id).get("filed_at")).isNull();
    }

    @Test
    void 仕組みを知りたいは未解決と判定すると担当者に送られ通知と記録が残る() throws Exception {
        String id = pushToAnswering("HOW_IT_WORKS", null);
        stream(id);

        resolve(id, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(false))
                .andExpect(jsonPath("$.handlingStatus").value("OPEN"));

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.inquiryId().toString()).isEqualTo(id);
            assertThat(n.type()).isEqualTo(InquiryType.HOW_IT_WORKS);
            assertThat(n.urgency()).isEqualTo(Urgency.NORMAL);
            assertThat(n.sentImmediately()).isFalse();
            assertThat(n.systemLabel()).isEqualTo("レポート");
            assertThat(n.functionLabel()).isNull();
            assertThat(n.memo().findings()).isNotEmpty();
        });
        Map<String, Object> row = row(id);
        assertThat(row.get("filed_at")).isNotNull();
        assertThat(row.get("step")).isEqualTo("CLOSED");
        assertThat(row.get("mock")).isEqualTo(true);
        assertThat(row.get("asker_id")).isEqualTo("local-user");
        // 押して答えただけなので、受付は呼ばれない。聞き手、言い直し、調査、説明をスキルの識別子で追記している
        assertThat(usageSteps(id)).containsExactly("interviewer", "restater", "investigator", "explainer");
    }

    @Test
    void おかしいかもしれないは0次回答の完了と同時に担当者に送られる() throws Exception {
        String id = pushToAnswering("LOOKS_WRONG", null);

        String done = doneView(stream(id));

        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("FILED");
        assertThat((Boolean) JsonPath.read(done, "$.sentImmediately")).isFalse();
        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).contains("現在の実装では");
        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(InquiryType.LOOKS_WRONG);
            assertThat(n.memo().nextChecks()).isNotEmpty();
        });
        assertThat(row(id).get("filed_at")).isNotNull();
    }

    @Test
    void やってほしいは根拠があり問いに答えていれば本人が確かめる段階になり_自分で行うか担当者に頼むかを選べる() throws Exception {
        String selfService = pushToAnswering("DO_FOR_ME", null);
        String asked = pushToAnswering("DO_FOR_ME", null);

        String done = doneView(stream(selfService));
        stream(asked);

        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("ANSWERED");
        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isTrue();
        assertThat((Boolean) JsonPath.read(done, "$.answer.answered")).isTrue();
        resolve(selfService, true).andExpect(jsonPath("$.outcome").value("SELF_RESOLVED"));
        resolve(asked, false).andExpect(jsonPath("$.outcome").value("FILED"));
        assertThat(notifier.notices())
                .singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo(InquiryType.DO_FOR_ME));
    }

    @Test
    void やってほしいは根拠がなくても的外れでも_答えの完了と同時に担当者に送られる() throws Exception {
        String ungrounded = pushToAnswering("DO_FOR_ME", "[根拠なし]");
        String offTopic = pushToAnswering("DO_FOR_ME", "[的外れ]");
        String ghost = pushToAnswering("DO_FOR_ME", "[幽霊出典]");

        for (String id : List.of(ungrounded, offTopic, ghost)) {
            String done = doneView(stream(id));
            assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("CLOSED");
            assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("FILED");
            assertThat((Boolean) JsonPath.read(done, "$.sentImmediately")).isFalse();
        }
        assertThat(notifier.notices()).hasSize(3);
    }

    @Test
    void 変えてほしいは確定と同時に担当者に送られ_調査はしない() throws Exception {
        String id = pushToStructured("CHANGE_REQUEST", null);

        confirm(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(false))
                .andExpect(jsonPath("$.answer").value(nullValue()));

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(InquiryType.CHANGE_REQUEST);
            assertThat(n.memo()).isNull();
        });
        assertThat(usageSteps(id)).containsExactly("interviewer", "restater");
    }

    @Test
    void 種類が決まっていない問い合わせは仕組みを知りたいと同じ経路で答えを本人が確かめる() throws Exception {
        String id = idOf(start());
        unknown(id, "type");
        unknown(id, "system");
        unknown(id, "q1");
        // すべてわからないと答え、文章もないので、補足は省けない
        write(id, "note", "状況の説明")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.type").value(nullValue()));
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));

        String done = doneView(stream(id));

        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("ANSWERED");
        assertThat((Object) JsonPath.read(done, "$.type")).isNull();
        assertThat((String) JsonPath.read(done, "$.structured.summary")).startsWith("相談したいことがある。");
    }

    // ---- タグの振る舞い（HTTP 経由） ----

    @Test
    void 根拠なしタグは根拠なしの回答になり技術メモが残る() throws Exception {
        String done = doneView(stream(pushToAnswering("HOW_IT_WORKS", "[根拠なし]")));

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer.askerExplanation")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.notFound")).isNotEmpty();
    }

    @Test
    void 幽霊出典は照合で落ちて根拠なしになり技術メモと照合結果が残る() throws Exception {
        String id = pushToAnswering("HOW_IT_WORKS", "[幽霊出典]");

        String done = doneView(stream(id));

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer.askerExplanation")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).isEmpty();
        // 事実は確かめられなかったものとして残り、分かったことには入らない
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.findings")).isEmpty();
        // 確かめられなかった事実は担当者だけに見せるため、本人向けの応答には含めない
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.unconfirmed")).isEmpty();
        // 画面には出さないが、記録には照合失敗として残す
        assertThat(jdbc.queryForObject(
                        "select citations->0->>'verified' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("false");
        assertThat(row(id).get("answer_grounded")).isEqualTo(false);
    }

    @Test
    void エラータグは失敗を流し段階はANSWERINGのまま答えを待たずに送れる() throws Exception {
        String id = pushToAnswering("HOW_IT_WORKS", "[エラー]");

        var events = events(stream(id));

        assertFailedLast(events, "AI_UNAVAILABLE");
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.step").value("ANSWERING"));
        sendNow(id).andExpect(jsonPath("$.outcome").value("FILED"));
    }

    @Test
    void 拒否タグはAI_REFUSEDの失敗を流す() throws Exception {
        var events = events(stream(pushToAnswering("HOW_IT_WORKS", "[拒否]")));

        assertFailedLast(events, "AI_REFUSED");
    }

    // ---- 言い直しの失敗 ----

    @Test
    void 言い直しの失敗は503とinquiryIdを返し_答えは保存され_入口は終わったまま答えを待たずに送れる() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o2");

        String body = text(write(id, "note", "[言い直しエラー]")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.inquiryId").value(id)));

        assertThat(body).contains(id);
        String view = view(id);
        assertThat((String) JsonPath.read(view, "$.step")).isEqualTo("INTAKE");
        assertThat((Boolean) JsonPath.read(view, "$.intake.complete")).isTrue();
        assertThat((Object) JsonPath.read(view, "$.intake.next")).isNull();
        assertThat((Boolean) JsonPath.read(view, "$.canSendNow")).isTrue();
        assertThat((Object) JsonPath.read(view, "$.structured")).isNull();
        // 答えは保存されている
        assertThat((List<String>) JsonPath.read(view, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "q1", "note");
        assertThat((String) JsonPath.read(view, "$.utterance")).isEqualTo("[言い直しエラー]");
        // 失敗した呼び出しとして利用量に残る
        assertThat(usageSteps(id)).containsExactly("interviewer", "restater");
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    @Test
    void 言い直しの失敗の後はcomposeでやり直せ_戻って直せば成功する() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o2");
        write(id, "note", "[言い直しエラー]").andExpect(status().isServiceUnavailable());

        // タグが残っているので、そのままやり直しても同じ失敗になる。エラーには inquiryId が付く
        compose(id)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.inquiryId").value(id));
        // 補足まで戻って書き直すと、組み立てられる
        back(id, "note").andExpect(status().isOk()).andExpect(jsonPath("$.canSendNow").value(false));
        write(id, "note", "直しました")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.canSendNow").value(false));
    }

    @Test
    void 言い直しの失敗の後_答えを待たずに送ると担当者に届く() throws Exception {
        String id = idOf(create("[言い直しエラー] " + SPEC));
        unknown(id, "system").andExpect(status().isOk());
        unknown(id, "q1").andExpect(status().isServiceUnavailable());

        sendNow(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true));

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.structured()).isNull();
            assertThat(n.utterance()).isEqualTo("[言い直しエラー] " + SPEC);
            assertThat(n.sentImmediately()).isTrue();
        });
    }

    // ---- 答えを待たずに送る（canSendNow） ----

    @Test
    void 答えを待たずに送れるのは_問いを確かめた後_AIの失敗で進めない場合_作り直しを使い切った場合だけ() throws Exception {
        String intake = idOf(create(SPEC));
        String structured = toStructured(SPEC, null);
        String answering = toAnswering(SPEC, null);
        String answered = toAnswering(SPEC, null);
        stream(answered);

        // 入口の途中と、言い直しの確認の前は送れない
        for (String id : List.of(intake, structured)) {
            assertThat((Boolean) JsonPath.read(view(id), "$.canSendNow")).isFalse();
            sendNow(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
        // 確定した後は送れる
        for (String id : List.of(answering, answered)) {
            assertThat((Boolean) JsonPath.read(view(id), "$.canSendNow")).isTrue();
            sendNow(id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("CLOSED"))
                    .andExpect(jsonPath("$.outcome").value("FILED"))
                    .andExpect(jsonPath("$.sentImmediately").value(true))
                    .andExpect(jsonPath("$.canSendNow").value(false));
            assertThat(row(id).get("sent_immediately")).isEqualTo(true);
            assertThat(row(id).get("filed_at")).isNotNull();
        }
        // 確定した後の問い合わせは、整形後の問いを持って通知される。回答後に送った分は技術メモを持つ
        assertThat(notifier.notices()).hasSize(2).allSatisfy(n -> {
            assertThat(n.sentImmediately()).isTrue();
            assertThat(n.structured()).isNotNull();
        });
        assertThat(notifier.notices().get(0).memo()).isNull();
        assertThat(notifier.notices().get(1).memo()).isNotNull();
    }

    @Test
    void 作り直しを使い切ると答えを待たずに送れる() throws Exception {
        String id = toStructured(SPEC, null);
        assertThat((Boolean) JsonPath.read(view(id), "$.canSendNow")).isFalse();

        revise(id, "1回目").andExpect(jsonPath("$.canSendNow").value(false));
        revise(id, "2回目").andExpect(jsonPath("$.revisionsLeft").value(0)).andExpect(jsonPath("$.canSendNow").value(true));

        sendNow(id)
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true));
        // 送った時点の言い直しで通知される
        assertThat(notifier.notices())
                .singleElement()
                .satisfies(n -> assertThat(n.structured().summary()).contains("補足: 2回目"));
    }

    @Test
    void 終了した問い合わせは送れない() throws Exception {
        String id = filedNow(SPEC, null);

        sendNow(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat((Boolean) JsonPath.read(view(id), "$.canSendNow")).isFalse();
    }

    // ---- 不正な段階 ----

    @Test
    void 不正な段階の操作は409のINVALID_STATEになる() throws Exception {
        String intake = idOf(create(SPEC));
        String structured = toStructured(SPEC, null);
        String answering = toAnswering(SPEC, null);

        confirm(intake).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        resolve(intake, true).andExpect(status().isConflict());
        revise(intake, "x").andExpect(status().isConflict());
        reopen(intake).andExpect(status().isConflict());
        pick(structured, "type", "HOW_TO").andExpect(status().isConflict());
        back(structured, "type").andExpect(status().isConflict());
        intakeText(structured, "x").andExpect(status().isConflict());
        compose(structured).andExpect(status().isConflict());
        confirm(answering).andExpect(status().isConflict());
        revise(answering, "x").andExpect(status().isConflict());
        reopen(answering).andExpect(status().isConflict());
        urgency(answering, "BLOCKED").andExpect(status().isConflict());
    }

    @Test
    void ANSWERINGでない問い合わせのストリームはINVALID_STATEの失敗を流す() throws Exception {
        String id = idOf(create(SPEC));

        var events = events(stream(id));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getKey()).isEqualTo("failed");
            assertThat((String) JsonPath.read(e.getValue(), "$.code")).isEqualTo("INVALID_STATE");
        });
    }

    @Test
    void 回答済みの問い合わせを再度ストリームするとINVALID_STATEになる() throws Exception {
        String id = toAnswering(SPEC, null);
        stream(id);

        var events = events(stream(id));

        assertThat(events).singleElement().satisfies(e -> assertThat(e.getKey()).isEqualTo("failed"));
    }

    @Test
    void inquiryIdは言い直しの失敗以外のエラー本文に含まれない() throws Exception {
        String body = text(mvc.perform(get("/api/inquiries/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound()));
        assertThat(body).doesNotContain("inquiryId");

        String conflict = text(resolve(idOf(create(SPEC)), true).andExpect(status().isConflict()));
        assertThat(conflict).doesNotContain("inquiryId");
    }

    // ---- 入力検証・存在確認 ----

    @Test
    void 存在しない問い合わせは404になる() throws Exception {
        String missing = "00000000-0000-0000-0000-000000000000";
        mvc.perform(get("/api/inquiries/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/inquiries/" + missing + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        pick(missing, "type", "HOW_TO").andExpect(status().isNotFound());
        urgency(missing, "BLOCKED").andExpect(status().isNotFound());
    }

    @Test
    void 不正な作成の入力は400のVALIDATIONになる() throws Exception {
        for (String text : List.of("", "   ", "あ".repeat(4001))) {
            postJson("/api/inquiries", json("text", text))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        postJson("/api/inquiries", "{\"urgency\":\"URGENT\"}").andExpect(status().isBadRequest());
        postJson("/api/inquiries", "{").andExpect(status().isBadRequest());
        mvc.perform(get("/api/inquiries/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void 本文なしでも作れる() throws Exception {
        String body = text(mvc.perform(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()));

        assertThat((String) JsonPath.read(body, "$.step")).isEqualTo("INTAKE");
        assertThat((String) JsonPath.read(body, "$.intake.next.questionId")).isEqualTo("type");
        assertThat((String) JsonPath.read(body, "$.urgency")).isEqualTo("NORMAL");
    }

    @Test
    void 不正な確定の本文は400になる() throws Exception {
        String id = toStructured(SPEC, null);

        confirmSummary(id, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        confirmSummary(id, "   ").andExpect(status().isBadRequest());
        confirmSummary(id, "あ".repeat(2001)).andExpect(status().isBadRequest());
        // 旧形式の5項目は受け付けない
        mvc.perform(put("/api/inquiries/" + id + "/structured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSystem\":null,\"goal\":\"x\",\"whatHappened\":null,"
                                + "\"expectedResult\":null,\"urgency\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.step").value("STRUCTURED"));
    }

    @Test
    void 本文なしの確定はAIの言い直しをそのまま確定する() throws Exception {
        String id = toStructured(SPEC, null);
        String aiSummary = JsonPath.read(view(id), "$.structured.summary");

        confirm(id).andExpect(jsonPath("$.structured.summary").value(aiSummary));

        assertThat(jdbc.queryForObject(
                        "select structured_confirmed->>'summary' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo(aiSummary);
    }

    @Test
    void summaryありの確定はsummaryだけを置き換えgoalとタグの振る舞いは保たれる() throws Exception {
        String text = "[幽霊出典] " + SPEC;
        String id = toStructured(text, null);

        confirmSummary(id, "自分で書き直した文章")
                .andExpect(jsonPath("$.step").value("ANSWERING"))
                .andExpect(jsonPath("$.structured.summary").value("自分で書き直した文章"))
                .andExpect(jsonPath("$.structured.goal").value(text));
        String done = doneView(stream(id));

        // goal が変わらないため、タグ（幽霊出典）の振る舞いが最後まで保たれる
        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat(jdbc.queryForObject(
                        "select structured_by_ai->>'summary' from inquiries where id = ?::uuid", String.class, id))
                .isNotEqualTo("自分で書き直した文章");
    }

    // ---- 言い直し（summary） ----

    @Test
    void 文章で始めた言い直しは置き場所の前置きと種類の文に続けてタグを除いた文章を書く() throws Exception {
        String id = idOf(create("[質問なし] 求人管理画面の求人の掲載がおかしい"));

        // 種類、システム、機能は文章から置かれ、設定の詳しい質問（since）に「わからない」と答えて組み立てる
        String last = finishIntake(id, null);

        assertThat((String) JsonPath.read(last, "$.structured.summary"))
                .isEqualTo("求人管理画面の求人の掲載について、動きがおかしいかもしれない。意図した動きかどうかを確認したい。"
                        + " 書かれた内容: 求人管理画面の求人の掲載がおかしい");
        // goal には、タグも含めて本人が書いた文章をそのまま入れる
        assertThat((String) JsonPath.read(last, "$.structured.goal"))
                .isEqualTo("[質問なし] 求人管理画面の求人の掲載がおかしい");
    }

    @Test
    void 言い直しは押して答えただけなら種類ごとの文と答えから作られgoalは種類ごとの文() throws Exception {
        String id = idOf(start());
        pick(id, "type", "LOOKS_WRONG");
        pick(id, "system", "job-admin");
        pick(id, "function", "posting");
        pick(id, "since", "today");

        String last = text(skip(id, "note").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(last, "$.structured.summary"))
                .isEqualTo("求人管理画面の求人の掲載について、動きがおかしいかもしれない。意図した動きかどうかを確認したい。"
                        + " （いつから起きていますか: 今日から）");
        assertThat((String) JsonPath.read(last, "$.structured.goal"))
                .isEqualTo("動きがおかしいかもしれない。意図した動きかどうかを確認したい。");
        assertThat((String) JsonPath.read(last, "$.structured.targetSystem")).isEqualTo("求人管理画面");
        // 緊急度は整形後の問いに持たない（問い合わせの urgency に一本化している）
        assertThat(last).doesNotContain("\"urgency\":\"HIGH\"");
        assertThat((String) JsonPath.read(last, "$.urgency")).isEqualTo("NORMAL");
    }

    @Test
    void 言い直しは文章で答えた詳しい質問と補足の文章を含み_わからないは除く() throws Exception {
        String id = idOf(start());
        pick(id, "type", "DO_FOR_ME");
        pick(id, "system", "billing");
        pick(id, "function", "invoice");
        write(id, "partner", "A社");

        String last = text(write(id, "note", "来週までに").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(last, "$.structured.summary"))
                .isEqualTo("請求管理の請求書について、作業をしてほしい。 書かれた内容: 来週までに （対象の取引先はどこですか: A社）");
        assertThat((String) JsonPath.read(last, "$.utterance")).isEqualTo("来週までに");

        String unknownId = idOf(start());
        pick(unknownId, "type", "DO_FOR_ME");
        pick(unknownId, "system", "billing");
        pick(unknownId, "function", "invoice");
        unknown(unknownId, "partner");
        String unknownLast = text(skip(unknownId, "note").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(unknownLast, "$.structured.summary"))
                .isEqualTo("請求管理の請求書について、作業をしてほしい。");
    }

    @Test
    void 確定した対象システムの名前がstructuredのtargetSystemに入り作り直しでも保たれる() throws Exception {
        String id = toStructured("期限の仕様を教えて", "job-admin");

        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        revise(id, "違う").andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        confirm(id).andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        assertThat(jdbc.queryForObject(
                        "select structured_confirmed->>'targetSystem' from inquiries where id = ?::uuid",
                        String.class,
                        id))
                .isEqualTo("求人管理画面");
        // 決まっていなければ null のまま
        String unknown = toStructured("期限の仕様を教えて", null);
        mvc.perform(get("/api/inquiries/" + unknown))
                .andExpect(jsonPath("$.structured.targetSystem").value(nullValue()));
    }

    // ---- 作り直し（5a）・入口の質問へ戻る（5b） ----

    @Test
    void 作り直しで補足が入り残り回数が減り3回目は409になり一言が保存される() throws Exception {
        String id = toStructured(SPEC, null);
        String base = JsonPath.read(view(id), "$.structured.summary");
        assertThat((Integer) JsonPath.read(view(id), "$.revisionsLeft")).isEqualTo(2);

        revise(id, "消えたのは一昨日から")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.revisionsLeft").value(1))
                .andExpect(jsonPath("$.structured.summary").value(base + " 補足: 消えたのは一昨日から"))
                .andExpect(jsonPath("$.structured.goal").value(SPEC));
        revise(id, "画面は管理画面")
                .andExpect(jsonPath("$.revisionsLeft").value(0))
                .andExpect(jsonPath("$.structured.summary").value(base + " 補足: 消えたのは一昨日から 補足: 画面は管理画面"));
        revise(id, "三回目").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(jdbc.queryForObject(
                        "select revision_notes::text from inquiries where id = ?::uuid", String.class, id))
                .contains("消えたのは一昨日から", "画面は管理画面");
        assertThat(row(id).get("revisions_left")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from inquiries, jsonb_array_elements(usages) u"
                                + " where id = ?::uuid and u->>'step' = 'restater'",
                        Integer.class,
                        id))
                .isEqualTo(3); // 言い直し1回と作り直し2回
        // 使い切っても、そのまま確定して先へ進める
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));
    }

    @Test
    void 作り直しの一言の検証() throws Exception {
        String id = toStructured(SPEC, null);

        revise(id, "").andExpect(status().isBadRequest());
        revise(id, "あ".repeat(1001)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.revisionsLeft").value(2));
    }

    @Test
    void 入口の質問へ戻ると最後に答えた質問が次の質問になりその答えは捨てられ_作り直しの回数は戻らない() throws Exception {
        String id = pushToStructured("HOW_TO", "メモ");
        revise(id, "違う").andExpect(jsonPath("$.revisionsLeft").value(1));

        reopen(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("INTAKE"))
                .andExpect(jsonPath("$.structured").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.questionId").value("note"))
                .andExpect(jsonPath("$.intake.next.kind").value("NOTE"))
                .andExpect(jsonPath("$.intake.entries[*].questionId")
                        .value(org.hamcrest.Matchers.contains("type", "system", "q1")))
                .andExpect(jsonPath("$.utterance").value(nullValue()))
                .andExpect(jsonPath("$.revisionsLeft").value(1));

        write(id, "note", "その他")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.revisionsLeft").value(1))
                .andExpect(jsonPath("$.structured.summary").value(org.hamcrest.Matchers.containsString("その他")));
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));
    }

    @Test
    void 文章で始めた問い合わせの確認から戻ると最後に答えた質問に戻る() throws Exception {
        String id = toStructured("[質問なし] 求人管理画面の求人の掲載がおかしい", null);
        // 種類、システム、機能は文章から置かれ、設定の詳しい質問に答えて STRUCTURED になった
        assertThat((List<String>) JsonPath.read(view(id), "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function", "since");

        reopen(id)
                .andExpect(jsonPath("$.intake.next.questionId").value("since"))
                .andExpect(jsonPath("$.type.by").value("AI"))
                .andExpect(jsonPath("$.utterance").value("[質問なし] 求人管理画面の求人の掲載がおかしい"));
    }

    @Test
    void configはモック動作中を返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.filedGuidance").value(filedGuidance))
                .andExpect(jsonPath("$.incidentGuidance").doesNotExist());
        // 設定ファイルの日本語が文字化けせずに読めていること
        assertThat(filedGuidance).startsWith("担当者が内容を確認し、このページに返信します。");
    }

    // ---- SSE ----

    @Test
    void SSEは進捗を流してから完了を流す() throws Exception {
        String id = toAnswering(SPEC, null);

        String body = stream(id);

        var events = events(body);
        assertThat(events.stream().map(Map.Entry::getKey).toList())
                .containsExactly("progress", "progress", "progress", "progress", "progress", "done");
        assertThat(events.stream().filter(e -> e.getKey().equals("done")).count()).isEqualTo(1);
        // 調査の進捗3件に続けて、根拠の確認と説明のまとめ
        assertThat(events.subList(0, 5).stream().map(e -> (String) JsonPath.read(e.getValue(), "$.message")).toList())
                .endsWith("根拠を確かめています", "答えをまとめています");
        assertThat((String) JsonPath.read(events.get(events.size() - 1).getValue(), "$.step")).isEqualTo("ANSWERED");
    }
}
