package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 本人が待つ調査の時間切れ（契約書「6. answer-stream」「調査の状況と、裏での調査」）を確かめる。調査は3段階で約2秒かかり
 * （1段階700ミリ秒）、時間切れは300ミリ秒にして、時間切れの後も調査が裏で続くことを確かめる。
 */
@SpringBootTest(
        properties = {
            "inquiry.mock.slow-step-delay=700ms",
            "inquiry.rules.answer-timeout=300ms",
            "inquiry.operator.heartbeat-interval=PT0.2S"
        })
class InvestigationTimeoutApiTest extends ApiSupport {

    private static String doneOf(String streamBody) {
        String[] lines = streamBody.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("event:done")) {
                return lines[i + 1].substring("data:".length()).trim();
            }
        }
        throw new IllegalStateException("done がない: " + streamBody);
    }

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    @Test
    void 時間切れになると担当者に渡して閉じ_調査は続き_結果は担当者だけに届く() throws Exception {
        String id = toAnswering("[遅延] 使い方を教えて", null);

        String done = doneOf(streamOnly(id));

        // 時間切れ: 担当者に渡して done。答えはまだない
        assertThat((String) JsonPath.read(done, "$.phase")).isEqualTo("KNOW");
        assertThat((String) JsonPath.read(done, "$.turn")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(done, "$.handoverReason")).isEqualTo("TIMEOUT");
        assertThat((Boolean) JsonPath.read(done, "$.sentImmediately")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer")).isNull();
        String early = detail(id);
        assertThat((String) JsonPath.read(early, "$.investigation.status")).isEqualTo("RUNNING");
        assertThat((List<String>) JsonPath.read(early, "$.history[-1:].event")).containsExactly("HANDED_OVER");
        assertThat((List<String>) JsonPath.read(early, "$.history[-1:].actor")).containsExactly("TIME");
        // 初めて渡したので、通知が働く
        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.inquiryId().toString()).isEqualTo(id);
            assertThat(n.sentImmediately()).isFalse();
        });
        // 担当者がすぐ既読にしても、調査が終われば未読に戻る
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());

        awaitInvestigationIdle(id);

        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.investigation.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(detail, "$.investigation.audience")).isEqualTo("OPERATOR");
        assertThat((Object) JsonPath.read(detail, "$.answer.askerExplanation")).isNotNull();
        assertThat((Boolean) JsonPath.read(detail, "$.read")).isFalse();
        assertThat((String) JsonPath.read(detail, "$.handoverReason")).isEqualTo("TIMEOUT");
        // 本人向けには、答えを見せない
        String asker = view(id);
        assertThat((Object) JsonPath.read(asker, "$.answer")).isNull();
        assertThat((String) JsonPath.read(asker, "$.awaiting")).isEqualTo("HANDLING");
        // 渡した後の状態は、結果が届いても変わらない（履歴に調査の出来事は入らない）
        assertThat((List<String>) JsonPath.read(detail, "$.history[*].event")).endsWith("QUESTION_CONFIRMED", "HANDED_OVER");
    }

    @Test
    void 時間切れの後にもう一度ストリームを開いても調べ直さない() throws Exception {
        String id = toAnswering("[遅延] 使い方を教えて", null);
        stream(id);

        String body = mvc.perform(get("/api/inquiries/" + id + "/answer-stream")
                        .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        awaitInvestigationIdle(id);

        // 開き直しは調査を待つ状態でないので、失敗（INVALID_STATE）で閉じる。調査が2重に走らない
        assertThat(usageSteps(id)).endsWith("restater", "investigator", "explainer", "advisor");
        assertThat(body).doesNotContain("event:done");
    }

    @Test
    void 時間内に終わる調査は従来どおり() throws Exception {
        String id = toAnswering("使い方を教えて", null);

        String done = doneOf(stream(id));

        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat((Object) JsonPath.read(done, "$.handoverReason")).isNull();
        assertThat((Object) JsonPath.read(done, "$.answer")).isNotNull();
        assertThat(post("/api/inquiries/" + id + "/messages")).isNotNull();
    }
}
