package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.SystemOption;
import java.util.List;

/** 受付。最初の自由記述から種別を判定し、対象システムを推測する。道具は使わない。 */
public interface ReceptionSkill {

    /**
     * 種別を判定し、対象システムを推測する。本人が対象システムを選んでいれば推測しない。
     *
     * @param utterance 本人が書いた元の発話
     * @param candidates 対象システムの候補
     * @param selected 本人が選んだ対象システム。選んでいなければ {@code null}
     * @return 種別と推測した対象システム
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Reception> receive(String utterance, List<SystemOption> candidates, SystemOption selected);
}
