package dev.inquiryassist.core.lifecycle;

/** いまの状態では実行できない操作であることを表す。HTTP では 409（{@code INVALID_STATE}）に対応する。 */
public class IllegalTransitionException extends RuntimeException {

    /**
     * @param message 理由
     */
    public IllegalTransitionException(String message) {
        super(message);
    }
}
