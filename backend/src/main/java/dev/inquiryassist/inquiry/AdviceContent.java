package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 保存する AI の案の中身。担当者向けの詳細の {@code advice} と同じ形で、{@code basedOn} は事実の文
 * （助言のスキルが返した番号を、確かめた後に、渡した事実の文へ置き換えたもの）。基づく事実のない案は持たない。
 *
 * @param judgment 判断の案。なければ null
 * @param procedure 手順の案。なければ null
 */
public record AdviceContent(Judgment judgment, Procedure procedure) {

    /**
     * 判断の案。
     *
     * @param text 案の文章
     * @param needsConsultation 仕様の持ち主への相談が要りそうか
     * @param consultationReason 相談が要る場合の理由。要らなければ null
     * @param basedOn 基づく事実の文。なければ空
     * @param basedOnPrecedents 基づく先例。なければ空（先例に対応する前に保存した案は、この項目を持たない）。{@code basedOn} と合わせて1件以上
     */
    public record Judgment(
            String text,
            boolean needsConsultation,
            String consultationReason,
            List<String> basedOn,
            List<PrecedentRef> basedOnPrecedents) {

        public Judgment {
            basedOn = basedOn == null ? List.of() : basedOn;
            basedOnPrecedents = basedOnPrecedents == null ? List.of() : basedOnPrecedents;
        }
    }

    /**
     * 案が基づく先例。
     *
     * @param id 先例の識別子
     * @param decision 決定の内容
     * @param decidedBy 決めた人の名前
     * @param decidedAt 決めた時刻
     */
    public record PrecedentRef(UUID id, String decision, String decidedBy, Instant decidedAt) {}

    /**
     * 手順の案の、1つの手順。
     *
     * @param target 対象
     * @param action 操作やコマンド
     * @param check 結果の確かめ方
     */
    public record Step(String target, String action, String check) {}

    /**
     * 手順の案。
     *
     * @param steps 行う順の手順
     * @param rollback 元に戻す方法。なければ null
     * @param basedOn 基づく事実の文。なければ空
     * @param basedOnPrecedents 基づく先例。なければ空。{@code basedOn} と合わせて1件以上
     */
    public record Procedure(
            List<Step> steps, String rollback, List<String> basedOn, List<PrecedentRef> basedOnPrecedents) {

        public Procedure {
            basedOn = basedOn == null ? List.of() : basedOn;
            basedOnPrecedents = basedOnPrecedents == null ? List.of() : basedOnPrecedents;
        }
    }
}
