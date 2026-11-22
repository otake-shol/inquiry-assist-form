package dev.inquiryassist.inquiry;

/** 調査の結果を誰に見せるか（契約書「調査の状況と、裏での調査」）。 */
public enum InvestigationAudience {
    /** 本人が待っている間に出た答え。本人にも見せる。 */
    ASKER,
    /** 裏での調査の結果。担当者だけに見せる。 */
    OPERATOR
}
