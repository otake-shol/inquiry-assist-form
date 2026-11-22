package dev.inquiryassist.core.intake;

import dev.inquiryassist.core.FollowUpQuestion;
import java.util.ArrayList;
import java.util.List;

/**
 * 入口で本人に出す質問1問。
 *
 * @param id 質問の識別子。置き場所と補足は固定（{@link #TYPE_ID} など）で、詳しい質問は設定か聞き手が決める
 * @param kind 質問の種類
 * @param text 質問文
 * @param options 選択肢。空なら文章で答える
 * @param allowsText 選択肢のほかに文章でも答えられるか。選択肢のない質問は文章で答えるため true
 * @param canSkip 書かずに進めるか。補足だけが true になりうる
 */
public record IntakeQuestion(
        String id, QuestionKind kind, String text, List<Choice> options, boolean allowsText, boolean canSkip) {

    /** 種類の質問の識別子。 */
    public static final String TYPE_ID = "type";
    /** システムの質問の識別子。 */
    public static final String SYSTEM_ID = "system";
    /** 機能の質問の識別子。 */
    public static final String FUNCTION_ID = "function";
    /** 補足の質問の識別子。 */
    public static final String NOTE_ID = "note";

    public IntakeQuestion {
        options = options == null ? List.of() : List.copyOf(options);
        // 選択肢のない質問は、文章で答える以外に答え方がない
        allowsText = allowsText || options.isEmpty();
    }

    /**
     * 聞き手が作った質問を、入口の質問にする。選択肢の識別子は {@code o1}、{@code o2}… と振る。
     *
     * @param question 聞き手の質問
     * @return 詳しい質問
     */
    public static IntakeQuestion detailOf(FollowUpQuestion question) {
        List<Choice> options = new ArrayList<>();
        for (String option : question.options()) {
            options.add(new Choice("o" + (options.size() + 1), option));
        }
        return new IntakeQuestion(question.id(), QuestionKind.DETAIL, question.text(), options, false, false);
    }
}
