package dev.inquiryassist.core.lifecycle;

/** 誰の番か。次に動く人。終わった件では持たない（{@code null}）。 */
public enum Turn {
    /** 本人。 */
    ASKER,
    /** AI。 */
    AI,
    /** 担当者。 */
    OPERATOR
}
