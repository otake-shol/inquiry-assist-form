package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.skill.Skill;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.PropertyResolver;

/**
 * スキルと一次情報の検索が、どの実装で動いているか。名前は任意で、{@code mock} 以外は、足した実装の名前。
 * その名前の実装が、ちょうど1つあることは、{@link SkillProviderGuard} が起動時に確かめる。
 *
 * <p>「モックを含むか」は Spring のプロファイルではなく、スキルごとの設定
 * （{@code inquiry.skills.<識別子>.provider}、{@code inquiry.knowledge.provider}）から判断する。
 * 設定がなければすべて {@code mock}。1つでも {@code mock} があれば「モックを含む」。
 */
public final class SkillImplementations {

    /** モックの実装名。 */
    public static final String MOCK = "mock";

    /** 一次情報の検索の実装を選ぶ設定の名前。 */
    public static final String KNOWLEDGE_KEY = "inquiry.knowledge.provider";

    private final Map<Skill, String> skills;
    private final String knowledge;

    private SkillImplementations(Map<Skill, String> skills, String knowledge) {
        this.skills = skills;
        this.knowledge = knowledge;
    }

    /**
     * 設定から実装の対応を読む。Bean の生成前（起動時の検査）にも使える。
     *
     * @param resolver 設定の参照先
     * @return 実装の対応
     */
    public static SkillImplementations from(PropertyResolver resolver) {
        Map<Skill, String> skills = new LinkedHashMap<>();
        for (Skill skill : Skill.values()) {
            skills.put(skill, resolver.getProperty("inquiry.skills." + skill.id() + ".provider", MOCK));
        }
        return new SkillImplementations(skills, resolver.getProperty(KNOWLEDGE_KEY, MOCK));
    }

    /**
     * @param skill スキル
     * @return そのスキルの実装名
     */
    public String providerOf(Skill skill) {
        return skills.get(skill);
    }

    /**
     * @return 一次情報の検索の実装名
     */
    public String knowledgeProvider() {
        return knowledge;
    }

    /**
     * @param skill スキル
     * @return そのスキルがモックで動いている場合は true
     */
    public boolean isMock(Skill skill) {
        return MOCK.equals(skills.get(skill));
    }

    /**
     * @return スキルまたは一次情報の検索に1つでもモックがある場合は true
     */
    public boolean includesMock() {
        return skills.values().stream().anyMatch(MOCK::equals) || MOCK.equals(knowledge);
    }

    /**
     * @return スキルの識別子から実装名への対応（{@code GET /api/config} の {@code skills}）
     */
    public Map<String, String> byId() {
        Map<String, String> result = new LinkedHashMap<>();
        skills.forEach((skill, provider) -> result.put(skill.id(), provider));
        return result;
    }

    /**
     * @return モックで動いているものの名前（スキルの識別子と、一次情報の検索なら {@code knowledge}）
     */
    public java.util.List<String> mockNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        skills.forEach((skill, provider) -> {
            if (MOCK.equals(provider)) {
                names.add(skill.id());
            }
        });
        if (MOCK.equals(knowledge)) {
            names.add("knowledge");
        }
        return names;
    }
}
