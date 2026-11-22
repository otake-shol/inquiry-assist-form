package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.skill.RestaterSkill;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 言い直しが拒否や一時的な失敗で終わったときの、答えの保存とやり直し（{@code intake/compose}）を確認する。 */
class ComposeFailureTest extends ApiSupport {

    @MockitoSpyBean
    RestaterSkill restater;

    @AfterEach
    void restore() {
        doCallRealMethod().when(restater).restate(any(), any(), any(), any(), any(), any(), any());
    }

    private String toLastAnswer() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o1");
        return id;
    }

    @Test
    void 拒否は422とinquiryIdを返し_答えは保存され_composeでやり直せる() throws Exception {
        String id = toLastAnswer();
        doThrow(new AiRefusalException("拒否")).when(restater).restate(any(), any(), any(), any(), any(), any(), any());

        String body = text(skip(id, "note")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_REFUSED"))
                .andExpect(jsonPath("$.inquiryId").value(id)));

        assertThat(body).contains(id);
        String view = view(id);
        assertThat((String) JsonPath.read(view, "$.awaiting")).isEqualTo("INTAKE_ANSWER");
        assertThat((Boolean) JsonPath.read(view, "$.intake.complete")).isTrue();
        assertThat((Boolean) JsonPath.read(view, "$.canSendNow")).isTrue();
        assertThat((List<String>) JsonPath.read(view, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "q1", "note");

        // 失敗が続く間は、composeも同じ失敗になる
        compose(id).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.inquiryId").value(id));
        // 直ったら、同じ答えのまま組み立てられる
        doCallRealMethod().when(restater).restate(any(), any(), any(), any(), any(), any(), any());
        compose(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"))
                .andExpect(jsonPath("$.canSendNow").value(false))
                .andExpect(jsonPath("$.structured.summary").isNotEmpty());
        assertThat(usageSteps(id)).containsExactly("interviewer", "restater", "restater", "restater");
    }

    @Test
    void 一時的な失敗でも同じ扱いで_業務が止まっているときだけは担当者に送られる() throws Exception {
        String normal = toLastAnswer();
        String blocked = idOf(create(null, "BLOCKED"));
        pick(blocked, "type", "HOW_TO");
        pick(blocked, "system", "reports");
        doThrow(new AiUnavailableException("失敗", null)).when(restater).restate(any(), any(), any(), any(), any(), any(), any());

        skip(normal, "note").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        write(blocked, "note", "止まっています")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.sentImmediately").value(true));
    }

    @Test
    void 言い直しの失敗の後に緊急度を変えても組み立て直さず_業務が止まっているに変えると担当者に送られる() throws Exception {
        String id = toLastAnswer();
        doThrow(new AiUnavailableException("失敗", null)).when(restater).restate(any(), any(), any(), any(), any(), any(), any());
        skip(id, "note").andExpect(status().isServiceUnavailable());

        urgency(id, "HURRY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("INTAKE_ANSWER"))
                .andExpect(jsonPath("$.intake.complete").value(true))
                .andExpect(jsonPath("$.canSendNow").value(true));
        urgency(id, "BLOCKED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.sentImmediately").value(true))
                .andExpect(jsonPath("$.structured").value(org.hamcrest.Matchers.nullValue()));
    }
}
