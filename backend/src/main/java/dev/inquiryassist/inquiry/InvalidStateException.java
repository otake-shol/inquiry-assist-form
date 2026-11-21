package dev.inquiryassist.inquiry;

/** その段階では実行できない操作を表す。HTTP では 409 {@code INVALID_STATE}。 */
public class InvalidStateException extends RuntimeException {

    /**
     * @param message 実行できない理由
     */
    public InvalidStateException(String message) {
        super(message);
    }
}
