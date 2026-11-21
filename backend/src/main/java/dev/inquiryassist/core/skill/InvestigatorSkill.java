package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.StructuredInquiry;

/**
 * 調査。一次情報を調べ、根拠つきの事実を集める。読み取り専用の検索とファイル読み取りを繰り返し使う。
 *
 * <p>返す出典は未照合。呼び出し側（{@link AnswerPipeline}）が照合する。
 */
public interface InvestigatorSkill {

    /**
     * 整形後の問いについて調べる。一次情報の検索はここに集約する。
     *
     * @param inquiry 本人が確認した整形後の問い。元の発話や追加質問のやり取りは渡さない
     * @param scope 投稿者の閲覧範囲
     * @param progress 進捗の通知先
     * @return 調査の結果
     * @throws IllegalArgumentException 0次回答を出さない種別を渡した場合
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Investigation> investigate(StructuredInquiry inquiry, AccessScope scope, ProgressListener progress);
}
