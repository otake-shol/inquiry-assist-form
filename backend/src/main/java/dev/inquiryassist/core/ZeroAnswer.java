package dev.inquiryassist.core;

import java.util.List;

/**
 * 0次回答。本人向けの説明と担当者向けの技術メモを持つ。
 *
 * <p>調査・照合・説明の結果から {@code AnswerPipeline} が組み立てる。照合で出典が減った回答を、
 * 説明の文章を残したまま作り直す手段は持たせない（説明は照合の後に、確かめられた事実だけから書く）。
 *
 * <p>根拠あり（{@code grounded}）の回答は出典を1つ以上持つ。出典のない回答は「わからない」として扱う。
 *
 * @param grounded 一次情報に根拠があるか
 * @param askerExplanation 本人向けの説明。業務の言葉で書く。根拠がない場合は {@code null}
 * @param memo 担当者向けの技術メモ
 * @param citations 出典
 */
public record ZeroAnswer(
        boolean grounded, String askerExplanation, TechnicalMemo memo, List<Citation> citations) {

    public ZeroAnswer {
        memo = memo == null ? TechnicalMemo.empty() : memo;
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (grounded && citations.isEmpty()) {
            throw new IllegalArgumentException("根拠ありの回答には出典が必要");
        }
        if (grounded && (askerExplanation == null || askerExplanation.isBlank())) {
            throw new IllegalArgumentException("根拠ありの回答には本人向けの説明が必要");
        }
    }

    /**
     * 根拠が見つからなかった回答を作る。本人には「わからない」と示す。
     *
     * @param memo 調査の経過。担当者への引き継ぎに使う
     * @return 根拠なしの回答
     */
    public static ZeroAnswer ungrounded(TechnicalMemo memo) {
        return new ZeroAnswer(false, null, memo, List.of());
    }
}
