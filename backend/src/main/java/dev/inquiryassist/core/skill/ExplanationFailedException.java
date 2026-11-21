package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 調査と照合は成功したが、説明が失敗したことを表す。費用をかけた調査の結果を失わないよう、結果を持つ。
 *
 * <p>原因は {@link dev.inquiryassist.core.AiRefusalException} または
 * {@link dev.inquiryassist.core.AiUnavailableException}。
 */
public class ExplanationFailedException extends RuntimeException {

    private final transient VerifiedInvestigation investigation;
    private final transient List<SkillUsage> usages;

    /**
     * @param cause 説明で起きた AI の失敗
     * @param investigation 照合後の調査結果
     * @param usages ここまでのスキルごとの利用量
     */
    public ExplanationFailedException(
            RuntimeException cause, VerifiedInvestigation investigation, List<SkillUsage> usages) {
        super(cause.getMessage(), cause);
        this.investigation = investigation;
        this.usages = List.copyOf(usages);
    }

    /**
     * @return 照合後の調査結果
     */
    public VerifiedInvestigation investigation() {
        return investigation;
    }

    /**
     * @return 調査と、失敗した説明の利用量
     */
    public List<SkillUsage> usages() {
        return usages;
    }
}
