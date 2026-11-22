package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import java.util.List;

/** 言い直し。言い直しの文章と構造化データを作る。作り直しも担う。用語対応表を使える。 */
public interface RestaterSkill {

    /**
     * 置き場所、詳しい質問への答え、本人が書いた文章から、整形後の問いを作る。作り直しも別のメソッドにせず、これで行う。
     *
     * <p>本人が違う点を伝えた場合は、その一言（{@code corrections}）を足して、最初から言い直す。
     * 前回の言い直しの文章は渡さない。前回の文章を渡すと、直すべき記述がそこに残っているため、
     * 訂正と食い違う文になりやすい。緊急度は出力に含めない（本人の申告を、AI が書き換えないため）。
     *
     * @param utterance 本人が書いた文章。書いていなければ {@code null}
     * @param type 種類。決まっていなければ {@code null}
     * @param system システム。決まっていなければ {@code null}。{@code targetSystem} にはその名前を入れる
     * @param function 機能。決まっていなければ {@code null}
     * @param answers 詳しい質問と答え
     * @param corrections 本人が伝えた違う点の一言。伝えられた順。空なら最初の言い直し
     * @return 整形後の問い
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<StructuredInquiry> restate(
            String utterance,
            InquiryType type,
            SystemOption system,
            FunctionOption function,
            List<AnsweredQuestion> answers,
            List<String> corrections);
}
