package dev.inquiryassist.core.lifecycle;

/**
 * 問い合わせ1件の状態。区間、誰の番か、待っていること、終わり方の4つで表す。
 *
 * @param phase 区間
 * @param turn 誰の番か。終わった件は {@code null}
 * @param awaiting 待っていること。終わった件は {@code null}
 * @param ending 終わり方。終わっていない件は {@code null}
 */
public record InquiryState(Phase phase, Turn turn, Awaiting awaiting, Ending ending) {

    /** 問い合わせができた直後の状態。 */
    public static final InquiryState START = new InquiryState(Phase.INTAKE, Turn.ASKER, Awaiting.INTAKE_ANSWER, null);

    /**
     * @return 終わっている場合は true
     */
    public boolean closed() {
        return phase == Phase.CLOSED;
    }
}
