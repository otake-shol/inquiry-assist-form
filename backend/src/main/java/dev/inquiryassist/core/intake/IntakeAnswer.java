package dev.inquiryassist.core.intake;

/**
 * 入口の質問への答え。
 *
 * @param kind 答え方
 * @param optionId 選んだ選択肢（または受付が置いた値）の識別子。{@code OPTION} と {@code INFERRED} だけ
 * @param label 選んだ時点の名前。{@code OPTION} と {@code INFERRED} だけ
 * @param text 本人が書いた文章。{@code TEXT} だけ
 */
public record IntakeAnswer(AnswerKind kind, String optionId, String label, String text) {

    /**
     * 選択肢を選んだ答え。
     *
     * @param id 選択肢の識別子
     * @param label 選んだ時点の名前
     * @return 答え
     */
    public static IntakeAnswer option(String id, String label) {
        return new IntakeAnswer(AnswerKind.OPTION, id, label, null);
    }

    /**
     * 文章で答えた答え。
     *
     * @param text 文章
     * @return 答え
     */
    public static IntakeAnswer text(String text) {
        return new IntakeAnswer(AnswerKind.TEXT, null, null, text);
    }

    /**
     * 「わからない」の答え。
     *
     * @return 答え
     */
    public static IntakeAnswer unknown() {
        return new IntakeAnswer(AnswerKind.UNKNOWN, null, null, null);
    }

    /**
     * 補足を書かずに進んだ答え。
     *
     * @return 答え
     */
    public static IntakeAnswer skipped() {
        return new IntakeAnswer(AnswerKind.SKIPPED, null, null, null);
    }

    /**
     * 受付が文章から置いた答え。
     *
     * @param id 置いた値の識別子
     * @param label 置いた時点の名前
     * @return 答え
     */
    public static IntakeAnswer inferred(String id, String label) {
        return new IntakeAnswer(AnswerKind.INFERRED, id, label, null);
    }

    /**
     * 置き場所を決める答えかを返す。
     *
     * @return 選んだか、受付が置いた場合は true
     */
    public boolean decides() {
        return kind == AnswerKind.OPTION || kind == AnswerKind.INFERRED;
    }
}
