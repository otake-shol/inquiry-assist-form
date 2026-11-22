package dev.inquiryassist.inquiry;

/** AI の案（助言）の状況（契約書「AI の案（advice）」）。 */
public enum AdviceStatus {
    /** 作っていない（確かめられた事実がない場合を含む）。 */
    NOT_RUN,
    /** 作っている。 */
    RUNNING,
    /** できた。 */
    DONE,
    /** 失敗した。 */
    FAILED
}
