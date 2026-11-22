package dev.inquiryassist.core.lifecycle;

/** 担当者が本人に返す発言の種類（返信の操作で選べるもの）。 */
public enum ReplyKind {
    /** 聞き返し。本人の返事を待つ。 */
    ASK,
    /** 結果を伝える。本人が確かめる。 */
    RESULT
}
