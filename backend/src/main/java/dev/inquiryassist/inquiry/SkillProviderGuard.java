package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.skill.Skill;
import org.springframework.stereotype.Component;

/**
 * 8つのスキルは、モックの実装だけを持つ（骨組みの段階。実際のモデルの実装は、別の場所で作る）。
 * {@code inquiry.skills.<スキル>.provider} に {@code mock} 以外を指定したら、起動を止める
 * （実装がないまま、モックでないと思って動かさないため）。実際のモデルの実装を足すときは、その実装を持つスキルを、ここの検査から外す。
 */
@Component
class SkillProviderGuard {

    /**
     * @param implementations スキルごとの実装
     * @throws IllegalStateException どれかのスキルに、{@code mock} 以外が指定されている場合
     */
    SkillProviderGuard(SkillImplementations implementations) {
        for (Skill skill : Skill.values()) {
            if (!implementations.isMock(skill)) {
                throw new IllegalStateException("inquiry.skills." + skill.id() + ".provider は mock だけに対応しています（指定: "
                        + implementations.providerOf(skill) + "）。実際のモデルの実装は、まだありません");
            }
        }
    }
}
