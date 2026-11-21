package dev.inquiryassist.core;

/** モデルが回答を拒否したことを表す。画面では「担当者に送る」へ誘導する。 */
public class AiRefusalException extends RuntimeException {

    private final transient Usage usage;

    /**
     * @param message 拒否の内容
     */
    public AiRefusalException(String message) {
        this(message, null);
    }

    /**
     * @param message 拒否の内容
     * @param usage 失敗した呼び出しの利用量。分からなければ {@code null}
     */
    public AiRefusalException(String message, Usage usage) {
        super(message);
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
