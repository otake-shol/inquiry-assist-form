package dev.inquiryassist.inquiry;

import java.util.UUID;

/**
 * 入口の答えから問いを組み立てる言い直しが、AI の失敗で終わったことを表す。答えは保存済みで、
 * {@code inquiryId} で参照できる。本人は {@code intake/compose} でやり直すか、「担当者に送る」を使える。
 *
 * <p>原因は {@link dev.inquiryassist.core.AiRefusalException} または
 * {@link dev.inquiryassist.core.AiUnavailableException}。HTTP では原因に応じて 422／503 にし、本文に id を加える。
 */
public class ComposeFailedException extends RuntimeException {

    private final UUID inquiryId;

    /**
     * @param inquiryId 保存した問い合わせの識別子
     * @param cause 言い直しで起きた AI の失敗
     */
    public ComposeFailedException(UUID inquiryId, RuntimeException cause) {
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
