package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.skill.Skill;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 「モックを含むか」は、設定の名前から判断する。名前は任意で、mock 以外は、足した実装の名前。 */
class SkillImplementationsTest {

    @Test
    void 設定がなければすべてmockで_モックを含む() {
        SkillImplementations implementations = SkillImplementations.from(new MockEnvironment());

        assertThat(implementations.includesMock()).isTrue();
        assertThat(implementations.byId()).hasSize(8).containsEntry("reception", "mock").containsEntry("translator", "mock");
        assertThat(implementations.knowledgeProvider()).isEqualTo("mock");
        assertThat(implementations.mockNames()).hasSize(9).contains("reception", "knowledge");
    }

    @Test
    void 一部を自分の名前にすると_その名前がそのまま出て_残りがモックなら引き続きモックを含む() {
        SkillImplementations implementations = SkillImplementations.from(new MockEnvironment()
                .withProperty("inquiry.skills.reception.provider", "bedrock")
                .withProperty("inquiry.knowledge.provider", "search-api"));

        assertThat(implementations.providerOf(Skill.RECEPTION)).isEqualTo("bedrock");
        assertThat(implementations.isMock(Skill.RECEPTION)).isFalse();
        assertThat(implementations.byId()).containsEntry("reception", "bedrock").containsEntry("restater", "mock");
        assertThat(implementations.knowledgeProvider()).isEqualTo("search-api");
        assertThat(implementations.includesMock()).isTrue();
        assertThat(implementations.mockNames()).doesNotContain("reception", "knowledge").contains("restater");
    }

    @Test
    void すべてを自分の名前にすると_モックを含まない() {
        MockEnvironment environment = new MockEnvironment().withProperty("inquiry.knowledge.provider", "search-api");
        for (Skill skill : Skill.values()) {
            environment.setProperty("inquiry.skills." + skill.id() + ".provider", "custom-" + skill.id());
        }

        SkillImplementations implementations = SkillImplementations.from(environment);

        assertThat(implementations.includesMock()).isFalse();
        assertThat(implementations.mockNames()).isEmpty();
    }
}
