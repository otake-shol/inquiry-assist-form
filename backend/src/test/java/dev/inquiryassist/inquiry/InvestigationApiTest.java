package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 調査の状況と、裏での調査（契約書「調査の状況と、裏での調査」「担当者に渡す」）を API から確かめる。調査の1段階を300ミリ秒に
 * して、調べている最中に操作できるようにする（時間に頼る確認は、固定の待ちではなく、状況が変わるまで待つ）。
 */
@SpringBootTest(properties = {"inquiry.mock.slow-step-delay=300ms", "inquiry.operator.heartbeat-interval=PT0.2S"})
class InvestigationApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";

    @Autowired
    InvestigationRunner runner;

    // ---- 補助 ----

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

    private static String doneOf(String streamBody) {
        return events(streamBody).stream().filter(e -> e.getKey().equals("done")).findFirst().orElseThrow().getValue();
    }

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private String investigationStatus(String id) {
        return (String) row(id).get("investigation_status");
    }

    private void awaitStatus(String id, String expected) throws Exception {
        assertThat(waitFor(() -> expected.equals(investigationStatus(id)), 15_000)).as("調査の状況が " + expected + " にならない: " + id).isTrue();
    }

    private void revise(String id, String note) throws Exception {
        postJson("/api/inquiries/" + id + "/revision", json("note", note)).andExpect(status().isOk());
    }

    private void operatorReply(String id, String kind, String body) throws Exception {
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", kind, "body", body)))
                .andExpect(status().isOk());
    }

    /** 本人が待つ調査（[遅延] で3段階）を別のスレッドで走らせ始め、調べている最中になるまで待つ。 */
    private CompletableFuture<String> startStreaming(String id) throws Exception {
        CompletableFuture<String> streaming = CompletableFuture.supplyAsync(() -> {
            try {
                return stream(id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitStatus(id, "RUNNING");
        return streaming;
    }

    // ---- 種類ごとの経路 ----

    @Test
    void 根拠がない件と的外れの件は自動で担当者に渡り_調べた結果は本人にも見える() throws Exception {
        List<String> filed = new ArrayList<>();
        for (String tag : List.of("[根拠なし]", "[的外れ]")) {
            String id = toAnswering(tag + " " + HOW_TO, null);
            filed.add(id);
            assertThat((String) JsonPath.read(view(id), "$.type.id")).isEqualTo("HOW_TO");

            String done = doneOf(stream(id));

            assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("HANDLING");
            assertThat((String) JsonPath.read(done, "$.turn")).isEqualTo("OPERATOR");
            assertThat((String) JsonPath.read(done, "$.handoverReason")).isEqualTo("NO_ANSWER");
            assertThat((Object) JsonPath.read(done, "$.answer")).isNotNull();
            assertThat((Boolean) JsonPath.read(done, "$.sentImmediately")).isFalse();
            String detail = detail(id);
            assertThat((String) JsonPath.read(detail, "$.handoverReason")).isEqualTo("NO_ANSWER");
            assertThat((String) JsonPath.read(detail, "$.investigation.status")).isEqualTo("DONE");
            assertThat((String) JsonPath.read(detail, "$.investigation.audience")).isEqualTo("ASKER");
            assertThat((List<String>) JsonPath.read(detail, "$.history[-1:].event")).containsExactly("HANDED_OVER");
            assertThat((List<String>) JsonPath.read(detail, "$.history[-1:].actor")).containsExactly("AI");
        }
        // 初めて渡したので、どちらも担当者に通知される（通知の一覧は、ほかのテストの分も含むので、この2件で確かめる）
        assertThat(notifier.notices()).extracting(n -> n.inquiryId().toString()).containsAll(filed);
    }

    @Test
    void 根拠があり答えていれば従来どおり本人が確かめる() throws Exception {
        String id = toAnswering(HOW_TO, null);

        String done = doneOf(stream(id));

        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat((Object) JsonPath.read(done, "$.handoverReason")).isNull();
        assertThat((Object) JsonPath.read(done, "$.answer")).isNotNull();
        assertThat(investigationStatus(id)).isEqualTo("DONE");
        assertThat(row(id).get("investigation_audience")).isEqualTo("ASKER");
    }

    @Test
    void 渡した理由は経路ごとに入り_聞き返しへの返事では変わらない() throws Exception {
        // ROUTE: おかしいかもしれないは常に渡す
        String wrong = toAnswering("表示がおかしい", null);
        assertThat((String) JsonPath.read(doneOf(stream(wrong)), "$.handoverReason")).isEqualTo("ROUTE");
        // ROUTE: 変えてほしいは確定と同時に渡す
        String change = toStructured("要望です", null);
        confirm(change).andExpect(jsonPath("$.handoverReason").value("ROUTE"));
        // UNRESOLVED: 解決していないと答えた
        String unresolved = toAnswering(HOW_TO, null);
        stream(unresolved);
        resolve(unresolved, false).andExpect(jsonPath("$.handoverReason").value("UNRESOLVED"));
        // SENT_NOW、そして聞き返しへの返事では変わらない
        String sent = filedNow(HOW_TO, null);
        assertThat((String) JsonPath.read(view(sent), "$.handoverReason")).isEqualTo("SENT_NOW");
        operatorReply(sent, "ASK", "詳しく教えてください");
        mvc.perform(post("/api/inquiries/" + sent + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "こうです")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.handoverReason").value("SENT_NOW"));
        // REOPENED: 終わった件に追記した
        String declined = filedNow(HOW_TO, null);
        mvc.perform(post("/api/operator/inquiries/" + declined + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reasonKind", "OUT_OF_SCOPE", "reason", "対象外です")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + declined + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "もう一度お願いします")))
                .andExpect(jsonPath("$.handoverReason").value("REOPENED"));
        // 渡したことがなければ null
        assertThat((Object) JsonPath.read(view(idOf(start())), "$.handoverReason")).isNull();
    }

    // ---- 答えを待たずに送る ----

    @Test
    void 調べている最中に送ると調査は続き_結果は担当者だけに届き担当者側は未読に戻る() throws Exception {
        String id = toAnswering("[遅延] " + HOW_TO, null);
        CompletableFuture<String> streaming = startStreaming(id);

        mvc.perform(post("/api/inquiries/" + id + "/send-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.handoverReason").value("SENT_NOW"));
        // 担当者がすぐ既読にしても、結果が届くと未読に戻る
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        assertThat((String) JsonPath.read(detail(id), "$.investigation.status")).isEqualTo("RUNNING");

        String done = doneOf(streaming.get(15, TimeUnit.SECONDS));
        awaitStatus(id, "DONE");

        // 待っていた画面には、本人向けの答えがない
        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((Object) JsonPath.read(done, "$.answer")).isNull();
        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.investigation.audience")).isEqualTo("OPERATOR");
        assertThat((Boolean) JsonPath.read(detail, "$.investigation.unconfirmedQuestion")).isFalse();
        assertThat((Object) JsonPath.read(detail, "$.investigation.finishedAt")).isNotNull();
        assertThat((Object) JsonPath.read(detail, "$.answer.askerExplanation")).isNotNull();
        assertThat((Boolean) JsonPath.read(detail, "$.read")).isFalse();
        assertThat((Object) JsonPath.read(view(id), "$.answer")).isNull();
        assertThat(usageSteps(id)).contains("investigator", "explainer");
    }

    @Test
    void 調べていない間に送ると裏で調査が始まる() throws Exception {
        String id = toAnswering(HOW_TO, null);
        assertThat(investigationStatus(id)).isEqualTo("NOT_RUN");

        sendNow(id).andExpect(status().isOk());

        assertThat(investigationStatus(id)).isEqualTo("DONE");
        assertThat(row(id).get("investigation_audience")).isEqualTo("OPERATOR");
        assertThat((Object) JsonPath.read(detail(id), "$.answer.memo.findings")).isNotNull();
    }

    @Test
    void 調査が失敗した後に送ると裏で調査が始まり_失敗しても本人向けには何も変わらない() throws Exception {
        String id = toAnswering("[エラー] " + HOW_TO, null);
        stream(id);
        assertThat(investigationStatus(id)).isEqualTo("FAILED");
        Instant firstStart = ((Timestamp) row(id).get("investigation_started_at")).toInstant();

        sendNow(id).andExpect(status().isOk());

        // 裏で、もう一度調べて、また失敗した
        assertThat(investigationStatus(id)).isEqualTo("FAILED");
        assertThat(((Timestamp) row(id).get("investigation_started_at")).toInstant()).isAfter(firstStart);
        assertThat(usageSteps(id)).endsWith("restater", "investigator", "investigator");
        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.investigation.status")).isEqualTo("FAILED");
        assertThat((Object) JsonPath.read(detail, "$.investigation.audience")).isNull();
        assertThat((Object) JsonPath.read(detail, "$.investigation.finishedAt")).isNotNull();
        String asker = view(id);
        assertThat((Object) JsonPath.read(asker, "$.answer")).isNull();
        assertThat((String) JsonPath.read(asker, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((Boolean) JsonPath.read(asker, "$.unreadReply")).isFalse();
    }

    @Test
    void 裏での調査が失敗しても担当者側の未読には戻さない() throws Exception {
        String id = toAnswering("[エラー] " + HOW_TO, null);
        mvc.perform(post("/api/inquiries/" + id + "/send-now")).andExpect(status().isOk());
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        awaitStatus(id, "FAILED");

        assertThat(row(id).get("read_at")).isNotNull();
    }

    @Test
    void 答えが既にある件を送っても調査は始めず_結果は本人向けのまま() throws Exception {
        String id = toAnswering(HOW_TO, null);
        stream(id);
        Instant startedAt = ((Timestamp) row(id).get("investigation_started_at")).toInstant();

        sendNow(id).andExpect(status().isOk());

        assertThat(investigationStatus(id)).isEqualTo("DONE");
        assertThat(row(id).get("investigation_audience")).isEqualTo("ASKER");
        assertThat(((Timestamp) row(id).get("investigation_started_at")).toInstant()).isEqualTo(startedAt);
        assertThat((Object) JsonPath.read(view(id), "$.answer")).isNotNull();
        assertThat(usageSteps(id)).endsWith("restater", "investigator", "explainer", "advisor");
    }

    @Test
    void 言い直しを作れなかった件と合っていないままの件を送っても調べない() throws Exception {
        String failed = idOf(create("[言い直しエラー] " + HOW_TO));
        unknown(failed, "system");
        unknown(failed, "q1");
        sendNow(failed).andExpect(status().isOk());
        assertThat(investigationStatus(failed)).isEqualTo("NOT_RUN");

        String unconfirmed = toStructured(HOW_TO, null);
        revise(unconfirmed, "1回目");
        revise(unconfirmed, "2回目");
        sendNow(unconfirmed).andExpect(status().isOk()).andExpect(jsonPath("$.awaiting").value("HANDLING"));
        assertThat(investigationStatus(unconfirmed)).isEqualTo("NOT_RUN");
        assertThat(row(unconfirmed).get("investigation_started_at")).isNull();
    }

    // ---- 業務が止まっている件 ----

    @Test
    void 業務が止まっている件は渡した後に裏で調べ_本人が確かめていない問いの印が付く() throws Exception {
        String id = idOf(create("求人管理画面の求人の掲載が使えない", "BLOCKED"));
        finishIntake(id, null);

        awaitStatus(id, "DONE");
        assertThat(row(id).get("investigation_audience")).isEqualTo("OPERATOR");
        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.handoverReason")).isEqualTo("BLOCKED");
        assertThat((Boolean) JsonPath.read(detail, "$.investigation.unconfirmedQuestion")).isTrue();
        assertThat((Object) JsonPath.read(detail, "$.answer.memo")).isNotNull();
        assertThat((Object) JsonPath.read(view(id), "$.answer")).isNull();
    }

    @Test
    void 業務が止まっていても_変えてほしいと言い直しの失敗では調べない() throws Exception {
        String change = idOf(create("要望です 求人管理画面の求人の掲載", "BLOCKED"));
        finishIntake(change, null);
        assertThat((String) JsonPath.read(view(change), "$.awaiting")).isEqualTo("HANDLING");

        String failed = idOf(create("[言い直しエラー] 画面が真っ白", "BLOCKED"));
        finishIntake(failed, null);
        assertThat((String) JsonPath.read(view(failed), "$.awaiting")).isEqualTo("HANDLING");

        assertThat(usageSteps(change)).doesNotContain("investigator");
        assertThat(usageSteps(failed)).doesNotContain("investigator");
        assertThat(investigationStatus(change)).isEqualTo("NOT_RUN");
        assertThat(investigationStatus(failed)).isEqualTo("NOT_RUN");
    }

    // ---- 終わっている件 ----

    @Test
    void 終わっている件に結果が届いても保存だけで担当者側を未読にしない() throws Exception {
        String id = toAnswering("[遅延] " + HOW_TO, null);
        CompletableFuture<String> streaming = startStreaming(id);
        mvc.perform(post("/api/inquiries/" + id + "/send-now")).andExpect(status().isOk());
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + id + "/withdraw")).andExpect(status().isOk());

        streaming.get(15, TimeUnit.SECONDS);
        awaitStatus(id, "DONE");

        assertThat(row(id).get("investigation_audience")).isEqualTo("OPERATOR");
        assertThat(row(id).get("read_at")).isNotNull();
        assertThat((String) JsonPath.read(detail(id), "$.ending")).isEqualTo("WITHDRAWN");
        assertThat((Object) JsonPath.read(detail(id), "$.answer")).isNotNull();
    }

    // ---- 起動時 ----

    @Test
    void 起動時に調べている最中のまま残った調査は失敗になる() throws Exception {
        String stuck = idOf(start());
        String idle = idOf(start());
        jdbc.update(
                "update inquiries set investigation_status = 'RUNNING', investigation_started_at = now() where id = ?::uuid",
                stuck);
        Instant bootTime = Instant.parse("2026-12-01T00:00:00Z");

        int failed = runner.failRunning(bootTime);

        assertThat(failed).isGreaterThanOrEqualTo(1);
        assertThat(investigationStatus(stuck)).isEqualTo("FAILED");
        assertThat(((Timestamp) row(stuck).get("investigation_finished_at")).toInstant()).isEqualTo(bootTime);
        assertThat(investigationStatus(idle)).isEqualTo("NOT_RUN");
        assertThat(row(idle).get("investigation_finished_at")).isNull();
    }
}
