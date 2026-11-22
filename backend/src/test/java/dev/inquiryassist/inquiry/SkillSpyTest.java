package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.ReceptionSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 受付と聞き手を呼ぶ・呼ばないと、説明の失敗後の再試行を、スパイで呼び出しを確かめながら確認する。 */
class SkillSpyTest extends ApiSupport {

    @MockitoSpyBean
    ReceptionSkill reception;

    @MockitoSpyBean
    InterviewerSkill interviewer;

    @MockitoSpyBean
    ExplainerSkill explainer;

    @MockitoSpyBean
    RestaterSkill restater;

    @MockitoSpyBean
    InvestigatorSkill investigator;

    @BeforeEach
    void reset() {
        clearInvocations(reception, interviewer, explainer, restater, investigator);
    }

    private long receptionCalls() {
        return mockingDetails(reception).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("receive"))
                .count();
    }

    private long interviewerCalls() {
        return mockingDetails(interviewer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("ask"))
                .count();
    }

    @Test
    void 押して答えるだけなら受付を呼ばず_利用量にも受付は現れない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO").andExpect(status().isOk());
        pick(id, "system", "reports").andExpect(status().isOk());
        pick(id, "q1", "o1").andExpect(status().isOk());
        skip(id, "note").andExpect(status().isOk());

        assertThat(receptionCalls()).isZero();
        assertThat(usageSteps(id)).doesNotContain("reception").containsExactly("interviewer", "restater");
    }

    @Test
    void 文章を書いたときだけ受付を呼ぶ() throws Exception {
        String id = idOf(create("期限の仕様"));
        assertThat(receptionCalls()).isEqualTo(1);

        // 決まっていない置き場所が残っていれば、途中で文章を足したときにも呼ぶ
        intakeText(id, "もう少し詳しく").andExpect(status().isOk());
        assertThat(receptionCalls()).isEqualTo(2);
        assertThat(usageSteps(id)).startsWith("reception", "reception");
    }

    @Test
    void 置き場所がそろっていれば文章を足しても受付を呼ばない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports").andExpect(status().isOk());
        long before = receptionCalls();

        intakeText(id, "補足です").andExpect(status().isOk());

        assertThat(receptionCalls()).isEqualTo(before);
    }

    @Test
    void 設定に質問がなければ聞き手を1回だけ呼び_設定に質問があれば呼ばない() throws Exception {
        String viaInterviewer = idOf(start());
        pick(viaInterviewer, "type", "HOW_TO");
        pick(viaInterviewer, "system", "reports");
        assertThat(interviewerCalls()).isEqualTo(1);
        // 回答のたびに呼び直さない
        pick(viaInterviewer, "q1", "o1");
        assertThat(interviewerCalls()).isEqualTo(1);

        clearInvocations(interviewer);
        String configured = idOf(start());
        pick(configured, "type", "LOOKS_WRONG");
        pick(configured, "system", "job-admin");
        unknown(configured, "function");
        assertThat(view(configured)).contains("\"questionId\":\"since\"");
        assertThat(interviewerCalls()).isZero();
    }

    @Test
    void 業務が止まっているときは聞き手を呼ばない() throws Exception {
        String id = idOf(create(null, "BLOCKED"));
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");

        assertThat(interviewerCalls()).isZero();
    }

    @Test
    void 戻って置き場所を選び直すと_前の置き場所のために作った質問は捨てて聞き手を呼び直す() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        assertThat(interviewerCalls()).isEqualTo(1);

        // 詳しい質問へ戻っただけなら、作った質問は残る
        pick(id, "q1", "o1");
        back(id, "q1").andExpect(status().isOk());
        assertThat(interviewerCalls()).isEqualTo(1);

        // 置き場所へ戻って選び直すと、別の置き場所の質問になりうるので呼び直す
        back(id, "system").andExpect(status().isOk());
        pick(id, "system", "billing");
        unknown(id, "function");
        assertThat(interviewerCalls()).isEqualTo(2);
    }

    @Test
    void 説明が失敗した後の再試行は調査からやり直し成功すると保存済みの内容が置き換わる() throws Exception {
        // 調査は確定の時点で始まるので、説明を失敗させてから確定する
        doThrow(new AiUnavailableException("一時的な失敗", null)).when(explainer).explain(any(), any(), any());
        String id = toAnswering("期限の仕様", null);

        // 進捗ストリームにはつながない。調査は、説明が失敗して止まる（待つ状態は変わらない）
        assertThat(waitFor(() -> "FAILED".equals(row(id).get("investigation_status")), 15_000)).isTrue();
        assertThat(row(id).get("awaiting")).isEqualTo("INVESTIGATION");
        assertThat(jdbc.queryForObject("select answer_grounded from inquiries where id = ?::uuid", Boolean.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("select technical_memo is not null from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();

        doCallRealMethod().when(explainer).explain(any(), any(), any());
        String retried = stream(id);

        String done = retried.lines()
                .dropWhile(l -> !l.startsWith("event:done"))
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring("data:".length())
                .trim();
        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
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

    /** スパイに記録された、指定のメソッドの呼び出しの最後の引数（本人の言語）を、呼んだ順に返す。 */
    private static java.util.List<Object> languagesOf(Object spy, String method) {
        return mockingDetails(spy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method))
                .map(i -> i.getArguments()[i.getArguments().length - 1])
                .toList();
    }

    @Test
    void 聞き手と言い直しと調査と説明は問い合わせの言語を受け取る() throws Exception {
        MediaType json = MediaType.APPLICATION_JSON;
        String id = idOf(text(mvc.perform(post("/api/inquiries")
                        .header("Accept-Language", "en")
                        .contentType(json)
                        .content(json("text", "What is the rule for the deadline?")))
                .andExpect(status().isCreated())));
        // 入口の最後まで、英語で進める
        String current = view(id);
        while (JsonPath.read(current, "$.intake.next") != null) {
            String questionId = JsonPath.read(current, "$.intake.next.questionId");
            String kind = JsonPath.read(current, "$.intake.next.kind");
            String body = "NOTE".equals(kind)
                    ? ((Boolean) JsonPath.read(current, "$.intake.next.canSkip")
                            ? json("questionId", questionId, "skipped", true)
                            : json("questionId", questionId, "text", "x"))
                    : json("questionId", questionId, "unknown", true);
            current = text(mvc.perform(post("/api/inquiries/" + id + "/intake/answers")
                            .header("Accept-Language", "en")
                            .contentType(json)
                            .content(body))
                    .andExpect(status().isOk()));
        }
        mvc.perform(put("/api/inquiries/" + id + "/structured")
                        .header("Accept-Language", "en")
                        .contentType(json)
                        .content("{}"))
                .andExpect(status().isOk());
        stream(id);

        assertThat(languagesOf(interviewer, "ask")).isNotEmpty().containsOnly("en");
        assertThat(languagesOf(restater, "restate")).isNotEmpty().containsOnly("en");
        assertThat(languagesOf(investigator, "investigate")).containsExactly("en");
        assertThat(languagesOf(explainer, "explain")).containsExactly("en");
    }
}
