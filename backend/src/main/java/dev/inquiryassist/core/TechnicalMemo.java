package dev.inquiryassist.core;

import java.util.List;

/**
 * 担当者向けの技術メモ。担当者が調査をやり直さずに済む内容を持つ。
 *
 * @param findings 調べて分かったこと（根拠を確かめられた事実の文）
 * @param unconfirmed 調査が挙げたが、根拠の実在を確かめられなかった事実の文。事実としては示さない
 * @param notFound 調べたが見つからなかったこと
 * @param nextChecks 次に確認すべき場所、または本人に聞くべきこと
 */
public record TechnicalMemo(
        List<String> findings, List<String> unconfirmed, List<String> notFound, List<String> nextChecks) {

    public TechnicalMemo {
        findings = findings == null ? List.of() : List.copyOf(findings);
        unconfirmed = unconfirmed == null ? List.of() : List.copyOf(unconfirmed);
        notFound = notFound == null ? List.of() : List.copyOf(notFound);
        nextChecks = nextChecks == null ? List.of() : List.copyOf(nextChecks);
    }

    /**
     * 確かめられなかった事実のないメモを作る。
     *
     * @param findings 調べて分かったこと
     * @param notFound 調べたが見つからなかったこと
     * @param nextChecks 次に確認すべき場所、または本人に聞くべきこと
     */
    public TechnicalMemo(List<String> findings, List<String> notFound, List<String> nextChecks) {
        this(findings, List.of(), notFound, nextChecks);
    }

    /**
     * 中身のないメモを返す。
     *
     * @return 空のメモ
     */
    public static TechnicalMemo empty() {
        return new TechnicalMemo(List.of(), List.of(), List.of(), List.of());
    }
}
