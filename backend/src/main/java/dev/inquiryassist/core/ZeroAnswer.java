package dev.inquiryassist.core;

import java.util.List;

/**
 * 0次回答。1回の調査から本人向けの説明と担当者向けの技術メモを出す。
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

    /**
     * 照合できた出典だけを残した回答を返す。
     *
     * <p>照合できた出典が1つもなければ、回答文の内容にかかわらず根拠なしに落とす。
     *
     * @param verified 実在を照合できた出典
     * @return 出典を差し替えた回答
     */
    public ZeroAnswer withVerifiedCitations(List<Citation> verified) {
        if (verified == null || verified.isEmpty()) {
            return ungrounded(memo);
        }
        return new ZeroAnswer(grounded, askerExplanation, memo, verified);
    }
}
