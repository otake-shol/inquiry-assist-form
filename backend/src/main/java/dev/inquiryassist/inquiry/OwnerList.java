package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Urgency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 仕様の持ち主が、決定を待っている相談の一覧（契約書「仕様の持ち主への相談」）。古い順（長く待たせている順）。
 *
 * @param items 相談
 */
public record OwnerList(List<Item> items) {

    /**
     * 一覧の1件。
     *
     * @param inquiryId 問い合わせの識別子
     * @param consultationId 相談の識別子
     * @param askedAt 相談した時刻
     * @param askedBy 相談した担当者の名前
     * @param to 相談先の名前
     * @param question 相談の文章
     * @param summary 要約（担当者向けの一覧と同じ決まり）
     * @param type 種類。決まっていなければ null
     * @param system システム。決まっていなければ null
     * @param function 機能。決まっていなければ null
     * @param urgency 緊急度
     * @param language 本人の言語
     */
    public record Item(
            UUID inquiryId,
            UUID consultationId,
            Instant askedAt,
            String askedBy,
            List<String> to,
            String question,
            String summary,
            OperatorItem.Ref type,
            OperatorItem.Ref system,
            OperatorItem.Ref function,
            Urgency urgency,
            String language) {}
}
