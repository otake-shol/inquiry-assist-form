package dev.inquiryassist.eval;

import tools.jackson.databind.JsonNode;

/**
 * 評価の例1件。
 *
 * @param skill 対象のスキル
 * @param id 例の識別子
 * @param split 区分（{@code dev} は調整用、{@code holdout} は確認用）
 * @param body 例のファイルの1件分（入力と {@code expect} を含む）
 * @param source 読み込んだファイル（エラーの表示用）
 */
record EvalCase(EvalSkill skill, String id, String split, JsonNode body, String source) {

    static final String DEV = "dev";
    static final String HOLDOUT = "holdout";

    JsonNode expect() {
        return body.path("expect");
    }

    boolean isHoldout() {
        return HOLDOUT.equals(split);
    }
}
