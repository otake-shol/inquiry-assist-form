package dev.inquiryassist.inquiry;

/** 仕様の持ち主の決定の種類（契約書「仕様の持ち主への相談」）。 */
public enum DecisionKind {
    /** 意図どおり。 */
    AS_INTENDED,
    /** 不具合。 */
    DEFECT,
    /** 受ける。 */
    ACCEPT,
    /** 受けない。 */
    REJECT,
    /** 採用する。 */
    ADOPT,
    /** 見送る。 */
    DEFER,
    /** その他。 */
    OTHER
}
