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
     * <p>対象システムは本人が選んでいればそれを使い、推測しない。選んでいなければ候補から推測して返す。
     * 対象システムが決まっている場合は、画面や対象を尋ねる質問を出さない。
     *
     * @param utterance 本人が書いた元の発話
     * @param candidates 対象システムの候補
     * @param selected 本人が選んだ対象システム。選んでいなければ {@code null}
     * @return 種別、追加質問、推測した対象システム
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<TriageResult> triage(
            String utterance, List<SystemOption> candidates, SystemOption selected);

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
    AiResult<StructuredInquiry> structure(
            String utterance,
            InquiryCategory category,
            List<AnsweredQuestion> answers,
            SystemOption system);

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
