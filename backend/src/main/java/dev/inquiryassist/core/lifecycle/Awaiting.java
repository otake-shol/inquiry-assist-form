package dev.inquiryassist.core.lifecycle;

/** 待っていること。番の人に、何をしてもらうのを待っているか。画面は、これを見て描くものを決める。 */
public enum Awaiting {
    /** 本人が、入口の質問に答える。 */
    INTAKE_ANSWER,
    /** 本人が、組み立てた問い（言い直し）を確かめる。 */
    QUESTION_CONFIRMATION,
    /** AI が、調べて答えを作る。 */
    INVESTIGATION,
    /** 本人が、結果（AI の答え、または担当者が伝えた結果）を確かめる。 */
    RESULT_CONFIRMATION,
    /** 担当者が受けている。 */
    HANDLING,
    /** 担当者が聞き返し、本人の返事を待っている。 */
    ASKER_REPLY,
    /** 仕様の持ち主が、担当者の相談に答えて決める。 */
    CONSULTATION
}
