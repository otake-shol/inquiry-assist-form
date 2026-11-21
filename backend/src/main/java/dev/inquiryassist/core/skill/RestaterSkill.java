package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import java.util.List;

/** 言い直し。言い直しの文章と構造化データを作る。作り直しも担う。用語対応表を使える。 */
public interface RestaterSkill {

    /**
     * 発話と追加質問への回答から、整形後の問いを作る。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 本人が確認した種別
     * @param answers 追加質問と回答
     * @param system 確定した対象システム。決まっていなければ {@code null}。{@code targetSystem} にはその名前を入れる
     * @return 整形後の問い
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<StructuredInquiry> restate(
            String utterance, InquiryCategory category, List<AnsweredQuestion> answers, SystemOption system);

    /**
     * 本人が違う点を一言で伝えたときに、言い直しを作り直す。
     *
     * @param utterance 本人が書いた元の発話
     * @param current 直前の整形後の問い
     * @param note 本人が伝えた、違う点の一言
     * @return 作り直した整形後の問い
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<StructuredInquiry> revise(String utterance, StructuredInquiry current, String note);
}
