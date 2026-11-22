package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import java.util.List;

/**
 * 助言。確かめられた事実から、担当者への判断の案（相談が要るかを含む）と、作業の手順の案を作る。道具を持たない
 * （調査の結果を受け取り、自分では調べない）。読み手は担当者で、技術の言葉で書く。
 *
 * <p>出力は、事実の番号で基づく事実を示す。番号の確かめと、基づく事実のない案の破棄は、通常のコード（{@code AdviceVerifier}）が行う。
 */
public interface AdvisorSkill {

    /**
     * 案を作る。
     *
     * @param inquiry 言い直した問い
     * @param type 種類。決まっていなければ {@code null}
     * @param confirmedFindings 確かめられた事実。1件以上。案の {@code basedOn} は、この並びの1から始まる番号
     * @param language 案を書く言語（既定の言語）
     * @return 判断の案と手順の案（どちらもなくてもよい）
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Advice> advise(
            StructuredInquiry inquiry, InquiryType type, List<Finding> confirmedFindings, String language);
}
