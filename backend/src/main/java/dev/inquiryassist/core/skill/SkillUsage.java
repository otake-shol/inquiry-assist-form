package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.Usage;

/**
 * スキル1回の呼び出しの利用量。
 *
 * @param skill 呼び出したスキル
 * @param usage 実行条件と利用量
 * @param failed 失敗した呼び出しか。失敗でも費用がかかるため記録する
 * @param durationMillis 呼び出しにかかった時間（ミリ秒）。分からなければ null
 */
public record SkillUsage(Skill skill, Usage usage, boolean failed, Long durationMillis) {

    /**
     * 成功した呼び出しの利用量を作る。
     *
     * @param skill 呼び出したスキル
     * @param usage 実行条件と利用量
     */
    public SkillUsage(Skill skill, Usage usage) {
        this(skill, usage, false, null);
    }

    /**
     * 時間を分からないまま、呼び出しの利用量を作る。
     *
     * @param skill 呼び出したスキル
     * @param usage 実行条件と利用量
     * @param failed 失敗した呼び出しか
     */
    public SkillUsage(Skill skill, Usage usage, boolean failed) {
        this(skill, usage, failed, null);
    }
}
