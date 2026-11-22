package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 助言の出力。担当者への、判断の案と、作業の手順の案。どちらも、なくてもよい。
 *
 * <p>案は、渡した事実の番号と、渡した先例の番号（どちらも1から始まる）で、基づくものを示す。番号が存在するかは、
 * {@code AdviceVerifier} が確かめる。
 *
 * @param judgment 判断の案。なければ {@code null}
 * @param procedure 手順の案。なければ {@code null}
 */
public record Advice(Judgment judgment, Procedure procedure) {

    /**
     * 判断の案。
     *
     * @param text 案の文章。空でない
     * @param needsConsultation 仕様の持ち主への相談が要りそうか
     * @param consultationReason 相談が要る場合の理由。要らなければ {@code null}
     * @param basedOn 基づく事実の番号（渡した事実の並びの、1から始まる番号）
     * @param basedOnPrecedents 基づく先例の番号（渡した先例の並びの、1から始まる番号）
     */
    public record Judgment(
            String text,
            boolean needsConsultation,
            String consultationReason,
            List<Integer> basedOn,
            List<Integer> basedOnPrecedents) {

        /**
         * 先例に基づかない判断の案を作る。
         *
         * @param text 案の文章
         * @param needsConsultation 相談が要りそうか
         * @param consultationReason 相談が要る場合の理由
         * @param basedOn 基づく事実の番号
         */
        public Judgment(String text, boolean needsConsultation, String consultationReason, List<Integer> basedOn) {
            this(text, needsConsultation, consultationReason, basedOn, List.of());
        }

        public Judgment {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("判断の案の text は必須");
            }
            basedOn = basedOn == null ? List.of() : List.copyOf(basedOn);
            basedOnPrecedents = basedOnPrecedents == null ? List.of() : List.copyOf(basedOnPrecedents);
        }
    }

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
     * @param steps 順に行う手順。1件以上
     * @param rollback 元に戻す方法。なければ {@code null}
     * @param basedOn 基づく事実の番号（渡した事実の並びの、1から始まる番号）
     * @param basedOnPrecedents 基づく先例の番号（渡した先例の並びの、1から始まる番号）
     */
    public record Procedure(
            List<Step> steps, String rollback, List<Integer> basedOn, List<Integer> basedOnPrecedents) {

        /**
         * 先例に基づかない手順の案を作る。
         *
         * @param steps 行う順の手順
         * @param rollback 元に戻す方法
         * @param basedOn 基づく事実の番号
         */
        public Procedure(List<Step> steps, String rollback, List<Integer> basedOn) {
            this(steps, rollback, basedOn, List.of());
        }

        public Procedure {
            steps = steps == null ? List.of() : List.copyOf(steps);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("手順の案の steps は1件以上");
            }
            basedOn = basedOn == null ? List.of() : List.copyOf(basedOn);
            basedOnPrecedents = basedOnPrecedents == null ? List.of() : List.copyOf(basedOnPrecedents);
        }
    }
}
