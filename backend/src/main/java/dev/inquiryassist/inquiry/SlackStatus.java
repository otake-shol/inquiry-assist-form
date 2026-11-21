package dev.inquiryassist.inquiry;

/** Slack への投稿の状態（オペレータ詳細の {@code slackStatus}）。 */
public enum SlackStatus {
    /** 投稿できた。 */
    POSTED,
    /** 投稿先が設定されていないため、投稿しない。 */
    SKIPPED,
    /** 投稿先はあるが、結果がまだ出ていない。 */
    PENDING,
    /** 投稿に失敗した。 */
    FAILED
}
