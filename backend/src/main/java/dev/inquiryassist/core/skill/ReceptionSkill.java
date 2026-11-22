package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import java.util.List;

/**
 * 受付。本人が書いた文章から、まだ決まっていない置き場所（種類、システム、機能）を読み取る。道具は使わない。
 *
 * <p>本人が文章を書いたときだけ呼ぶ。選択肢を押して答えるだけの入口では呼ばない。失敗してもエラーにせず、
 * 置き場所は置かれず、質問として聞く。
 */
public interface ReceptionSkill {

    /**
     * 文章から置き場所を読み取る。本人が既に選んだ項目は変えない。
     *
     * @param utterance 本人が書いた文章
     * @param candidates システムと機能の候補
     * @param decided 本人が既に選んだ置き場所
     * @return 読み取れた置き場所。読み取れない項目は {@code null}
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Reception> receive(String utterance, List<SystemOption> candidates, Placement decided);
}
