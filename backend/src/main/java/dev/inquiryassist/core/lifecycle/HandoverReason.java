package dev.inquiryassist.core.lifecycle;

/** 担当者に渡した理由（契約書「担当者に渡す」）。直近に渡したときの理由を、問い合わせが持つ。 */
public enum HandoverReason {
    /** 本人が、答えを待たずに送った。 */
    SENT_NOW,
    /** 本人が、解決していないと答えた（結果への追記を含む）。 */
    UNRESOLVED,
    /** 業務が止まっている。 */
    BLOCKED,
    /** 種類の経路（{@code LOOKS_WRONG}、{@code DO_FOR_ME}、{@code CHANGE_REQUEST}）。 */
    ROUTE,
    /** AI が答えられなかった（{@code HOW_TO}、{@code HOW_IT_WORKS}、種類が決まっていない場合）。 */
    NO_ANSWER,
    /** 調査が時間内に終わらなかった。 */
    TIMEOUT,
    /** 本人が、終わった件に追記した。 */
    REOPENED,
    /** 仕様の持ち主が、相談に答えて決めた（担当者の番に戻った）。 */
    DECIDED
}
