package dev.inquiryassist.core;

/**
 * 問い合わせの種類。種類ごとの経路（調べるか、答えの後にどうなるか）をここに集約する。
 *
 * <p>経路の定義は {@code docs/api-contract.md} の「種類ごとの経路」に対応する。障害は種類ではなく、
 * 緊急度（{@link Urgency#BLOCKED}）として持つ。種類が決まっていない問い合わせは {@link #HOW_IT_WORKS} と同じ経路にする。
 */
public enum InquiryType {
    /** 使い方を知りたい。調べ、答えを本人が確かめる。 */
    HOW_TO,
    /** 仕組みを知りたい。調べ、答えを本人が確かめる。 */
    HOW_IT_WORKS,
    /** おかしいかもしれない。調べるが、意図した動きかは担当者が判断するため必ず担当者に送る。 */
    LOOKS_WRONG,
    /** やってほしい。調べ、本人が自分でできる手順が見つかった場合だけ本人に確かめてもらう。 */
    DO_FOR_ME,
    /** 変えてほしい。調べず、確定と同時に担当者に送る。 */
    CHANGE_REQUEST;

    /** 種類が決まっていない場合に使う経路の種類。 */
    private static final InquiryType UNDECIDED_ROUTE = HOW_IT_WORKS;

    /**
     * 種類が決まっていなければ、{@link #HOW_IT_WORKS} と同じ経路になる種類を返す。
     *
     * @param type 種類。決まっていなければ {@code null}
     * @return 経路を決めるのに使う種類
     */
    public static InquiryType routeOf(InquiryType type) {
        return type == null ? UNDECIDED_ROUTE : type;
    }

    /**
     * この種類で、調査から説明までを行うかを返す。
     *
     * @return 行う場合は true。{@link #CHANGE_REQUEST} は false
     */
    public boolean investigates() {
        return this != CHANGE_REQUEST;
    }

    /**
     * 答えの完了後、本人の確かめを待たずに担当者へ送るかを返す。
     *
     * @param grounded 照合できた根拠があるか
     * @param answered 確かめられた事実が本人の問いに答えているか
     * @return 担当者へ送る場合は true。false なら答えを本人が確かめる（{@code ANSWERED}）
     */
    public boolean filesAfterAnswer(boolean grounded, boolean answered) {
        return switch (this) {
            case LOOKS_WRONG -> true;
            // 本人が自分でできる手順が見つかった場合だけ、本人に確かめてもらう
            case DO_FOR_ME -> !(grounded && answered);
            case HOW_TO, HOW_IT_WORKS -> false;
            case CHANGE_REQUEST -> true;
        };
    }

    /**
     * 言い直しを確定した時点で担当者へ送るかを返す。
     *
     * @return 送る場合は true。{@link #CHANGE_REQUEST} だけ
     */
    public boolean filesOnConfirm() {
        return this == CHANGE_REQUEST;
    }
}
