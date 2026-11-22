package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 時間で動く決まりの設定（{@code inquiry.rules}）の既定値と上書き、定期的な判定を止められることを、DB なしで確かめる。 */
class TimeRuleSettingsTest {

    @Configuration
    @EnableConfigurationProperties(InquiryProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

    @Test
    void 既定は東京の時間帯で5営業日で知らせ_さらに5営業日で終え_15分ごとに判定する() {
        runner.run(context -> {
            InquiryProperties.Rules rules = context.getBean(InquiryProperties.class).rules();
            assertThat(rules.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
            assertThat(rules.noReply().remindAfterBusinessDays()).isEqualTo(5);
            assertThat(rules.noReply().closeAfterBusinessDays()).isEqualTo(5);
            assertThat(rules.sweepEnabled()).isTrue();
            assertThat(rules.sweepInterval()).isEqualTo(Duration.ofMinutes(15));
            assertThat(rules.answerTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(rules).isEqualTo(InquiryProperties.Rules.defaults());
        });
    }

    @Test
    void 設定で上書きできる() {
        runner.withPropertyValues(
                        "inquiry.rules.zone=UTC",
                        "inquiry.rules.no-reply.remind-after-business-days=3",
                        "inquiry.rules.no-reply.close-after-business-days=7",
                        "inquiry.rules.sweep-enabled=false",
                        "inquiry.rules.sweep-interval=1h",
                        "inquiry.rules.answer-timeout=10m")
                .run(context -> {
                    InquiryProperties.Rules rules = context.getBean(InquiryProperties.class).rules();
                    assertThat(rules.zone()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(rules.periods().remindAfterBusinessDays()).isEqualTo(3);
                    assertThat(rules.periods().closeAfterBusinessDays()).isEqualTo(7);
                    assertThat(rules.sweepEnabled()).isFalse();
                    assertThat(rules.sweepInterval()).isEqualTo(Duration.ofHours(1));
                    assertThat(rules.answerTimeout()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    void 定期的な判定はsweep_enabledがfalseなら作らず_省くか_trueなら作る() {
        ApplicationContextRunner sweeper = new ApplicationContextRunner()
                .withUserConfiguration(TimeRuleSweeper.class)
                .withBean(TimeRuleService.class, () -> mock(TimeRuleService.class));

        sweeper.withPropertyValues("inquiry.rules.sweep-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TimeRuleSweeper.class));
        sweeper.run(context -> assertThat(context).hasSingleBean(TimeRuleSweeper.class));
        sweeper.withPropertyValues("inquiry.rules.sweep-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TimeRuleSweeper.class));
    }

    @Test
    void 定期的な判定は失敗しても例外を投げない() {
        TimeRuleService service = mock(TimeRuleService.class);
        org.mockito.Mockito.when(service.run(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("失敗"));

        new TimeRuleSweeper(service).sweep();

        org.mockito.Mockito.verify(service).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 開発用の口はproductionプロファイルでは作らない() {
        ApplicationContextRunner dev = new ApplicationContextRunner()
                .withUserConfiguration(dev.inquiryassist.web.DevRulesController.class)
                .withBean(TimeRuleService.class, () -> mock(TimeRuleService.class));

        dev.run(context -> assertThat(context).hasSingleBean(dev.inquiryassist.web.DevRulesController.class));
        dev.withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .run(context -> assertThat(context).doesNotHaveBean(dev.inquiryassist.web.DevRulesController.class));
    }

    @Test
    void 手元のモデルのプロファイルは本人が待つ調査の時間切れを10分にする() {
        runner.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local-ai")
                .run(context -> assertThat(context.getBean(InquiryProperties.class).rules().answerTimeout())
                        .isEqualTo(Duration.ofMinutes(10)));
        // プロファイルがなければ、既定の60秒
        runner.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(InquiryProperties.class).rules().answerTimeout())
                        .isEqualTo(Duration.ofSeconds(60)));
    }
}
