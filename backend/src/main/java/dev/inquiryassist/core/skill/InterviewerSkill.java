package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;

/**
 * 聞き手。足りない情報を、非エンジニアが答えられる質問にする。用語対応表とFAQの軽い検索を使える。
 *
 * <p>置き場所の質問が終わり、マスの設定に詳しい質問がないときに呼ぶ。失敗しても本人を止めず、詳しい質問なしで先へ進む。
 */
public interface InterviewerSkill {

    /**
     * 詳しい質問を作る（0〜2個）。システムや機能が決まっている場合は、それを尋ねる質問を出さない。
     *
     * @param utterance 本人が書いた文章。書いていなければ {@code null}
     * @param type 種類。決まっていなければ {@code null}
     * @param system システム。決まっていなければ {@code null}
     * @param function 機能。決まっていなければ {@code null}
     * @param scope 投稿者の閲覧範囲
     * @return 詳しい質問
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Interview> ask(
            String utterance, InquiryType type, SystemOption system, FunctionOption function, AccessScope scope);
}
