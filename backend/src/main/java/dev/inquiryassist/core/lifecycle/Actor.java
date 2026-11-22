package dev.inquiryassist.core.lifecycle;

/** 状態を変えた人。 */
public enum Actor {
    /** 本人。 */
    ASKER,
    /** AI。 */
    AI,
    /** 担当者。 */
    OPERATOR,
    /** 時間。返事がないまま期間が過ぎたことで起きた。 */
    TIME,
    /** 仕様の持ち主。 */
    OWNER
}
