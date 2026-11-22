package dev.inquiryassist.inquiry;

/**
 * 返信の下書き。
 *
 * @param body 下書きの文章
 * @param language 下書きを書いた言語（本人の言語）
 */
public record DraftResult(String body, String language) {}
