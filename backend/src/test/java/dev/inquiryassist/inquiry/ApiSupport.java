package dev.inquiryassist.inquiry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.TestcontainersConfiguration;
import dev.inquiryassist.TestcontainersConfiguration.RecordingFilingNotifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * API のテストが共有する、HTTP 呼び出しの補助。入口の質問は「押して答える」道と「文章で書く」道の両方を、
 * 1問ずつ API を呼んで進める（受付などのスキルを直接呼ばない）。
 */
@SpringBootTest(properties = {"inquiry.mock.slow-step-delay=100ms", "inquiry.operator.heartbeat-interval=PT0.2S"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class ApiSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingFilingNotifier notifier;

    Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from inquiries where id = ?::uuid", id);
    }

    static String idOf(String body) {
        return JsonPath.read(body, "$.id");
    }

    static String text(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** キーと値の組から JSON を作る。値が null のキーは出力に含めない。 */
    static String json(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return JSON.writeValueAsString(map);
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 作成 ----

    /** 文章で始める。201 を確かめ、InquiryView を返す。 */
    String create(String text) throws Exception {
        return text(postJson("/api/inquiries", json("text", text)).andExpect(status().isCreated()));
    }

    /** 文章と緊急度で始める。 */
    String create(String text, String urgency) throws Exception {
        return text(postJson("/api/inquiries", json("text", text, "urgency", urgency)).andExpect(status().isCreated()));
    }

    /** 本文 {@code {}} で始める（押して答える道）。AI は呼ばない。 */
    String start() throws Exception {
        return text(postJson("/api/inquiries", "{}").andExpect(status().isCreated()));
    }

    // ---- 入口の質問 ----

    ResultActions intakeReply(String id, String body) throws Exception {
        return postJson("/api/inquiries/" + id + "/intake/answers", body);
    }

    ResultActions pick(String id, String questionId, String optionId) throws Exception {
        return intakeReply(id, json("questionId", questionId, "optionId", optionId));
    }

    ResultActions unknown(String id, String questionId) throws Exception {
        return intakeReply(id, json("questionId", questionId, "unknown", true));
    }

    ResultActions write(String id, String questionId, String text) throws Exception {
        return intakeReply(id, json("questionId", questionId, "text", text));
    }

    ResultActions skip(String id, String questionId) throws Exception {
        return intakeReply(id, json("questionId", questionId, "skipped", true));
    }

    ResultActions back(String id, String questionId) throws Exception {
        return postJson("/api/inquiries/" + id + "/intake/back", json("questionId", questionId));
    }

    ResultActions intakeText(String id, String text) throws Exception {
        return postJson("/api/inquiries/" + id + "/intake/text", json("text", text));
    }

    ResultActions compose(String id) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/intake/compose"));
    }

    ResultActions urgency(String id, String urgency) throws Exception {
        return mvc.perform(put("/api/inquiries/" + id + "/urgency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("urgency", urgency)));
    }

    String view(String id) throws Exception {
        return text(mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isOk()));
    }

    /**
     * 次の質問がなくなるまで、「わからない」で答え続ける。システムの質問だけは {@code systemId} を選ぶ
     * （null なら「わからない」）。補足は、書かずに進めるなら進み、進めなければ短い文章を書く。
     * 最後の応答（問いを組み立てた後の InquiryView）を返す。
     */
    String finishIntake(String id, String systemId) throws Exception {
        String current = view(id);
        for (int guard = 0; guard < 20; guard++) {
            if (JsonPath.read(current, "$.intake.next") == null) {
                return current;
            }
            String questionId = JsonPath.read(current, "$.intake.next.questionId");
            String kind = JsonPath.read(current, "$.intake.next.kind");
            ResultActions action;
            if ("SYSTEM".equals(kind) && systemId != null) {
                action = pick(id, questionId, systemId);
            } else if ("NOTE".equals(kind)) {
                boolean canSkip = JsonPath.read(current, "$.intake.next.canSkip");
                action = canSkip ? skip(id, questionId) : write(id, questionId, "状況の説明");
            } else {
                action = unknown(id, questionId);
            }
            current = text(action.andExpect(status().isOk()));
        }
        throw new IllegalStateException("入口が終わらない: " + current);
    }

    /** 文章で始め、残りの質問に「わからない」と答えて、問いを組み立てた（STRUCTURED の）問い合わせの id を返す。 */
    String toStructured(String text, String systemId) throws Exception {
        String id = idOf(create(text));
        String last = finishIntake(id, systemId);
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(last, "$.awaiting")).isEqualTo("QUESTION_CONFIRMATION");
        return id;
    }

    // ---- 確定以降 ----

    ResultActions confirm(String id) throws Exception {
        return mvc.perform(put("/api/inquiries/" + id + "/structured").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    /**
     * 答えを待たずに送る。調査を待っている間に送ると、AI の調査が裏で続くので、その保存が済むまで待つ
     * （続けて担当者の操作をするテストが、保存と競合して不安定にならないように）。
     */
    ResultActions sendNow(String id) throws Exception {
        ResultActions sent = mvc.perform(post("/api/inquiries/" + id + "/send-now"));
        awaitInvestigationIdle(id);
        return sent;
    }

    /**
     * 調査と、担当者向けの AI の案の作成が、どちらも走っていない状態（{@code RUNNING} でない）になるまで待つ。
     * 案の作成は、調査の結果の保存と同時に始まる印が付くので、調査が終わった直後に「どちらも走っていない」と誤って見えることはない。
     */
    void awaitInvestigationIdle(String id) throws InterruptedException {
        boolean idle = waitFor(
                () -> {
                    Map<String, Object> row = row(id);
                    return !"RUNNING".equals(row.get("investigation_status"))
                            && !"RUNNING".equals(row.get("advice_status"));
                },
                15_000);
        org.assertj.core.api.Assertions.assertThat(idle).as("調査か案の作成が終わらない: " + id).isTrue();
    }

    /** 結果を確かめる。解決していないと答えると担当者に渡り、AI の案が裏で作られるので、終わるまで待つ。 */
    ResultActions resolve(String id, boolean resolved) throws Exception {
        ResultActions actions = mvc.perform(post("/api/inquiries/" + id + "/resolution")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolved\":" + resolved + "}"));
        awaitInvestigationIdle(id);
        return actions;
    }

    /** 文章で始めて入口を終え、言い直しを確定した、ANSWERING の問い合わせの id を返す。 */
    String toAnswering(String text, String systemId) throws Exception {
        String id = toStructured(text, systemId);
        confirm(id).andExpect(status().isOk());
        return id;
    }

    /**
     * 文章で始めて入口を終え、言い直しを確定し、答えを待たずに担当者へ送る。送った問い合わせの id を返す。
     * 調査は言い直しの確定の時点で API が始めるので、送るまでの間に終わらないよう、調査を遅らせる印（{@code [遅延]}）を文章に添える。
     */
    String filedNow(String text, String systemId) throws Exception {
        String id = toAnswering(text.contains("[遅延]") ? text : text + " [遅延]", systemId);
        sendNow(id).andExpect(status().isOk());
        return id;
    }

    /** 0次回答の SSE を最後まで読み、本文を返す。担当者に渡った場合は、裏の案の作成が終わるまで待つ。 */
    String stream(String id) throws Exception {
        String body = streamOnly(id);
        // 担当者に渡った場合は、続けて案の作成が裏で走る。続く操作と保存が重ならないよう、終わるまで待つ
        awaitInvestigationIdle(id);
        return body;
    }

    /** 0次回答の SSE を最後まで読み、本文を返す。裏の処理（調査、案の作成）は待たない。 */
    String streamOnly(String id) throws Exception {
        MvcResult started = mvc.perform(get("/api/inquiries/" + id + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(15_000);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        return started.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 利用量に記録されたスキルの識別子を、呼んだ順に返す。裏での調査が走っていれば、終わるまで待つ。 */
    List<String> usageSteps(String id) {
        try {
            awaitInvestigationIdle(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return jdbc.queryForList(
                "select u->>'step' from inquiries, jsonb_array_elements(usages) with ordinality as t(u, ord)"
                        + " where id = ?::uuid order by ord",
                String.class,
                id);
    }

    /** 条件が満たされるまで最大 timeoutMillis 待つ。 */
    static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
