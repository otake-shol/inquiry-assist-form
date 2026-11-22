package dev.inquiryassist.core;

/**
 * 整形後の問い。本人が確認・修正したうえで0次回答と担当者への送付に使う。
 *
 * <p>緊急度は持たない。緊急度は本人が申告する問い合わせの属性で、AI が文章から決めるものではないため。
 *
 * @param summary 本人に見せる言い直しの文章。必須（空白のみは不可）
 * @param type 種類。決まっていない場合は {@code null}
 * @param targetSystem 対象システムや画面。特定できない場合は {@code null}
 * @param goal やりたいこと・知りたいこと。必須
 * @param whatHappened 起きたこと。該当しない場合は {@code null}
 * @param expectedResult 期待した結果。該当しない場合は {@code null}
 */
public record StructuredInquiry(
        String summary,
        InquiryType type,
        String targetSystem,
        String goal,
        String whatHappened,
        String expectedResult) {

    /**
     * 言い直しの文章だけを差し替えた複製を返す。ほかの項目は変えない。
     *
     * @param newSummary 新しい言い直しの文章
     * @return 差し替え後の整形結果
     */
    public StructuredInquiry withSummary(String newSummary) {
        return new StructuredInquiry(newSummary, type, targetSystem, goal, whatHappened, expectedResult);
    }

    public StructuredInquiry {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary は必須");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal は必須");
        }
    }
}
