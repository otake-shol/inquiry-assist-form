package dev.inquiryassist.core;

/**
 * 詳しい質問と本人の答えの組。言い直しに渡す。
 *
 * @param question 聞いた質問
 * @param answer 本人の答え。「わからない」を選んだ場合は {@code null}
 */
public record AnsweredQuestion(FollowUpQuestion question, String answer) {

    public AnsweredQuestion {
        if (question == null) {
            throw new IllegalArgumentException("question は必須");
        }
    }

    /**
     * 本人が「わからない」を選んだかを返す。
     *
     * @return 回答がない場合は true
     */
    public boolean unknown() {
        return answer == null || answer.isBlank();
    }
}
