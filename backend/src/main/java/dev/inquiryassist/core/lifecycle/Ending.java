package dev.inquiryassist.core.lifecycle;

/** 終わり方。不足は埋まったか。 */
public enum Ending {
    /** 解決済み。本人が解決を伝えた。 */
    RESOLVED,
    /** 回答済み。結果は伝えたが、本人が確かめていない（返事がないまま期間が過ぎて終えた件）。 */
    ANSWERED,
    /** 見送り。担当者が見送った。 */
    DECLINED,
    /** 取り下げ。本人が取り下げた。 */
    WITHDRAWN
}
