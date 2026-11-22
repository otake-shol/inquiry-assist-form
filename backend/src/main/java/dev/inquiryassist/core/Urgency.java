package dev.inquiryassist.core;

/** 問い合わせの緊急度。本人が申告するもので、AI が文章から決めるものではない。種類とは別に持つ。 */
public enum Urgency {
    /** 通常。 */
    NORMAL,
    /** 急ぎ。 */
    HURRY,
    /** 業務が止まっている。入口の質問を終えた時点で、言い直しの確認と調査を待たずに担当者へ送る。 */
    BLOCKED
}
