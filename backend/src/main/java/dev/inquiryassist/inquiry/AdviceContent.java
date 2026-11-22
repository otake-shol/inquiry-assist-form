package dev.inquiryassist.inquiry;

import java.util.List;

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
     * @param basedOn 基づく事実の文（1件以上）
     */
    public record Judgment(String text, boolean needsConsultation, String consultationReason, List<String> basedOn) {}

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
     * @param basedOn 基づく事実の文（1件以上）
     */
    public record Procedure(List<Step> steps, String rollback, List<String> basedOn) {}
}
