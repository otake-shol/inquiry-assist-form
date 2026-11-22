package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.UUID;

/**
 * 先例（契約書「仕様の持ち主への相談」の「先例」）。名前（{@code label}）は、見る人の言語で返す。
 * {@code situation}、{@code decision}、{@code reason} は、書かれた内容で、書かれたときのまま返し、訳さない。
 *
 * @param id 識別子
 * @param inquiryId 問い合わせの識別子
 * @param type 決めた時点の種類。決まっていなければ null
 * @param system 決めた時点のシステム。決まっていなければ null
 * @param function 決めた時点の機能。決まっていなければ null
 * @param situation どんな状況についての決定か
 * @param decisionKind 決定の種類
 * @param decision 決定の内容
 * @param reason 理由
 * @param decidedBy 決めた人の名前
 * @param language 決めたときの見る人の言語
 * @param decidedAt 決めた時刻
 */
public record PrecedentView(
        UUID id,
        UUID inquiryId,
        Ref type,
        Ref system,
        Ref function,
        String situation,
        DecisionKind decisionKind,
        String decision,
        String reason,
        String decidedBy,
        String language,
        Instant decidedAt) {

    /**
     * 置き場所の1項目。
     *
     * @param id 識別子
     * @param label 見る人の言語の名前。設定にない識別子は、識別子のまま
     */
    public record Ref(String id, String label) {}
}
