package dev.inquiryassist.core.skill;

/**
 * 説明の出力。
 *
 * @param text 本人向けの説明。空でない
 * @param answersQuestion 確かめられた事実が本人の問いに答えているか。迷ったら false
 */
public record Explanation(String text, boolean answersQuestion) {

    public Explanation {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text は必須");
        }
    }
}
