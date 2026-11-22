package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.FollowUpQuestion;
import java.util.List;

/**
 * 聞き手の出力。
 *
 * @param questions 詳しい質問。最大 {@link #MAX_QUESTIONS} 個。空なら質問しない
 */
public record Interview(List<FollowUpQuestion> questions) {

    /** 詳しい質問の上限。一問一答で聞くため少なく保つ。 */
    public static final int MAX_QUESTIONS = 2;

    public Interview {
        questions = questions == null ? List.of() : List.copyOf(questions);
        if (questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("詳しい質問は最大 " + MAX_QUESTIONS + " 個");
        }
    }

    /**
     * 質問のない結果を返す。
     *
     * @return 質問なし
     */
    public static Interview none() {
        return new Interview(List.of());
    }
}
