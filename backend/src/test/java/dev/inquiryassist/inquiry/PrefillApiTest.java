package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.Prefill;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 設定の質問の答えが本人の文章に既に書かれていれば聞かない処理（聞き手の2つ目の仕事）を、API から確かめる。
 * 出力を確かめる規則そのものは {@code IntakePlannerTest}。
 */
class PrefillApiTest extends ApiSupport {

    private static final String LOOKS_WRONG_TEXT = "求人管理画面の求人の掲載がおかしい。";

    @MockitoSpyBean
    InterviewerSkill interviewer;

    @BeforeEach
    void reset() {
        clearInvocations(interviewer);
    }

    @AfterEach
    void restore() {
        doCallRealMethod().when(interviewer).prefill(any(), any());
    }

    private long prefillCalls() {
        return mockingDetails(interviewer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("prefill"))
                .count();
    }

    @Test
    void 文章に選択肢の名前があれば設定の質問は聞かれず_読み取った答えとして入る() throws Exception {
        String created = create(LOOKS_WRONG_TEXT + "今日から出ません");
        String id = idOf(created);

        assertThat((String) JsonPath.read(created, "$.awaiting")).isEqualTo("QUESTION_CONFIRMATION");
        assertThat((List<String>) JsonPath.read(created, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function", "since");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].kind")).isEqualTo("DETAIL");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].text")).isEqualTo("いつから起きていますか");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.kind")).isEqualTo("INFERRED");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.optionId")).isEqualTo("today");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.label")).isEqualTo("今日から");
        assertThat((String) JsonPath.read(created, "$.structured.summary")).contains("（いつから起きていますか: 今日から）");
        assertThat(prefillCalls()).isEqualTo(1);
        // 受付、読み取り（聞き手）、言い直し。設定の質問があるので、質問を作る聞き手の呼び出しはない
        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater");
    }

    @Test
    void 読み取れなかった設定の質問だけが聞かれる() throws Exception {
        // 2問のうち、1問目（範囲）だけが文章に書かれている
        String created = create("求人管理画面の応募者の一覧を変えてほしい。常にです");
        String id = idOf(created);

        assertThat((List<String>) JsonPath.read(created, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function", "scope");
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.optionId")).isEqualTo("always");
        assertThat((String) JsonPath.read(created, "$.awaiting")).isEqualTo("INTAKE_ANSWER");
        assertThat((String) JsonPath.read(created, "$.intake.next.questionId")).isEqualTo("effect");

        pick(id, "effect", "slow").andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"));
        // 読み取りは1回だけ
        assertThat(prefillCalls()).isEqualTo(1);
    }

    @Test
    void どれも読み取れなければ従来どおり聞かれる() throws Exception {
        String created = create(LOOKS_WRONG_TEXT);

        assertThat((String) JsonPath.read(created, "$.intake.next.questionId")).isEqualTo("since");
        assertThat(prefillCalls()).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(created, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function");
    }

    @Test
    void 押して答えただけなら読み取りは呼ばれない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "LOOKS_WRONG");
        pick(id, "system", "job-admin");
        pick(id, "function", "posting").andExpect(jsonPath("$.intake.next.questionId").value("since"));
        pick(id, "since", "today");
        skip(id, "note").andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"));

        assertThat(prefillCalls()).isZero();
        assertThat(usageSteps(id)).containsExactly("restater");
    }

    @Test
    void 業務が止まっているときは読み取りも行わない() throws Exception {
        String id = idOf(create(LOOKS_WRONG_TEXT + "今日から", "BLOCKED"));

        assertThat(prefillCalls()).isZero();
        assertThat((String) JsonPath.read(view(id), "$.awaiting")).isEqualTo("HANDLING");
        assertThat((List<String>) JsonPath.read(view(id), "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function");
    }

    @Test
    void 聞き手が失敗してもエラーにならず設定の質問がすべて聞かれ_失敗した呼び出しが記録される() throws Exception {
        String created = create("[質問エラー] " + LOOKS_WRONG_TEXT + "今日から");
        String id = idOf(created);

        assertThat((String) JsonPath.read(created, "$.awaiting")).isEqualTo("INTAKE_ANSWER");
        assertThat((String) JsonPath.read(created, "$.intake.next.questionId")).isEqualTo("since");
        assertThat(usageSteps(id)).containsExactly("reception", "interviewer");
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'mock')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        // 失敗した質問も、読み取りは1回で済んだことにして、同じ質問のために呼び直さない
        pick(id, "since", "earlier").andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"));
        assertThat(prefillCalls()).isEqualTo(1);
    }

    @Test
    void 途中で文章に切り替えると_まだ答えていない設定の質問が読み取られる() throws Exception {
        String id = idOf(start());
        pick(id, "type", "LOOKS_WRONG");
        pick(id, "system", "job-admin");
        pick(id, "function", "posting").andExpect(jsonPath("$.intake.next.questionId").value("since"));

        intakeText(id, "今週からです")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"))
                .andExpect(jsonPath("$.intake.entries[3].questionId").value("since"))
                .andExpect(jsonPath("$.intake.entries[3].answer.kind").value("INFERRED"))
                .andExpect(jsonPath("$.intake.entries[3].answer.optionId").value("this-week"));
    }

    @Test
    void 切り替えても_本人が既に答えた質問は変わらない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "CHANGE_REQUEST");
        pick(id, "system", "job-admin");
        pick(id, "function", "applicants");
        pick(id, "scope", "once").andExpect(jsonPath("$.intake.next.questionId").value("effect"));

        // 文章は「常に」と読めるが、本人が選んだ「今回だけ」は変えない。まだ答えていない質問だけ読み取る
        intakeText(id, "常に遅いです")
                .andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"))
                .andExpect(jsonPath("$.intake.entries[3].questionId").value("scope"))
                .andExpect(jsonPath("$.intake.entries[3].answer.kind").value("OPTION"))
                .andExpect(jsonPath("$.intake.entries[3].answer.optionId").value("once"))
                .andExpect(jsonPath("$.intake.entries[4].questionId").value("effect"))
                .andExpect(jsonPath("$.intake.entries[4].answer.kind").value("INFERRED"))
                .andExpect(jsonPath("$.intake.entries[4].answer.optionId").value("slow"));
    }

    @Test
    void 読み取った答えに戻ると_その質問が聞かれ_同じ文章から読み取り直して消えることはない() throws Exception {
        String created = create("求人管理画面の応募者の一覧を変えてほしい。常にです");
        String id = idOf(created);
        assertThat((String) JsonPath.read(created, "$.intake.entries[3].answer.kind")).isEqualTo("INFERRED");
        assertThat(prefillCalls()).isEqualTo(1);

        back(id, "scope")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("INTAKE_ANSWER"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type", "system", "function")))
                .andExpect(jsonPath("$.intake.next.questionId").value("scope"))
                .andExpect(jsonPath("$.utterance").value("求人管理画面の応募者の一覧を変えてほしい。常にです"));
        assertThat(prefillCalls()).isEqualTo(1);

        // 答え直すと、読み取りを済ませてある次の質問が聞かれる
        pick(id, "scope", "once")
                .andExpect(jsonPath("$.intake.entries[3].answer.kind").value("OPTION"))
                .andExpect(jsonPath("$.intake.next.questionId").value("effect"));
        assertThat(prefillCalls()).isEqualTo(1);
    }

    @Test
    void 確認から戻っても_読み取った最後の答えが聞かれ_読み取り直されない() throws Exception {
        String id = idOf(create(LOOKS_WRONG_TEXT + "今日から出ません"));
        assertThat(prefillCalls()).isEqualTo(1);

        mvc.perform(post("/api/inquiries/" + id + "/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.next.questionId").value("since"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type", "system", "function")));
        assertThat(prefillCalls()).isEqualTo(1);

        pick(id, "since", "earlier").andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"));
    }

    // ---- 文章で答える質問（モックは読み取らないため、聞き手を差し替えて確かめる） ----

    private static final String BILLING_TEXT = "請求管理の請求書をA社向けに作り直してください";

    private void stubPartner(String text) {
        doReturn(new AiResult<>(new Prefill(List.of(new Prefill.Answer("partner", null, text))), Usage.ofMock()))
                .when(interviewer)
                .prefill(any(), any());
    }

    @Test
    void 文章で答える質問は_本人の文章にそのまま含まれる答えだけが採られる() throws Exception {
        stubPartner("A社");
        String taken = create(BILLING_TEXT);

        assertThat((List<String>) JsonPath.read(taken, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system", "function", "partner");
        assertThat((String) JsonPath.read(taken, "$.intake.entries[3].answer.kind")).isEqualTo("INFERRED");
        assertThat((String) JsonPath.read(taken, "$.intake.entries[3].answer.text")).isEqualTo("A社");
        assertThat((String) JsonPath.read(taken, "$.awaiting")).isEqualTo("QUESTION_CONFIRMATION");
        assertThat((String) JsonPath.read(taken, "$.structured.summary")).contains("（対象の取引先はどこですか: A社）");

        // 文章にない答えは採らず、質問として聞く
        stubPartner("B社");
        String madeUp = create(BILLING_TEXT);
        assertThat((String) JsonPath.read(madeUp, "$.intake.next.questionId")).isEqualTo("partner");
        assertThat((String) JsonPath.read(madeUp, "$.intake.next.text")).isEqualTo("対象の取引先はどこですか");
    }
}
