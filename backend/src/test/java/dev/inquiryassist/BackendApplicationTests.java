package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import dev.inquiryassist.core.skill.AdvisorSkill;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.DrafterSkill;
import dev.inquiryassist.core.skill.TranslatorSkill;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.inquiry.SkillImplementations;
import dev.inquiryassist.mock.MockAdvisorSkill;
import dev.inquiryassist.mock.MockDrafterSkill;
import dev.inquiryassist.mock.MockExplainerSkill;
import dev.inquiryassist.mock.MockTranslatorSkill;
import dev.inquiryassist.mock.MockInterviewerSkill;
import dev.inquiryassist.mock.MockInvestigatorSkill;
import dev.inquiryassist.mock.MockKnowledgeSearch;
import dev.inquiryassist.mock.MockReceptionSkill;
import dev.inquiryassist.mock.MockRestaterSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import dev.inquiryassist.core.KnowledgeSearch;

/** 設定を書かなければ、すべてのスキルと一次情報の検索がモックで動く（外部のAIに接続しない）。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Autowired ReceptionSkill reception;
    @Autowired InterviewerSkill interviewer;
    @Autowired RestaterSkill restater;
    @Autowired InvestigatorSkill investigator;
    @Autowired ExplainerSkill explainer;
    @Autowired AdvisorSkill advisor;
    @Autowired DrafterSkill drafter;
    @Autowired TranslatorSkill translator;
    @Autowired KnowledgeSearch knowledge;
    @Autowired AnswerPipeline pipeline;
    @Autowired SkillImplementations implementations;
    @Autowired MockMvc mvc;

    @Test
    void 設定なしでは全スキルと一次情報の検索がモックになる() {
        assertThat(reception).isInstanceOf(MockReceptionSkill.class);
        assertThat(interviewer).isInstanceOf(MockInterviewerSkill.class);
        assertThat(restater).isInstanceOf(MockRestaterSkill.class);
        assertThat(investigator).isInstanceOf(MockInvestigatorSkill.class);
        assertThat(explainer).isInstanceOf(MockExplainerSkill.class);
        assertThat(advisor).isInstanceOf(MockAdvisorSkill.class);
        assertThat(drafter).isInstanceOf(MockDrafterSkill.class);
        assertThat(translator).isInstanceOf(MockTranslatorSkill.class);
        assertThat(knowledge).isInstanceOf(MockKnowledgeSearch.class);
        assertThat(pipeline).isNotNull();
        assertThat(implementations.includesMock()).isTrue();
        assertThat(implementations.byId().values()).containsOnly("mock").hasSize(8);
    }

    @Test
    void configは全スキルがモックであることを返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.skills.reception").value("mock"))
                .andExpect(jsonPath("$.skills.interviewer").value("mock"))
                .andExpect(jsonPath("$.skills.restater").value("mock"))
                .andExpect(jsonPath("$.skills.investigator").value("mock"))
                .andExpect(jsonPath("$.skills.explainer").value("mock"))
                .andExpect(jsonPath("$.skills.advisor").value("mock"))
                .andExpect(jsonPath("$.skills.drafter").value("mock"))
                .andExpect(jsonPath("$.skills.translator").value("mock"))
                .andExpect(jsonPath("$.skills.length()").value(8));
    }
}
