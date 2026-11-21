package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.TestcontainersConfiguration;
import dev.inquiryassist.TestcontainersConfiguration.RecordingFilingNotifier;
import dev.inquiryassist.core.InquiryCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 契約書の9エンドポイントを、実際の PostgreSQL とモック実装に対して確認する。 */
@SpringBootTest(properties = "inquiry.mock.slow-step-delay=100ms")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InquiryApiTest {

    private static final String SPEC = "求人の掲載期限の仕様を知りたい";
    private static final String BUG = "求人の表示がおかしい";
    private static final String WORK = "掲載期間の延長をお願いします";
    private static final String INCIDENT = "サイトが止まっていて障害になっている";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingFilingNotifier notifier;

    @Value("${inquiry.incident-guidance}")
    String incidentGuidance;

    @Value("${inquiry.filed-guidance}")
    String filedGuidance;

    @BeforeEach
    void clearNotices() {
        notifier.notices().clear();
    }

    // ---- ヘルパー ----

    private static String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = keyValues[i + 1];
            sb.append('"').append(keyValues[i]).append("\":");
            sb.append(v == null || v instanceof Boolean ? String.valueOf(v) : "\"" + v + "\"");
        }
        return sb.append('}').toString();
    }

    private ResultActions send(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String create(String utterance) throws Exception {
        return send(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON).content(json("utterance", utterance)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String idOf(String view) {
        return JsonPath.read(view, "$.id");
    }

    private ResultActions answers(String id, String body) throws Exception {
        return send(post("/api/inquiries/" + id + "/answers").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 本文なしの確定（AI の言い直しをそのまま確定する）。 */
    private ResultActions confirm(String id) throws Exception {
        return send(put("/api/inquiries/" + id + "/structured").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private ResultActions confirmSummary(String id, String summary) throws Exception {
        return send(put("/api/inquiries/" + id + "/structured")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("summary", summary)));
    }

    private ResultActions revise(String id, String note) throws Exception {
        return send(post("/api/inquiries/" + id + "/revision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("note", note)));
    }

    private ResultActions reopen(String id) throws Exception {
        return send(post("/api/inquiries/" + id + "/reopen"));
    }

    private static final String EMPTY_ANSWERS = "{\"answers\":[]}";

    /** 回答（空）から整形結果の確定まで進め、ANSWERING の問い合わせの id を返す。 */
    private String toAnswering(String utterance) throws Exception {
        String id = idOf(create(utterance));
        answers(id, EMPTY_ANSWERS).andExpect(status().isOk());
        confirm(id).andExpect(status().isOk()).andExpect(jsonPath("$.step").value("ANSWERING"));
        return id;
    }

    /** SSE を最後まで読み、本文を返す。 */
    private String stream(String id) throws Exception {
        MvcResult started = mvc.perform(get("/api/inquiries/" + id + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(15_000);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        // SSE の Content-Type には charset がないため、UTF-8 として読む
        return started.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from inquiries where id = ?::uuid", id);
    }

    // ---- (a) 種別4経路 ----

    @Test
    void 仕様確認は本人が解決と判定すると自己解決で終わる() throws Exception {
        String created = create(SPEC);
        String id = idOf(created);
        assertThat((String) JsonPath.read(created, "$.step")).isEqualTo("TRIAGED");
        assertThat((String) JsonPath.read(created, "$.category")).isEqualTo("SPEC_QUESTION");
        assertThat((List<?>) JsonPath.read(created, "$.questions")).hasSize(2);
        assertThat((Boolean) JsonPath.read(created, "$.mock")).isTrue();

        answers(id, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"求人管理画面\"},{\"questionId\":\"q2\",\"answer\":null}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.structured.goal").value(SPEC))
                .andExpect(jsonPath("$.answer").doesNotExist());
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));

        String done = doneView(stream(id));
        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("IN_PROGRESS");
        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isTrue();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).hasSize(2);
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.findings")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.notFound")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.nextChecks")).isNotEmpty();
        assertThat(notifier.notices()).isEmpty();

        send(post("/api/inquiries/" + id + "/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("SELF_RESOLVED"));
        assertThat(notifier.notices()).isEmpty();
        assertThat(row(id).get("filed_at")).isNull();
    }

    @Test
    void 仕様確認は未解決と判定すると起票され通知と記録が残る() throws Exception {
        String id = toAnswering(SPEC);
        stream(id);

        send(post("/api/inquiries/" + id + "/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(false));

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.inquiryId().toString()).isEqualTo(id);
            assertThat(n.sentImmediately()).isFalse();
            assertThat(n.structured().goal()).isEqualTo(SPEC);
            assertThat(n.memo().findings()).isNotEmpty();
        });
        Map<String, Object> row = row(id);
        assertThat(row.get("filed_at")).isNotNull();
        assertThat(row.get("step")).isEqualTo("CLOSED");
        assertThat(row.get("mock")).isEqualTo(true);
        assertThat(row.get("asker_id")).isEqualTo("local-user");
        // triage / structure / answer の3回分を追記している
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(usages) from inquiries where id = ?::uuid", Integer.class, id))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "select usages->2->>'step' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("answer");
    }

    @Test
    void 不具合の疑いは0次回答の完了と同時に起票される() throws Exception {
        String id = toAnswering(BUG);

        String done = doneView(stream(id));

        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("FILED");
        assertThat((Boolean) JsonPath.read(done, "$.sentImmediately")).isFalse();
        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).contains("現在の実装では");
        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.category()).isEqualTo(InquiryCategory.SUSPECTED_BUG);
            assertThat(n.memo().nextChecks()).isNotEmpty();
        });
        assertThat(row(id).get("filed_at")).isNotNull();
    }

    @Test
    void 作業依頼は固定3問に答え整形を確定すると0次回答なしで起票される() throws Exception {
        String created = create(WORK);
        String id = idOf(created);
        assertThat((List<String>) JsonPath.read(created, "$.questions[*].id"))
                .containsExactly("wr-what", "wr-when", "wr-target");
        assertThat((List<?>) JsonPath.read(created, "$.questions[0].options")).isEmpty();

        answers(id, "{\"answers\":[{\"questionId\":\"wr-what\",\"answer\":\"延長\"},"
                        + "{\"questionId\":\"wr-when\",\"answer\":\"来週\"},{\"questionId\":\"wr-target\",\"answer\":null}]}")
                .andExpect(jsonPath("$.step").value("STRUCTURED"));
        confirm(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.answer").doesNotExist());

        assertThat(notifier.notices()).singleElement().satisfies(n -> assertThat(n.memo()).isNull());
        // 保存された回答が追加質問に残っている
        assertThat(jdbc.queryForObject(
                        "select follow_ups->0->>'answer' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("延長");
    }

    @Test
    void 障害で止まっていると答えると案内して終わる() throws Exception {
        String created = create(INCIDENT);
        String id = idOf(created);
        assertThat((String) JsonPath.read(created, "$.category")).isEqualTo("INCIDENT");
        assertThat((List<String>) JsonPath.read(created, "$.questions[*].id")).containsExactly("incident-check");
        assertThat((List<String>) JsonPath.read(created, "$.questions[0].options")).containsExactly("はい", "いいえ");

        answers(id, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"はい\"}]}")
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("INCIDENT_REDIRECTED"))
                .andExpect(jsonPath("$.incidentGuidance").value(incidentGuidance));
        assertThat(notifier.notices()).isEmpty();
    }

    @Test
    void 障害の質問を答えなければ案内して終わる() throws Exception {
        String id = idOf(create(INCIDENT));

        answers(id, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":null}]}")
                .andExpect(jsonPath("$.outcome").value("INCIDENT_REDIRECTED"));
    }

    @Test
    void 障害で止まっていないと答えると不具合の疑いとして続行する() throws Exception {
        String id = idOf(create(INCIDENT));

        answers(id, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"いいえ\"}]}")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.categoryByAi").value("INCIDENT"))
                .andExpect(jsonPath("$.category").value("SUSPECTED_BUG"))
                .andExpect(jsonPath("$.incidentGuidance").doesNotExist());

        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));
        String done = doneView(stream(id));
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("FILED");
    }

    @Test
    void 種別の変更で追加質問が差し替わる() throws Exception {
        String id = idOf(create(SPEC));

        changeCategory(id, "WORK_REQUEST").andExpect(jsonPath("$.questions[*].id")
                .value(org.hamcrest.Matchers.contains("wr-what", "wr-when", "wr-target")));
        changeCategory(id, "INCIDENT").andExpect(jsonPath("$.questions[0].id").value("incident-check"));
        changeCategory(id, "SUSPECTED_BUG")
                .andExpect(jsonPath("$.questions[*].id").value(org.hamcrest.Matchers.contains("q1", "q2")))
                .andExpect(jsonPath("$.category").value("SUSPECTED_BUG"))
                .andExpect(jsonPath("$.categoryByAi").value("SPEC_QUESTION"));
    }

    @Test
    void AIの判定が作業依頼なら仕様確認へ変えても質問は空になる() throws Exception {
        String id = idOf(create(WORK));

        changeCategory(id, "SPEC_QUESTION").andExpect(jsonPath("$.questions").isEmpty());
    }

    private ResultActions changeCategory(String id, String category) throws Exception {
        return send(put("/api/inquiries/" + id + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("category", category)))
                .andExpect(status().isOk());
    }

    // ---- (b)(c) タグの振る舞い（HTTP経由） ----

    @Test
    void 質問なしタグは追加質問を出さない() throws Exception {
        assertThat((List<?>) JsonPath.read(create("[質問なし] " + SPEC), "$.questions")).isEmpty();
    }

    @Test
    void 判定エラーは503とinquiryIdを返し発話は種別なしで保存される() throws Exception {
        String body = send(post("/api/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("utterance", "[判定エラー] x")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.inquiryId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String id = JsonPath.read(body, "$.inquiryId");

        send(get("/api/inquiries/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("TRIAGED"))
                .andExpect(jsonPath("$.utterance").value("[判定エラー] x"))
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.categoryByAi").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.questions").isEmpty());
        Map<String, Object> row = row(id);
        assertThat(row.get("category_by_ai")).isNull();
        assertThat(row.get("category_confirmed")).isNull();
        assertThat(row.get("mock")).isEqualTo(true);
    }

    @Test
    void 判定に失敗した問い合わせは今すぐ送れて通知される() throws Exception {
        String id = failedTriageId();

        send(post("/api/inquiries/" + id + "/send-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true));
        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.inquiryId().toString()).isEqualTo(id);
            assertThat(n.sentImmediately()).isTrue();
            assertThat(n.utterance()).isEqualTo("[判定エラー] x");
        });
    }

    @Test
    void 判定に失敗した問い合わせの種別変更と回答は409になる() throws Exception {
        String id = failedTriageId();

        send(put("/api/inquiries/" + id + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("category", "SPEC_QUESTION")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        answers(id, EMPTY_ANSWERS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void inquiryIdは判定の失敗以外のエラー本文に含まれない() throws Exception {
        String body = send(get("/api/inquiries/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("inquiryId");
        String conflict = send(post("/api/inquiries/" + idOf(create(SPEC)) + "/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(conflict).doesNotContain("inquiryId");
    }

    private String failedTriageId() throws Exception {
        String body = send(post("/api/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("utterance", "[判定エラー] x")))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.inquiryId");
    }

    @Test
    void 根拠なしタグは根拠なしの回答になり技術メモが残る() throws Exception {
        String done = doneView(stream(toAnswering("[根拠なし] " + SPEC)));

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer.askerExplanation")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.notFound")).isNotEmpty();
    }

    @Test
    void 幽霊出典は照合で落ちて根拠なしになり技術メモと照合結果が残る() throws Exception {
        String id = toAnswering("[幽霊出典] " + SPEC);

        String done = doneView(stream(id));

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer.askerExplanation")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.findings")).isNotEmpty();
        // 画面には出さないが、記録には照合失敗として残す
        assertThat(jdbc.queryForObject(
                        "select citations->0->>'verified' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("false");
        assertThat(row(id).get("answer_grounded")).isEqualTo(false);
    }

    @Test
    void エラータグは失敗を流し段階はANSWERINGのまま今すぐ送れる() throws Exception {
        String id = toAnswering("[エラー] " + SPEC);

        var events = events(stream(id));

        assertFailedLast(events, "AI_UNAVAILABLE");
        send(get("/api/inquiries/" + id)).andExpect(jsonPath("$.step").value("ANSWERING"));
        send(post("/api/inquiries/" + id + "/send-now")).andExpect(jsonPath("$.outcome").value("FILED"));
    }

    @Test
    void 拒否タグはAI_REFUSEDの失敗を流す() throws Exception {
        var events = events(stream(toAnswering("[拒否] " + SPEC)));

        assertFailedLast(events, "AI_REFUSED");
    }

    // ---- (d) 今すぐ送る ----

    @Test
    void 今すぐ送るはどの段階でも起票されて送信済みになる() throws Exception {
        List<String> ids = new ArrayList<>();
        ids.add(idOf(create(SPEC))); // TRIAGED
        String structured = idOf(create(SPEC));
        answers(structured, EMPTY_ANSWERS);
        ids.add(structured); // STRUCTURED
        ids.add(toAnswering(SPEC)); // ANSWERING
        String answered = toAnswering(SPEC);
        stream(answered);
        ids.add(answered); // ANSWERED

        for (String id : ids) {
            send(post("/api/inquiries/" + id + "/send-now"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("CLOSED"))
                    .andExpect(jsonPath("$.outcome").value("FILED"))
                    .andExpect(jsonPath("$.sentImmediately").value(true));
            assertThat(row(id).get("sent_immediately")).isEqualTo(true);
            assertThat(row(id).get("filed_at")).isNotNull();
        }
        assertThat(notifier.notices()).hasSize(4).allSatisfy(n -> assertThat(n.sentImmediately()).isTrue());
        // 整形前に送った分は整形後の問いがなく、元の発話で通知される。回答後に送った分は技術メモを持つ
        assertThat(notifier.notices().get(0).structured()).isNull();
        assertThat(notifier.notices().get(3).memo()).isNotNull();
    }

    // ---- (e) 不正な段階 ----

    @Test
    void 不正な段階の操作は409のINVALID_STATEになる() throws Exception {
        String triaged = idOf(create(SPEC));
        String structured = idOf(create(SPEC));
        answers(structured, EMPTY_ANSWERS);
        String answering = toAnswering(SPEC);

        confirm(triaged).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        send(post("/api/inquiries/" + triaged + "/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isConflict());
        answers(structured, EMPTY_ANSWERS).andExpect(status().isConflict());
        send(put("/api/inquiries/" + structured + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("category", "WORK_REQUEST")))
                .andExpect(status().isConflict());
        confirm(answering).andExpect(status().isConflict());

        send(post("/api/inquiries/" + triaged + "/send-now")).andExpect(status().isOk());
        send(post("/api/inquiries/" + triaged + "/send-now"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        answers(triaged, EMPTY_ANSWERS).andExpect(status().isConflict());
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
        String id = toAnswering(SPEC);
        stream(id);

        var events = events(stream(id));

        assertThat(events).singleElement().satisfies(e -> assertThat(e.getKey()).isEqualTo("failed"));
    }

    // ---- 入力検証・存在確認 ----

    @Test
    void 存在しない問い合わせは404になる() throws Exception {
        String missing = "00000000-0000-0000-0000-000000000000";
        send(get("/api/inquiries/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/inquiries/" + missing + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 不正な入力は400のVALIDATIONになる() throws Exception {
        for (String utterance : List.of("", "   ", "あ".repeat(4001))) {
            send(post("/api/inquiries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("utterance", utterance)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        send(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        send(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());

        String id = idOf(create(SPEC));
        send(put("/api/inquiries/" + id + "/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("category", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        answers(id, "{\"answers\":[{\"questionId\":\"nothing\",\"answer\":\"x\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        send(get("/api/inquiries/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void 不正な確定の本文は400になる() throws Exception {
        String id = idOf(create(SPEC));
        answers(id, EMPTY_ANSWERS);

        confirmSummary(id, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        confirmSummary(id, "   ").andExpect(status().isBadRequest());
        confirmSummary(id, "あ".repeat(2001)).andExpect(status().isBadRequest());
        // 旧形式の5項目は受け付けない
        send(put("/api/inquiries/" + id + "/structured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSystem\":null,\"goal\":\"x\",\"whatHappened\":null,"
                                + "\"expectedResult\":null,\"urgency\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        send(get("/api/inquiries/" + id)).andExpect(jsonPath("$.step").value("STRUCTURED"));
    }

    @Test
    void 本文なしの確定はAIの言い直しをそのまま確定する() throws Exception {
        String id = idOf(create(SPEC));
        String structured = answers(id, EMPTY_ANSWERS)
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String aiSummary = JsonPath.read(structured, "$.structured.summary");

        confirm(id).andExpect(jsonPath("$.structured.summary").value(aiSummary));

        assertThat(jdbc.queryForObject(
                        "select structured_confirmed->>'summary' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo(aiSummary);
    }

    @Test
    void summaryありの確定はsummaryだけを置き換えgoalとタグの振る舞いは保たれる() throws Exception {
        String utterance = "[幽霊出典] " + SPEC;
        String id = idOf(create(utterance));
        answers(id, EMPTY_ANSWERS);

        confirmSummary(id, "自分で書き直した文章")
                .andExpect(jsonPath("$.step").value("ANSWERING"))
                .andExpect(jsonPath("$.structured.summary").value("自分で書き直した文章"))
                .andExpect(jsonPath("$.structured.goal").value(utterance));
        String done = doneView(stream(id));

        // goal が変わらないため、タグ（幽霊出典）の振る舞いが最後まで保たれる
        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat(jdbc.queryForObject(
                        "select structured_by_ai->>'summary' from inquiries where id = ?::uuid", String.class, id))
                .isNotEqualTo("自分で書き直した文章");
    }

    // ---- 言い直し（summary） ----

    @Test
    void summaryは種別ごとの形で作られタグを含まず回答が末尾に足される() throws Exception {
        String spec = idOf(create("[質問なし] 期限の仕様"));
        answers(spec, EMPTY_ANSWERS)
                .andExpect(jsonPath("$.structured.summary").value("「期限の仕様」について、いまの仕組みがどうなっているかを知りたい。"));

        String withAnswers = idOf(create("期限の仕様"));
        answers(withAnswers, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"求人管理画面\"},"
                        + "{\"questionId\":\"q2\",\"answer\":null}]}")
                .andExpect(jsonPath("$.structured.summary")
                        .value("「期限の仕様」について、いまの仕組みがどうなっているかを知りたい。（どの画面での話ですか: 求人管理画面）"));

        String bug = idOf(create("[質問なし] [遅延] 表示がおかしい"));
        answers(bug, EMPTY_ANSWERS)
                .andExpect(jsonPath("$.structured.summary")
                        .value("「表示がおかしい」という状況が起きている。これが意図した動きかどうかを確認したい。"))
                .andExpect(jsonPath("$.structured.goal").value("[質問なし] [遅延] 表示がおかしい"));

        String work = idOf(create(WORK));
        answers(work, "{\"answers\":[{\"questionId\":\"wr-what\",\"answer\":\"期間の延長\"},"
                        + "{\"questionId\":\"wr-when\",\"answer\":\"来週\"},{\"questionId\":\"wr-target\",\"answer\":null}]}")
                .andExpect(jsonPath("$.structured.summary").value("期間の延長を来週までに対応してほしい。"));
        String work2 = idOf(create(WORK));
        answers(work2, "{\"answers\":[{\"questionId\":\"wr-what\",\"answer\":\"期間の延長\"},"
                        + "{\"questionId\":\"wr-target\",\"answer\":\"求人A\"}]}")
                .andExpect(jsonPath("$.structured.summary").value("期間の延長を対応してほしい。対象は求人A。"));
    }

    // ---- 作り直し（5a）・追加質問へ戻る（5b） ----

    @Test
    void 作り直しで補足が入り残り回数が減り3回目は409になり一言が保存される() throws Exception {
        String id = idOf(create(SPEC));
        String base = JsonPath.read(
                answers(id, EMPTY_ANSWERS)
                        .andExpect(jsonPath("$.revisionsLeft").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.structured.summary");

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
                                + " where id = ?::uuid and u->>'step' = 'revise'",
                        Integer.class,
                        id))
                .isEqualTo(2);
        // 使い切っても、そのまま確定して先へ進める
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));
    }

    @Test
    void 作り直しの一言の検証() throws Exception {
        String id = idOf(create(SPEC));
        answers(id, EMPTY_ANSWERS);

        revise(id, "").andExpect(status().isBadRequest());
        revise(id, "あ".repeat(1001)).andExpect(status().isBadRequest());
        send(get("/api/inquiries/" + id)).andExpect(jsonPath("$.revisionsLeft").value(2));
    }

    @Test
    void 追加質問へ戻ると同じ質問が返りもう一度回答して確定まで進める() throws Exception {
        String created = create(SPEC);
        String id = idOf(created);
        List<String> questionIds = JsonPath.read(created, "$.questions[*].id");
        answers(id, EMPTY_ANSWERS);
        revise(id, "違う").andExpect(jsonPath("$.revisionsLeft").value(1));

        reopen(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("TRIAGED"))
                .andExpect(jsonPath("$.structured").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.questions[*].id").value(org.hamcrest.Matchers.contains(questionIds.toArray())))
                .andExpect(jsonPath("$.revisionsLeft").value(1));

        answers(id, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"その他\"}]}")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.revisionsLeft").value(1))
                .andExpect(jsonPath("$.structured.summary").value(org.hamcrest.Matchers.containsString("その他")));
        confirm(id).andExpect(jsonPath("$.step").value("ANSWERING"));
    }

    @Test
    void 不正な段階の作り直しと追加質問へ戻る操作は409になる() throws Exception {
        String triaged = idOf(create(SPEC));
        revise(triaged, "x").andExpect(status().isConflict());
        reopen(triaged).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        String answering = toAnswering(SPEC);
        revise(answering, "x").andExpect(status().isConflict());
        reopen(answering).andExpect(status().isConflict());

        String closed = idOf(create(SPEC));
        send(post("/api/inquiries/" + closed + "/send-now"));
        revise(closed, "x").andExpect(status().isConflict());
        reopen(closed).andExpect(status().isConflict());
    }

    @Test
    void configはモック動作中を返す() throws Exception {
        send(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.filedGuidance").value(filedGuidance));
        // 設定ファイルの日本語が文字化けせずに読めていること
        assertThat(filedGuidance).startsWith("担当者が内容を確認して連絡します。");
        assertThat(incidentGuidance).startsWith("障害の連絡は専用の経路");
    }

    // ---- (i) SSE ----

    @Test
    void SSEは進捗を流してから完了を流す() throws Exception {
        String id = toAnswering(SPEC);

        String body = stream(id);

        var events = events(body);
        assertThat(events.stream().map(Map.Entry::getKey).toList())
                .startsWith("progress", "progress", "progress")
                .endsWith("done");
        assertThat(events.stream().filter(e -> e.getKey().equals("done")).count()).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.getKey().equals("progress")).count()).isBetween(3L, 4L);
        assertThat((String) JsonPath.read(events.get(0).getValue(), "$.message")).isNotBlank();
        assertThat((String) JsonPath.read(events.get(events.size() - 1).getValue(), "$.step")).isEqualTo("ANSWERED");
    }
}
