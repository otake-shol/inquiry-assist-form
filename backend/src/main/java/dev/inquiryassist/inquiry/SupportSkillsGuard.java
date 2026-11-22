package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.skill.Skill;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 助言、下書き、訳は、モックの実装だけを持つ（担当者を支えるスキル）（骨組みの段階。契約書「担当者を支える2つのスキル」「訳のスキル」）。
 * {@code inquiry.skills.advisor.provider}、{@code inquiry.skills.drafter.provider}、{@code inquiry.skills.translator.provider} に {@code mock} 以外を指定したら、起動を止める
 * （実装がないまま、モックでないと思って動かさないため）。
 */
@Component
class SupportSkillsGuard {

    /**
     * @param implementations スキルごとの実装
     * @throws IllegalStateException 助言、下書き、訳のどれかに、{@code mock} 以外が指定されている場合
     */
    SupportSkillsGuard(SkillImplementations implementations) {
        for (Skill skill : List.of(Skill.ADVISOR, Skill.DRAFTER, Skill.TRANSLATOR)) {
            if (!implementations.isMock(skill)) {
                throw new IllegalStateException("inquiry.skills." + skill.id() + ".provider は mock だけに対応しています（指定: "
                        + implementations.providerOf(skill) + "）。実際のモデルの実装は、まだありません");
            }
        }
    }
}
