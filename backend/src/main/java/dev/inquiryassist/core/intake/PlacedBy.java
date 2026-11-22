package dev.inquiryassist.core.intake;

/** 置き場所を決めたのが誰か。 */
public enum PlacedBy {
    /** 本人が選んだ。 */
    ASKER,
    /** 受付が、本人の文章から置いた。 */
    AI,
    /** 担当者が置き直した。 */
    OPERATOR
}
