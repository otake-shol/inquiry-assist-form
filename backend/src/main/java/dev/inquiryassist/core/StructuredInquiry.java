package dev.inquiryassist.core;

/**
 * 整形後の問い。本人が確認・修正したうえで0次回答と起票に使う。
 *
 * @param category 種別
 * @param targetSystem 対象システムや画面。特定できない場合は {@code null}
 * @param goal やりたいこと・知りたいこと。必須
 * @param whatHappened 起きたこと。該当しない場合は {@code null}
 * @param expectedResult 期待した結果。該当しない場合は {@code null}
 * @param urgency 緊急度
 */
public record StructuredInquiry(
        InquiryCategory category,
        String targetSystem,
        String goal,
        String whatHappened,
        String expectedResult,
        Urgency urgency) {

    /** 緊急度。 */
    public enum Urgency {
        LOW,
        NORMAL,
        HIGH
    }

    public StructuredInquiry {
        if (category == null) {
            throw new IllegalArgumentException("category は必須");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal は必須");
        }
        urgency = urgency == null ? Urgency.NORMAL : urgency;
    }
}
