package dev.inquiryassist.core;

/** モデルを呼び出せなかったことを表す（接続失敗、上限超過、読めない出力など）。 */
public class AiUnavailableException extends RuntimeException {

    private final transient Usage usage;

    /**
     * @param message 失敗の内容
     * @param cause 元の例外
     */
    public AiUnavailableException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /**
     * @param message 失敗の内容
     * @param cause 元の例外
     * @param usage 失敗した呼び出しの利用量。分からなければ {@code null}
     */
    public AiUnavailableException(String message, Throwable cause, Usage usage) {
        super(message, cause);
        this.usage = usage;
    }

    /**
     * 失敗した呼び出しの利用量を返す。失敗でも費用や記録の対象になるため、分かる場合は持たせる。
     *
     * @return 利用量。分からなければ {@code null}
     */
    public Usage usage() {
        return usage;
    }
}
