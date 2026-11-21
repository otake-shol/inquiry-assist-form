package dev.inquiryassist.core;

import java.util.List;

/**
 * フォームがAIに頼む処理の境界。モック実装と Bedrock 実装をこの単位で差し替える。
 *
 * <p>画面やHTTPの層に依存しない。Slack の入口や評価の自動実行からも同じ処理を呼ぶ。
 */
public interface InquiryAssistant {

    /**
     * 最初の自由記述から種別を判定し、追加質問を作る。コード検索は行わない。
     *
     * @param utterance 本人が書いた元の発話
     * @return 種別と追加質問
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<TriageResult> triage(String utterance);

    /**
     * 発話と追加質問への回答から、整形後の問いを作る。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 本人が確認した種別
     * @param answers 追加質問と回答
     * @return 整形後の問い
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<StructuredInquiry> structure(
            String utterance, InquiryCategory category, List<AnsweredQuestion> answers);

    /**
     * 整形後の問いに対する0次回答を作る。一次情報の検索はここに集約する。
     *
     * <p>返す出典は未照合。呼び出し側が照合し、{@link ZeroAnswer#withVerifiedCitations} で絞り込む。
     *
     * @param inquiry 本人が確認した整形後の問い。元の発話や追加質問のやり取りは渡さない
     * @param scope 投稿者の閲覧範囲
     * @param progress 進捗の通知先
     * @return 0次回答
     * @throws IllegalArgumentException 0次回答を出さない種別を渡した場合
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<ZeroAnswer> answer(StructuredInquiry inquiry, AccessScope scope, ProgressListener progress);

    /** 調査の進捗を受け取る。画面の進捗表示に使う。 */
    @FunctionalInterface
    interface ProgressListener {

        /**
         * 進捗を1件通知する。
         *
         * @param message 本人に見せる進捗の文言
         */
        void onProgress(String message);
    }
}
