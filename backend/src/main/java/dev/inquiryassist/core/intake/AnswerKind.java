package dev.inquiryassist.core.intake;

/** 入口の質問への答え方。 */
public enum AnswerKind {
    /** 選択肢を選んだ。 */
    OPTION,
    /** 文章で答えた。 */
    TEXT,
    /** 「わからない」と答えた。 */
    UNKNOWN,
    /** 補足を書かずに進んだ。 */
    SKIPPED,
    /** 本人が書いた文章から、受付が置いた。 */
    INFERRED
}
