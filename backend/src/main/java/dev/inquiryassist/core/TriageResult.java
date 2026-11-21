package dev.inquiryassist.core;

import java.util.List;

/**
 * 最初の自由記述に対する判定結果。種別と追加質問をまとめて返す。
 *
 * @param category AIが判定した種別。本人が画面で変更できる
 * @param questions 追加質問。最大 {@link #MAX_QUESTIONS} 個。障害では空
 * @param guessedSystemId 発話から推測した対象システムの識別子。推測できなければ {@code null}
 */
public record TriageResult(
        InquiryCategory category, List<FollowUpQuestion> questions, String guessedSystemId) {

    /**
     * 対象システムを推測しない結果を作る。
     *
     * @param category AIが判定した種別
     * @param questions 追加質問
     */
    public TriageResult(InquiryCategory category, List<FollowUpQuestion> questions) {
        this(category, questions, null);
    }

    /** 追加質問の上限。1往復で終えるため少なく保つ。 */
    public static final int MAX_QUESTIONS = 3;

    public TriageResult {
        if (category == null) {
            throw new IllegalArgumentException("category は必須");
        }
        questions = questions == null ? List.of() : List.copyOf(questions);
        if (questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("追加質問は最大 " + MAX_QUESTIONS + " 個");
        }
        if (category == InquiryCategory.INCIDENT && !questions.isEmpty()) {
            throw new IllegalArgumentException("障害では追加質問を出さない");
        }
    }
}
