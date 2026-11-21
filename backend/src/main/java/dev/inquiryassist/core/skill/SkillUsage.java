package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.Usage;

/**
 * スキル1回の呼び出しの利用量。
 *
 * @param skill 呼び出したスキル
 * @param usage 実行条件と利用量
 * @param failed 失敗した呼び出しか。失敗でも費用がかかるため記録する
 */
public record SkillUsage(Skill skill, Usage usage, boolean failed) {

    /**
     * 成功した呼び出しの利用量を作る。
     *
     * @param skill 呼び出したスキル
     * @param usage 実行条件と利用量
     */
    public SkillUsage(Skill skill, Usage usage) {
        this(skill, usage, false);
    }
}
