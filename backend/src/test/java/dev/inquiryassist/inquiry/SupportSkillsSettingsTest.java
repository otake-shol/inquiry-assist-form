package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** 助言と下書きは、モックの実装だけを持つ。モック以外を指定すると、起動を止める（契約書「実装」）。 */
class SupportSkillsSettingsTest {

    private ApplicationContextRunner runner(MockEnvironment environment) {
        return new ApplicationContextRunner()
                .withBean(SkillImplementations.class, () -> SkillImplementations.from(environment))
                .withBean(SupportSkillsGuard.class);
    }

    @Test
    void 設定がなければ_またmockなら起動できる() {
        runner(new MockEnvironment()).run(context -> assertThat(context).hasNotFailed());
        runner(new MockEnvironment()
                        .withProperty("inquiry.skills.advisor.provider", "mock")
                        .withProperty("inquiry.skills.drafter.provider", "mock"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 助言にmock以外を指定すると起動が止まる() {
        runner(new MockEnvironment().withProperty("inquiry.skills.advisor.provider", "ollama"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("inquiry.skills.advisor.provider")
                        .hasStackTraceContaining("ollama"));
    }

    @Test
    void 下書きにmock以外を指定すると起動が止まる() {
        runner(new MockEnvironment().withProperty("inquiry.skills.drafter.provider", "bedrock"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("inquiry.skills.drafter.provider"));
    }

    @Test
    void 訳にmock以外を指定すると起動が止まる() {
        runner(new MockEnvironment().withProperty("inquiry.skills.translator.provider", "ollama"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("inquiry.skills.translator.provider")
                        .hasStackTraceContaining("ollama"));
    }

    @Test
    void 既存の5つのスキルの実装の指定は妨げない() {
        runner(new MockEnvironment().withProperty("inquiry.skills.investigator.provider", "ollama"))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
