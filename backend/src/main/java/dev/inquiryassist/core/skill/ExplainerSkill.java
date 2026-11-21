package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.StructuredInquiry;
import java.util.List;

/**
 * 説明。確かめられた事実だけから、本人向けの説明を業務の言葉で書く。用語対応表を使える。
 *
 * <p>渡されるのは確かめられた事実だけで、確かめられなかった内容を知る手段を持たない。
 */
public interface ExplainerSkill {

    /**
     * 本人向けの説明を書く。あわせて、確かめられた事実が本人の問いに答えているかを判定する。
     *
     * @param inquiry 本人が確認した整形後の問い
     * @param confirmedFindings 根拠を確かめられた事実。1件以上
     * @return 本人向けの説明と、問いに答えているかの判定
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Explanation> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings);
}
