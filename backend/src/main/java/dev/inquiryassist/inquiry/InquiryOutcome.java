package dev.inquiryassist.inquiry;

/** 問い合わせの結果（契約書の「結果」）。 */
public enum InquiryOutcome {
    /** 進行中。 */
    IN_PROGRESS,
    /** 自己解決。 */
    SELF_RESOLVED,
    /** 担当者に送った。 */
    FILED,
    /** 離脱。初版では自動設定しない。 */
    ABANDONED
}
