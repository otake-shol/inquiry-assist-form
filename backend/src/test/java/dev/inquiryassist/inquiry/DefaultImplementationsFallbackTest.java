package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.AccessScope;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

/**
 * 既定の実装（利用者と、担当者に初めて渡したときの通知）は、同じ型の実装を足すと引っ込む（{@code @Primary} は要らない）。
 * 足さなければ、既定の実装が使われる。
 */
class DefaultImplementationsFallbackTest {

    /** 骨組みの既定の実装。部品の走査と同じ形で、注釈つきのクラスとして登録する。 */
    @Import({FixedCurrentUserProvider.class, LoggingFilingNotifier.class})
    static class Defaults {}

    /** 利用者と通知を使う側。コンストラクタで型だけを指定して受け取る。 */
    record Consumer(CurrentUserProvider users, FilingNotifier notifier) {}

    static class CustomUsers implements CurrentUserProvider {
        @Override
        public AccessScope currentScope() {
            return new AccessScope("custom-user", Set.of());
        }

        @Override
        public AccessScope scopeOf(String userId) {
            return new AccessScope(userId, Set.of());
        }
    }

    static class CustomNotifier implements FilingNotifier {
        @Override
        public void notifyFiled(FilingNotice notice) {}
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Defaults.class).withBean(Consumer.class);

    @Test
    void 足さなければ既定の実装が使われる() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            assertThat(consumer.users()).isInstanceOf(FixedCurrentUserProvider.class);
            assertThat(consumer.notifier()).isInstanceOf(LoggingFilingNotifier.class);
        });
    }

    @Test
    void 利用者を足すとそちらが使われ_既定の実装は候補に残るだけ() {
        runner.withBean(CustomUsers.class).run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            assertThat(consumer.users()).isInstanceOf(CustomUsers.class);
            assertThat(consumer.notifier()).isInstanceOf(LoggingFilingNotifier.class);
            assertThat(context.getBean(CurrentUserProvider.class)).isInstanceOf(CustomUsers.class);
            assertThat(context.getBeansOfType(CurrentUserProvider.class)).hasSize(2);
        });
    }

    @Test
    void 通知を足すとそちらが使われ_既定の実装は候補に残るだけ() {
        runner.withBean(CustomNotifier.class).run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            assertThat(consumer.notifier()).isInstanceOf(CustomNotifier.class);
            assertThat(consumer.users()).isInstanceOf(FixedCurrentUserProvider.class);
            assertThat(context.getBeansOfType(FilingNotifier.class)).hasSize(2);
        });
    }
}
