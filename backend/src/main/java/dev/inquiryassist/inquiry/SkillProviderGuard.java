package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.skill.AdvisorSkill;
import dev.inquiryassist.core.skill.DrafterSkill;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 8つのスキルと一次情報の検索（{@link KnowledgeSearch}）のそれぞれに、実装の Bean がちょうど1つあることを、起動時に確かめる。
 *
 * <p>実装は {@code inquiry.skills.<スキル>.provider}（一次情報の検索は {@code inquiry.knowledge.provider}）で選ぶ。モックは
 * {@code provider} が {@code mock}（省略を含む）のときだけ Bean になるので、自分の実装のクラスを足して、{@code provider} に別の名前を
 * 書けば差し替わる。足りないとき（実装がない）と多すぎるとき（{@code mock} のまま、自分の実装も足した）は、何をすればよいかを書いた
 * メッセージで起動を止める（Spring の素の例外のまま落とさない）。
 *
 * <p>どの Bean も作る前に検査するため、{@link BeanFactoryPostProcessor} にしている。Bean の定義だけを数えるので、実装を作ることはしない。
 */
@Component
class SkillProviderGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    /** スキルごとの、実装が満たすインターフェース。 */
    static final Map<Skill, Class<?>> SKILL_TYPES = Map.of(
            Skill.RECEPTION, ReceptionSkill.class,
            Skill.INTERVIEWER, InterviewerSkill.class,
            Skill.RESTATER, RestaterSkill.class,
            Skill.INVESTIGATOR, InvestigatorSkill.class,
            Skill.EXPLAINER, ExplainerSkill.class,
            Skill.ADVISOR, AdvisorSkill.class,
            Skill.DRAFTER, DrafterSkill.class,
            Skill.TRANSLATOR, TranslatorSkill.class);

    private Environment environment;

    /**
     * @param environment 設定の参照先
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * @param beanFactory Bean ファクトリ
     * @throws IllegalStateException 実装の Bean が、ちょうど1つでないスキルまたは一次情報の検索がある場合
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        SkillImplementations implementations = SkillImplementations.from(environment);
        Map<String, Class<?>> targets = new LinkedHashMap<>();
        Map<String, String> providers = new LinkedHashMap<>();
        Arrays.stream(Skill.values()).forEach(skill -> {
            String key = "inquiry.skills." + skill.id() + ".provider";
            targets.put(key, SKILL_TYPES.get(skill));
            providers.put(key, implementations.providerOf(skill));
        });
        targets.put(SkillImplementations.KNOWLEDGE_KEY, KnowledgeSearch.class);
        providers.put(SkillImplementations.KNOWLEDGE_KEY, implementations.knowledgeProvider());

        targets.forEach((key, type) -> {
            String[] names = beanFactory.getBeanNamesForType(type, true, false);
            String provider = providers.get(key);
            if (names.length == 0) {
                throw new IllegalStateException(missing(key, provider, type));
            }
            if (names.length > 1) {
                throw new IllegalStateException(tooMany(key, provider, type, beanFactory, names));
            }
        });
    }

    private static String missing(String key, String provider, Class<?> type) {
        if (SkillImplementations.MOCK.equals(provider)) {
            return key + " は mock ですが、" + type.getSimpleName() + " の実装が見つかりません（モックの実装が取り除かれています）。"
                    + type.getSimpleName() + " を実装したクラスを足し、" + key + " をその実装の名前に変えてください";
        }
        return key + " に " + provider + " が指定されていますが、" + type.getSimpleName() + " の実装が見つかりません。"
                + type.getSimpleName() + " を実装したクラスを足す（dev.inquiryassist 以下に置き、@Component を付ける）か、"
                + key + " を mock に戻してください";
    }

    private static String tooMany(
            String key, String provider, Class<?> type, ConfigurableListableBeanFactory beanFactory, String[] names) {
        StringBuilder found = new StringBuilder();
        for (String name : names) {
            Class<?> actual = beanFactory.getType(name, false);
            found.append(found.isEmpty() ? "" : "、").append(actual == null ? name : actual.getName());
        }
        String advice = SkillImplementations.MOCK.equals(provider)
                ? key + " が mock のままです。自分の実装を使うなら、" + key + " を自分の実装の名前に変えてください（モックは provider が mock のときだけ有効です）"
                : key + " は " + provider + " ですが、実装が2つ以上あります。" + type.getSimpleName() + " の実装を1つにしてください";
        return type.getSimpleName() + " の実装が2つ以上あります（" + found + "）。" + advice;
    }
}
