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

/** 対象システムとオペレータ通知のテストが共有する、HTTP 呼び出しの補助。 */
@SpringBootTest(properties = {"inquiry.mock.slow-step-delay=50ms", "inquiry.operator.heartbeat-interval=PT0.2S"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class ApiSupport {

    static final String EMPTY_ANSWERS = "{\"answers\":[]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingFilingNotifier notifier;

    java.util.Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from inquiries where id = ?::uuid", id);
    }

    static String idOf(String body) {
        return JsonPath.read(body, "$.id");
    }

    static String text(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** systemId が null なら本文に含めない。 */
    String create(String utterance, String systemId) throws Exception {
        String body = systemId == null
                ? "{\"utterance\":\"" + utterance + "\"}"
                : "{\"utterance\":\"" + utterance + "\",\"systemId\":\"" + systemId + "\"}";
        return text(mvc.perform(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()));
    }

    ResultActions answers(String id, String body) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/answers").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions confirm(String id) throws Exception {
        return mvc.perform(put("/api/inquiries/" + id + "/structured").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    ResultActions sendNow(String id) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/send-now"));
    }

    ResultActions resolve(String id, boolean resolved) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/resolution")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolved\":" + resolved + "}"));
    }

    /** 回答（空）→確定まで進め、ANSWERING の問い合わせの id を返す。 */
    String toAnswering(String utterance, String systemId) throws Exception {
        String id = idOf(create(utterance, systemId));
        answers(id, EMPTY_ANSWERS).andExpect(status().isOk());
        confirm(id).andExpect(status().isOk());
        return id;
    }

    /** 0次回答の SSE を最後まで読み、本文を返す。 */
    String stream(String id) throws Exception {
        MvcResult started = mvc.perform(get("/api/inquiries/" + id + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(15_000);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        return started.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
