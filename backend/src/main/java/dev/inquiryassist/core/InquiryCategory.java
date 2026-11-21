package dev.inquiryassist.core;

/**
 * 問い合わせの種別。種別ごとに経路（0次回答の有無、起票の要否）が決まる。
 *
 * <p>経路の定義は要件定義の「種別ごとの経路」表に対応する。
 */
public enum InquiryCategory {
    /** 仕様確認・相談。0次回答を本人に提示し、未解決のときだけ起票する。 */
    SPEC_QUESTION,
    /** 不具合の疑い。0次回答を「現在の動作」として提示し、必ず起票する。 */
    SUSPECTED_BUG,
    /** 作業依頼。0次回答は出さず、必ず起票する。 */
    WORK_REQUEST,
    /** 障害。このフォームでは受け付けず、既存の障害連絡の経路を案内する。 */
    INCIDENT;

    /**
     * この種別で0次回答を生成するかを返す。
     *
     * @return 0次回答を生成する場合は true
     */
    public boolean receivesZeroAnswer() {
        return this == SPEC_QUESTION || this == SUSPECTED_BUG;
    }

    /**
     * 本人の解決判定にかかわらず必ず起票するかを返す。
     *
     * @return 必ず起票する場合は true
     */
    public boolean alwaysFiled() {
        return this == SUSPECTED_BUG || this == WORK_REQUEST;
    }

    /**
     * このフォームで受け付ける種別かを返す。
     *
     * @return 受け付ける場合は true。障害は false
     */
    public boolean acceptedByForm() {
        return this != INCIDENT;
    }
}
