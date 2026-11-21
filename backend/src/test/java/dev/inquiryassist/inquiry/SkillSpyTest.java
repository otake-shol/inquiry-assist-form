package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InterviewerSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 聞き手を呼ぶ・呼ばないと、説明の失敗後の再試行を、スパイで呼び出しを確かめながら確認する。 */
class SkillSpyTest extends ApiSupport {

    @MockitoSpyBean
    InterviewerSkill interviewer;

    @MockitoSpyBean
    ExplainerSkill explainer;

    @BeforeEach
    void reset() {
        clearInvocations(interviewer, explainer);
    }

    private long interviewerCalls() {
        return mockingDetails(interviewer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("ask"))
                .count();
    }

    @Test
    void 作業依頼と障害では聞き手を呼ばない() throws Exception {
        String work = idOf(create("延長をお願いします", null));
        String incident = idOf(create("サイトが止まっていて障害になっている", null));
        answers(work, EMPTY_ANSWERS);
        answers(incident, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"いいえ\"}]}");

        assertThat(interviewerCalls()).isZero();
    }

    @Test
    void 仕様確認と不具合の疑いでは聞き手を1回ずつ呼ぶ() throws Exception {
        create("期限の仕様", null);
        create("表示がおかしい", null);

        assertThat(interviewerCalls()).isEqualTo(2);
    }

    @Test
    void 種別を仕様確認へ変えると聞き手が1回呼ばれ質問がある状態でさらに変えても呼ばれない() throws Exception {
        String id = idOf(create("延長をお願いします", null));
        assertThat(interviewerCalls()).isZero();

        changeCategory(id, "SPEC_QUESTION");
        changeCategory(id, "SUSPECTED_BUG");

        assertThat(interviewerCalls()).isEqualTo(1);
    }

    @Test
    void 対象システムを変更しただけでは聞き手を呼び直さない() throws Exception {
        String id = idOf(create("期限の仕様", null));
        long before = interviewerCalls();

        mvc.perform(put("/api/inquiries/" + id + "/system")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemId\":\"billing\"}"));

        assertThat(interviewerCalls()).isEqualTo(before);
    }

    @Test
    void 説明が失敗した後の再試行は調査からやり直し成功すると保存済みの内容が置き換わる() throws Exception {
        String id = toAnswering("期限の仕様", null);
        doThrow(new AiUnavailableException("一時的な失敗", null)).when(explainer).explain(any(), any());

        String failed = stream(id);

        assertThat(failed).contains("event:failed");
        assertThat(jdbc.queryForObject("select answer_grounded from inquiries where id = ?::uuid", Boolean.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("select technical_memo is not null from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();

        doCallRealMethod().when(explainer).explain(any(), any());
        String retried = stream(id);

        String done = retried.lines()
                .dropWhile(l -> !l.startsWith("event:done"))
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring("data:".length())
                .trim();
        assertThat((String) JsonPath.read(done, "$.step")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).isNotBlank();
        assertThat(jdbc.queryForObject("select answer_grounded from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        // 失敗した説明と成功した説明の両方が、利用量に残る
        assertThat(jdbc.queryForList(
                        "select (u->>'failed')::boolean from inquiries, jsonb_array_elements(usages) u"
                                + " where id = ?::uuid and u->>'step' = 'explainer'",
                        Boolean.class,
                        id))
                .containsExactlyInAnyOrder(true, false);
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.answer.grounded").value(true));
    }

    private void changeCategory(String id, String category) throws Exception {
        mvc.perform(put("/api/inquiries/" + id + "/category")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"" + category + "\"}"));
    }
}
