package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.InquiryCategory;

/**
 * 受付の出力。
 *
 * @param category AIが判定した種別。本人が画面で変更できる
 * @param guessedSystemId 発話から推測した対象システムの識別子。推測できなければ {@code null}
 */
public record Reception(InquiryCategory category, String guessedSystemId) {

    public Reception {
        if (category == null) {
            throw new IllegalArgumentException("category は必須");
        }
    }
}
