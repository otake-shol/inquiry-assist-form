package dev.inquiryassist.eval;

import dev.inquiryassist.core.skill.Skill;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 評価の対象になるスキル。調査は道具を繰り返し使うため、まだ対象にしない。 */
enum EvalSkill {
    RECEPTION(Skill.RECEPTION),
    INTERVIEWER(Skill.INTERVIEWER),
    RESTATER(Skill.RESTATER),
    EXPLAINER(Skill.EXPLAINER);

    private final Skill skill;

    EvalSkill(Skill skill) {
        this.skill = skill;
    }

    String id() {
        return skill.id();
    }

    Skill skill() {
        return skill;
    }

    static Optional<EvalSkill> parse(String id) {
        return Arrays.stream(values()).filter(s -> s.id().equals(id)).findFirst();
    }

    static List<String> ids() {
        return Arrays.stream(values()).map(EvalSkill::id).toList();
    }
}
