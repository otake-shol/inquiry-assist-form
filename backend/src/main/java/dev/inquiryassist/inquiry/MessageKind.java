package dev.inquiryassist.inquiry;

/** やり取りの発言の種類。 */
public enum MessageKind {
    /** 担当者の聞き返し。 */
    ASK,
    /** 担当者が伝えた結果。 */
    RESULT,
    /** 担当者の見送りの連絡。 */
    DECLINE,
    /** 本人の追記。 */
    NOTE
}
