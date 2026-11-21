package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.TechnicalMemo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * オペレータ向けの問い合わせの全内容。
 *
 * @param id 識別子
 * @param filedAt 担当者に送られた時刻
 * @param askerId 投稿者
 * @param utterance 元の発話
 * @param categoryByAi AI が判定した種別
 * @param category 確認済みの種別
 * @param system 対象システム
 * @param systemGuessed 対象システムを AI が推測したか
 * @param followUps 追加質問と回答
 * @param structured 整形後の問い
 * @param revisionNotes 本人が「少し違います」で伝えた一言
 * @param answer 0次回答。出典は照合に落ちたものも含む
 * @param sentImmediately 「今すぐ送る」による送付か
 * @param read 既読か
 * @param slackStatus Slack への投稿の状態
 */
public record OperatorDetail(
        UUID id,
        Instant filedAt,
        String askerId,
        String utterance,
        InquiryCategory categoryByAi,
        InquiryCategory category,
        InquiryView.SystemView system,
        boolean systemGuessed,
        List<FollowUpDetail> followUps,
        InquiryView.StructuredView structured,
        List<String> revisionNotes,
        AnswerDetail answer,
        boolean sentImmediately,
        boolean read,
        SlackStatus slackStatus) {

    /**
     * 追加質問と回答。
     *
     * @param text 質問文
     * @param answer 回答。「わからない」と未回答は null
     * @param status 本人の対応（回答あり、わからない、答える前に送られた）
     */
    public record FollowUpDetail(String text, String answer, FollowUpStatus status) {}

    /** 追加質問への本人の対応。 */
    public enum FollowUpStatus {
        /** 本人が答えた。 */
        ANSWERED,
        /** 本人が「わからない」を選んだ。 */
        UNKNOWN,
        /** 本人が答える前に担当者へ送られた。 */
        NOT_ANSWERED
    }

    /**
     * 0次回答。
     *
     * @param grounded 照合できた出典に根拠があるか
     * @param answered 確かめられた事実が本人の問いに答えているか。grounded でなければ常に false
     * @param askerExplanation 本人向けの説明
     * @param memo 技術メモ
     * @param citations 出典。照合結果つき
     */
    public record AnswerDetail(
            boolean grounded,
            boolean answered,
            String askerExplanation,
            TechnicalMemo memo,
            List<CitationDetail> citations) {}

    /**
     * 照合結果つきの出典。
     *
     * @param citation 出典
     * @param verified 照合できたか
     */
    public record CitationDetail(
            Citation.SourceType sourceType,
            String repository,
            String path,
            int startLine,
            int endLine,
            String quote,
            String revision,
            boolean verified) {}
}
