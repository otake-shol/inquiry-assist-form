package dev.inquiryassist.inquiry;

/** 担当者に送られた問い合わせの、担当者側の進み具合。{@code step}（本人の入力の進行位置）とは別に持つ。 */
public enum HandlingStatus {
    /** 未対応。担当者に送られた時点の値。 */
    OPEN,
    /** 対応中。 */
    IN_PROGRESS,
    /** 完了。 */
    DONE
}
