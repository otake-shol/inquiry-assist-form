package dev.inquiryassist.core;

/** モデルが回答を拒否したことを表す。画面では「担当者に送る」へ誘導する。 */
public class AiRefusalException extends RuntimeException {

    /**
     * @param message 拒否の内容
     */
    public AiRefusalException(String message) {
        super(message);
    }
}
