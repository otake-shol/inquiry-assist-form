package dev.inquiryassist.inquiry;

import java.util.List;

/**
 * 訳（契約書「訳」）。原文に添えるもので、原文を置き換えない。
 *
 * @param from 原文の言語（本人の言語）
 * @param to 訳す先の言語（見る人の言語）
 * @param items 訳した文章。契約書の順（本人が書いた文章、言い直し、説明、本人の発言）。あるものだけ
 */
public record TranslationResult(String from, String to, List<Item> items) {

    /**
     * 訳の1件。
     *
     * @param key どの文章か（{@code utterance}、{@code structured.summary}、{@code answer.askerExplanation}、{@code message:{id}}）
     * @param original 原文
     * @param translated 訳
     */
    public record Item(String key, String original, String translated) {}
}
