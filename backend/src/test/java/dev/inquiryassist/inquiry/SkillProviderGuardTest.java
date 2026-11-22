package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.skill.Skill;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** 8つのスキルは、モックの実装だけを持つ。どのスキルでも、モック以外を指定すると、起動を止める（契約書「実装」）。 */
class SkillProviderGuardTest {

    private ApplicationContextRunner runner(MockEnvironment environment) {
        return new ApplicationContextRunner()
                .withBean(SkillImplementations.class, () -> SkillImplementations.from(environment))
                .withBean(SkillProviderGuard.class);
    }

    @Test
    void 設定がなければ_すべてmockと書いても起動できる() {
        runner(new MockEnvironment()).run(context -> assertThat(context).hasNotFailed());
        MockEnvironment explicit = new MockEnvironment();
        for (Skill skill : Skill.values()) {
            explicit.setProperty("inquiry.skills." + skill.id() + ".provider", "mock");
        }
        runner(explicit).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 全スキルのどれにmock以外を指定しても起動が止まる() {
        assertThat(Skill.values()).hasSize(8);
        for (Skill skill : Skill.values()) {
            runner(new MockEnvironment().withProperty("inquiry.skills." + skill.id() + ".provider", "ollama"))
                    .run(context -> assertThat(context)
                            .as(skill.id())
                            .hasFailed()
                            .getFailure()
                            .hasStackTraceContaining("inquiry.skills." + skill.id() + ".provider")
                            .hasStackTraceContaining("ollama"));
        }
    }
}
