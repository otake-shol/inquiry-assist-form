package dev.inquiryassist.core.intake;

/** 答えた質問が、いま聞いている質問と違うことを表す。HTTP では 409（{@code INVALID_STATE}）に対応する。 */
public class QuestionMismatchException extends IllegalStateException {

    /**
     * @param message 理由
     */
    public QuestionMismatchException(String message) {
        super(message);
    }
}
