package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.SystemOption;

/**
 * 聞き手。足りない情報を、非エンジニアが答えられる質問にする。用語対応表とFAQの軽い検索を使える。
 *
 * <p>仕様確認・相談と不具合の疑いでだけ呼ぶ。失敗しても本人を止めず、質問なしで先へ進む。
 */
public interface InterviewerSkill {

    /**
     * 追加質問を作る。対象システムが決まっている場合は、画面や対象を尋ねる質問を出さない。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 種別
     * @param system 決まっている対象システム。決まっていなければ {@code null}
     * @param scope 投稿者の閲覧範囲
     * @return 追加質問
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Interview> ask(String utterance, InquiryCategory category, SystemOption system, AccessScope scope);
}
