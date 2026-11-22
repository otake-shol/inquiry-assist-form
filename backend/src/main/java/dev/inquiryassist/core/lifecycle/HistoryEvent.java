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
    /** 返事がないことを、本人に知らせた（状態は変わらない）。 */
    REMINDED,
    /** 返事がないまま期間が過ぎて、終わった。 */
    ANSWERED,
    /** 担当者が仕様の持ち主に相談した。 */
    CONSULTED,
    /** 仕様の持ち主が、相談に答えて決めた（先例が1つできた）。 */
    DECIDED,
    /** 担当者が相談を取り下げた。 */
    CONSULTATION_CANCELED,
    /** 担当者が区間を変えた（区間を手で変える操作は、なくした。以前の履歴を読むために残す）。 */
    PHASE_CHANGED,
    /** 担当者が置き直した。 */
    PLACEMENT_CHANGED
}
