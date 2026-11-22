package dev.inquiryassist.core.lifecycle;

/**
 * 見送りの内容。
 *
 * @param reasonKind 理由の種類
 * @param reason 理由の文章
 */
public record Declined(DeclineReason reasonKind, String reason) {}
