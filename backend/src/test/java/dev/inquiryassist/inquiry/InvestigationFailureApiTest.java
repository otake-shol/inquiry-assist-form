package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 調べ直しが失敗したときに、前の結果が残ることを確かめる（調査のスキルを、呼び出しごとに失敗させる）。 */
class InvestigationFailureApiTest extends ApiSupport {

    @MockitoSpyBean
    InvestigatorSkill investigator;

    @AfterEach
    void restore() {
        doCallRealMethod().when(investigator).investigate(any(), any(), any(), any());
    }

    @Test
    void 調べ直しが失敗すると前の結果が残りFAILEDになる() throws Exception {
        String id = toAnswering("使い方を教えて", null);
        stream(id);
        sendNow(id).andExpect(status().isOk());
        String before = text(mvc.perform(get("/api/operator/inquiries/" + id)));
        assertThat((String) JsonPath.read(before, "$.investigation.audience")).isEqualTo("ASKER");
        List<String> findings = JsonPath.read(before, "$.answer.memo.findings");
        assertThat(findings).isNotEmpty();

        doThrow(new AiUnavailableException("失敗", null)).when(investigator).investigate(any(), any(), any(), any());
        mvc.perform(post("/api/operator/inquiries/" + id + "/investigation")).andExpect(status().isOk());
        awaitInvestigationIdle(id);

        String after = text(mvc.perform(get("/api/operator/inquiries/" + id)));
        assertThat((String) JsonPath.read(after, "$.investigation.status")).isEqualTo("FAILED");
        assertThat((Object) JsonPath.read(after, "$.investigation.audience")).isNull();
        assertThat((Object) JsonPath.read(after, "$.investigation.finishedAt")).isNotNull();
        // 前の結果は、そのまま残る。本人向けの答えも、前に見せた答えのまま
        assertThat((List<String>) JsonPath.read(after, "$.answer.memo.findings")).isEqualTo(findings);
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.answer").isNotEmpty());
        assertThat(usageSteps(id)).endsWith("investigator", "explainer", "advisor", "investigator");
    }
}
