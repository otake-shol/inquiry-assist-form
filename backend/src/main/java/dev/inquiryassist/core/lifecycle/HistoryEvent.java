package dev.inquiryassist.core.lifecycle;

/** 状態の履歴に残す、状態が変わった出来事。 */
public enum HistoryEvent {
    /** 問い合わせができた。 */
    CREATED,
    /** 問いを組み立てた（言い直しの確認へ）。 */
    QUESTION_COMPOSED,
    /** 入口の質問へ戻った。 */
    REOPENED_INTAKE,
    /** 問いを確かめた。 */
    QUESTION_CONFIRMED,
    /** AI が答えた（本人が確かめる）。 */
    ANSWERED_BY_AI,
    /** 担当者に渡した。 */
    HANDED_OVER,
    /** 担当者が本人に聞き返した。 */
    ASKED_BACK,
    /** 本人が聞き返しに答えた。 */
    REPLIED,
    /** 担当者が結果を伝えた。 */
    RESULT_DELIVERED,
    /** 本人が解決を伝えた。 */
    RESOLVED,
    /** 担当者が見送った。 */
    DECLINED,
    /** 本人が取り下げた。 */
    WITHDRAWN,
    /** 終わった件を、本人の追記で開き直した。 */
    REOPENED,
    /** 担当者が区間を変えた。 */
    PHASE_CHANGED,
    /** 担当者が置き直した。 */
    PLACEMENT_CHANGED
}
