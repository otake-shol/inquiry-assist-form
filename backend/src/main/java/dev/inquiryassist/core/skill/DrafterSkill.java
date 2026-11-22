package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;

/**
 * 下書き。担当者が本人に送る文章（聞き返し、結果）の下書きを作る。道具を持たない。読み手は本人で、本人の言語で書く。
 * 結果は保存しない（担当者の入力欄に入るだけ）。
 */
public interface DrafterSkill {

    /**
     * 下書きを作る。
     *
     * @param request 依頼
     * @return 下書き
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Draft> draft(DraftRequest request);
}
