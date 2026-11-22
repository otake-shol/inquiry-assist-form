package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 聞き手の「答えを読み取る」仕事の出力。設定の質問のうち、本人の文章に答えが書かれているものの答え。
 * 書かれていない質問は含めない。出力は信用せず、入口の処理（{@code IntakePlanner#acceptPrefill}）が確かめてから使う。
 *
 * @param answers 読み取った答え
 */
public record Prefill(List<Answer> answers) {

    /**
     * 読み取った答え1件。
     *
     * @param questionId 質問の識別子
     * @param optionId 選択肢のある質問で、当てはまる選択肢の識別子。それ以外は {@code null}
     * @param text 文章で答える質問で、本人の文章から抜き出した答え。それ以外は {@code null}
     */
    public record Answer(String questionId, String optionId, String text) {}

    public Prefill {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /**
     * @return 何も読み取れなかった結果
     */
    public static Prefill none() {
        return new Prefill(List.of());
    }
}
