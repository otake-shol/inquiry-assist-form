package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 仕様の持ち主への相談1件。問い合わせが持ち、担当者向けの詳細が古い順に返す。保存する形と、API が返す形は同じ。
 *
 * @param id 相談の識別子
 * @param askedAt 相談した時刻
 * @param askedBy 相談した担当者の名前（相談した時点の名前）
 * @param to 相談先の名前（複数なら、その中の誰かが決めれば足りる）
 * @param question 相談の文章
 * @param status 状況
 * @param closedAt 決まった、または取り下げた時刻。{@code OPEN} なら null
 * @param precedentId できた先例の識別子。{@code DECIDED} のときだけ非 null
 */
public record Consultation(
        UUID id,
        Instant askedAt,
        String askedBy,
        List<String> to,
        String question,
        ConsultationStatus status,
        Instant closedAt,
        UUID precedentId) {

    /**
     * @return 決定を待っている場合は true
     */
    boolean open() {
        return status == ConsultationStatus.OPEN;
    }

    /**
     * 決まった相談にする。
     *
     * @param at 決まった時刻
     * @param precedent できた先例の識別子
     * @return 決まった相談
     */
    Consultation decided(Instant at, UUID precedent) {
        return new Consultation(id, askedAt, askedBy, to, question, ConsultationStatus.DECIDED, at, precedent);
    }

    /**
     * 取り下げた相談にする。
     *
     * @param at 取り下げた時刻
     * @return 取り下げた相談
     */
    Consultation canceled(Instant at) {
        return new Consultation(id, askedAt, askedBy, to, question, ConsultationStatus.CANCELED, at, null);
    }
}
