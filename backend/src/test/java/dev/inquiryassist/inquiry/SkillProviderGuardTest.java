package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.skill.Skill;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * 8つのスキルと資料の検索のそれぞれに、実装の Bean がちょうど1つあることの検査（契約書「実装」）を、実装の数と設定の組み合わせごとに確かめる。
 * 実装がないとき、多すぎるときは、何をすればよいかを書いたメッセージで止まる（Spring の素の例外ではない）。
 */
class SkillProviderGuardTest {

    /** 検査の対象: 設定の名前から、実装が満たすインターフェース。 */
    private static Map<String, Class<?>> targets() {
        Map<String, Class<?>> targets = new LinkedHashMap<>();
        SkillProviderGuard.SKILL_TYPES.forEach((skill, type) -> targets.put("inquiry.skills." + skill.id() + ".provider", type));
        targets.put(SkillImplementations.KNOWLEDGE_KEY, KnowledgeSearch.class);
        return targets;
    }

    /** 対象のすべてに、実装を1つずつ置いた構成。{@code except} の型は置かない。{@code extra} の型は、もう1つ置く。 */
    private static ApplicationContextRunner runner(MockEnvironment environment, Class<?> except, Class<?> extra) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().merge(environment))
                .withBean(SkillProviderGuard.class);
        for (Class<?> type : targets().values()) {
            if (type != except) {
                runner = withBean(runner, type, "impl");
            }
            if (type == extra) {
                runner = withBean(runner, type, "extra");
            }
        }
        return runner;
    }

    @SuppressWarnings("unchecked")
    private static <T> ApplicationContextRunner withBean(ApplicationContextRunner runner, Class<?> type, String name) {
        return runner.withBean(type.getSimpleName() + "-" + name, (Class<T>) type, () -> (T) mock(type));
    }

    private static MockEnvironment provider(String key, String value) {
        return new MockEnvironment().withProperty(key, value);
    }

    private static void assertFailedWith(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        // Spring の素の例外ではなく、このガードのメッセージで止まる
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof IllegalStateException && cause.getMessage().contains("inquiry."))) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(IllegalStateException.class);
        assertThat(cause.getMessage()).contains(messageParts);
        assertThat(failure.toString()).doesNotContain("NoSuchBeanDefinition", "NoUniqueBeanDefinition");
    }

    @Test
    void すべて1つずつあれば_設定がなくても_mockと書いても_自分の名前でも起動できる() {
        runner(new MockEnvironment(), null, null).run(context -> assertThat(context).hasNotFailed());
        MockEnvironment explicit = new MockEnvironment();
        targets().keySet().forEach(key -> explicit.setProperty(key, "mock"));
        runner(explicit, null, null).run(context -> assertThat(context).hasNotFailed());
        MockEnvironment custom = new MockEnvironment();
        targets().keySet().forEach(key -> custom.setProperty(key, "custom"));
        runner(custom, null, null).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void どの対象でも_provider_を指定したのに実装がなければ_足すか_mockに戻すよう伝えて止まる() {
        targets().forEach((key, type) -> runner(provider(key, "custom"), type, null)
                .run(context -> assertFailedWith(
                        context,
                        key + " に custom が指定されています",
                        type.getSimpleName() + " の実装が見つかりません",
                        "実装したクラスを足す",
                        "mock に戻してください")));
    }

    @Test
    void どの対象でも_実装が2つあれば_providerを自分の実装の名前に変えるよう伝えて止まる() {
        targets().forEach((key, type) -> runner(new MockEnvironment(), null, type)
                .run(context -> assertFailedWith(
                        context,
                        type.getSimpleName() + " の実装が2つ以上あります",
                        key + " が mock のままです",
                        "自分の実装の名前に変えてください")));
    }

    @Test
    void providerを自分の名前にしても_実装が2つあれば止まり_1つにするよう伝える() {
        targets().forEach((key, type) -> runner(provider(key, "custom"), null, type)
                .run(context -> assertFailedWith(
                        context,
                        type.getSimpleName() + " の実装が2つ以上あります",
                        key + " は custom ですが",
                        "実装を1つにしてください")));
    }

    @Test
    void 対象は8つのスキルと資料の検索の9つ() {
        assertThat(targets()).hasSize(Skill.values().length + 1);
        assertThat(targets().keySet()).contains("inquiry.skills.advisor.provider", "inquiry.knowledge.provider");
    }
}
