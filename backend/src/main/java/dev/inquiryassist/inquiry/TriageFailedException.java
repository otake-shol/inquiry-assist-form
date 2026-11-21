package dev.inquiryassist.inquiry;

import java.util.UUID;

/**
 * 種別の判定でAIが失敗したことを表す。元の発話は保存済みで、{@code inquiryId} で参照できる。
 *
 * <p>原因は {@link dev.inquiryassist.core.AiRefusalException} または
 * {@link dev.inquiryassist.core.AiUnavailableException}。HTTP では原因に応じて 422／503 にし、本文に id を加える。
 */
public class TriageFailedException extends RuntimeException {

    private final UUID inquiryId;

    /**
     * @param inquiryId 保存した問い合わせの識別子
     * @param cause 判定で起きた AI の失敗
     */
    public TriageFailedException(UUID inquiryId, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.inquiryId = inquiryId;
    }

    /**
     * @return 保存した問い合わせの識別子
     */
    public UUID inquiryId() {
        return inquiryId;
    }
}
