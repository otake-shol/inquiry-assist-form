package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 説明の出力。
 *
 * @param text 本人向けの説明。空でない
 * @param answersQuestion 確かめられた事実が本人の問いに答えているか。迷ったら false
 * @param steps 本人が自分で行える手順。行う順。なくてもよい（{@code null} は空の一覧にする）。10個まで。
 *     確かめられた事実から手順を組めなければ、出さない（空のまま）
 */
public record Explanation(String text, boolean answersQuestion, List<Step> steps) {

    /** 手順の数の上限。 */
    public static final int MAX_STEPS = 10;

    /**
     * 本人が自分で行える手順の1つ。
     *
     * @param action 何をするか。空でない。画面の名前、押す場所、入力する値をできるだけ具体的に
     * @param check 結果の確かめ方。なければ {@code null}
     */
    public record Step(String action, String check) {

        public Step {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("手順の action は必須");
            }
        }
    }

    public Explanation {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text は必須");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("手順は" + MAX_STEPS + "個まで");
        }
    }

    /**
     * 手順なしの説明を作る。
     *
     * @param text 本人向けの説明
     * @param answersQuestion 確かめられた事実が本人の問いに答えているか
     */
    public Explanation(String text, boolean answersQuestion) {
        this(text, answersQuestion, List.of());
    }
}
