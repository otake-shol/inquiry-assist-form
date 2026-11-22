package dev.inquiryassist.inquiry;

/** 調査の状況（契約書「調査の状況と、裏での調査」）。 */
public enum InvestigationStatus {
    /** 調べていない。 */
    NOT_RUN,
    /** 調べている。 */
    RUNNING,
    /** 終わった。 */
    DONE,
    /** 失敗した。 */
    FAILED
}
