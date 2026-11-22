package dev.inquiryassist.core.lifecycle;

/** 見送りの理由の種類。 */
public enum DeclineReason {
    /** 再現しない。 */
    NOT_REPRODUCIBLE,
    /** 既知の制限。 */
    KNOWN_LIMITATION,
    /** 対象外。 */
    OUT_OF_SCOPE,
    /** 重複。 */
    DUPLICATE,
    /** その他。 */
    OTHER
}
