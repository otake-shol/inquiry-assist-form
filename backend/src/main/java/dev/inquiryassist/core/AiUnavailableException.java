package dev.inquiryassist.core;

/** モデルを呼び出せなかったことを表す（接続失敗、上限超過など）。 */
public class AiUnavailableException extends RuntimeException {

    /**
     * @param message 失敗の内容
     * @param cause 元の例外
     */
    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
