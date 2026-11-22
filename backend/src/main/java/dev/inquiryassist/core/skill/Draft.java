package dev.inquiryassist.core.skill;

/**
 * 下書き。担当者が本人に送る文章の案。担当者が確かめ、直してから送る。
 *
 * @param text 下書きの文章。空でない
 */
public record Draft(String text) {

    public Draft {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("下書きの text は必須");
        }
    }
}
