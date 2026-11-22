package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.mock.MockInterviewerSkill;
import dev.inquiryassist.mock.MockReceptionSkill;
import dev.inquiryassist.ollama.FakeOllama;
import dev.inquiryassist.ollama.OllamaReceptionSkill;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** 一部のスキルだけを ollama にした構成で、実装の選択と、モックを含むことの扱いを確認する。 */
@TestPropertySource(properties = {
    "inquiry.skills.reception.provider=ollama",
    "inquiry.skills.reception.model=fake-model",
    "inquiry.skills.reception.timeout=5s"
})
class SkillSwitchTest extends ApiSupport {

    private static final FakeOllama OLLAMA = new FakeOllama();

    @DynamicPropertySource
    static void ollama(DynamicPropertyRegistry registry) {
        registry.add("inquiry.ollama.base-url", OLLAMA::baseUrl);
    }

    @AfterAll
    static void stop() {
        OLLAMA.close();
    }

    @Autowired
    ReceptionSkill reception;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<MockReceptionSkill> mockReception;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<MockInterviewerSkill> mockInterviewer;

    @Autowired
    RestaterSkill restater;

    @Test
    void ollamaにしたスキルだけが切り替わり他はモックのまま() {
        assertThat(reception).isInstanceOf(OllamaReceptionSkill.class);
        assertThat(mockReception.getIfAvailable()).isNull();
        assertThat(mockInterviewer.getIfAvailable()).isNotNull();
        assertThat(restater).isInstanceOf(dev.inquiryassist.mock.MockRestaterSkill.class);
    }

    @Test
    void configのskillsとmockは一部だけollamaでもモックを含むことを示す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.skills.reception").value("ollama"))
                .andExpect(jsonPath("$.skills.interviewer").value("mock"))
                .andExpect(jsonPath("$.skills.investigator").value("mock"));
    }

    @Test
    void 一部モックの問い合わせ記録はmockがtrueで実際のモデルの利用量も残る() throws Exception {
        OLLAMA.reply("{\"type\":\"HOW_IT_WORKS\",\"systemId\":null,\"functionId\":null}", 77, 9);

        String created = create("期限の仕様");
        String id = idOf(created);

        // 受付は実際のモデル（フェイク）が置いた
        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("HOW_IT_WORKS");
        assertThat((String) JsonPath.read(created, "$.type.by")).isEqualTo("AI");
        assertThat((Boolean) JsonPath.read(created, "$.mock")).isFalse();
        // 聞き手と言い直しはモック
        String structured = finishIntake(id, null);
        assertThat((Boolean) JsonPath.read(structured, "$.mock")).isTrue();
        assertThat(row(id).get("mock")).isEqualTo(true);
        var usages = jdbc.queryForList(
                "select u->>'step' as step, u->>'model' as model, (u->>'mock')::boolean as mock,"
                        + " (u->>'inputTokens')::int as input from inquiries, jsonb_array_elements(usages) u"
                        + " where id = ?::uuid",
                id);
        assertThat(usages.get(0)).containsEntry("step", "reception").containsEntry("model", "fake-model")
                .containsEntry("mock", false).containsEntry("input", 77);
        assertThat(usages.get(1)).containsEntry("step", "interviewer").containsEntry("mock", true);
    }

    @Test
    void traceのproviderは実際のモデルの呼び出しだけollamaでモックの呼び出しはmockになる() throws Exception {
        OLLAMA.reply("{\"type\":\"HOW_IT_WORKS\",\"systemId\":null,\"functionId\":null}", 77, 9);

        String id = idOf(create("期限の仕様"));
        finishIntake(id, null);

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.trace[0].skill").value("reception"))
                .andExpect(jsonPath("$.trace[0].provider").value("ollama"))
                .andExpect(jsonPath("$.trace[0].model").value("fake-model"))
                .andExpect(jsonPath("$.trace[0].inputTokens").value(77))
                .andExpect(jsonPath("$.trace[1].skill").value("interviewer"))
                .andExpect(jsonPath("$.trace[1].provider").value("mock"));
    }

    @Test
    void 実際のモデルの受付が失敗してもエラーにならず質問として聞き_失敗した呼び出しの利用量はモック由来ではない() throws Exception {
        OLLAMA.status(500);

        String created = create("期限の仕様");
        String id = idOf(created);

        // 何も置かれず、種類から順に聞く
        assertThat((Object) JsonPath.read(created, "$.type")).isNull();
        assertThat((String) JsonPath.read(created, "$.intake.next.questionId")).isEqualTo("type");
        // 受付が実際のモデルなので、この記録はモックではない
        assertThat(row(id).get("mock")).isEqualTo(false);
        assertThat(jdbc.queryForObject(
                        "select (usages->0->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select (usages->0->>'mock')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isFalse();
    }

    @Test
    void モックのタグは実際のモデルで動くスキルには効かない() throws Exception {
        // 受付が ollama のため、[判定エラー] を書いても失敗せず、モデルが読み取った種類が置かれる
        OLLAMA.reply("{\"type\":\"HOW_IT_WORKS\",\"systemId\":null,\"functionId\":null}", 1, 1);

        String created = create("[判定エラー] 期限の仕様");

        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("HOW_IT_WORKS");
    }
}
