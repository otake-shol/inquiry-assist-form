package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring に依存しない core のクラスを Bean として組み立てる。 */
@Configuration(proxyBeanMethods = false)
class SkillConfiguration {

    /**
     * 調査、照合、説明の流れ。
     *
     * @param investigator 調査
     * @param explainer 説明
     * @param knowledgeSearch 出典の照合に使う一次情報の読み取り
     * @return 0次回答のパイプライン
     */
    @Bean
    AnswerPipeline answerPipeline(
            InvestigatorSkill investigator, ExplainerSkill explainer, KnowledgeSearch knowledgeSearch) {
        return new AnswerPipeline(investigator, new CitationVerifier(knowledgeSearch), explainer);
    }
}
