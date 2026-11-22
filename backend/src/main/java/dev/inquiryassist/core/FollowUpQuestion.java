package dev.inquiryassist.core;

import java.util.List;

/**
 * 聞き手のAIが作る、詳しい質問。
 *
 * <p>選択肢つきで出す。「わからない」は画面側が常に出すため、ここには含めない。選択肢の識別子は、入口の質問にするときに
 * 実装が {@code o1}、{@code o2}… と振る。
 *
 * @param id 質問の識別子。回答との対応づけに使う
 * @param text 質問文。非エンジニアが答えられる内容に限る
 * @param options 選択肢。自由記述で答えさせる場合は空
 */
public record FollowUpQuestion(String id, String text, List<String> options) {

    public FollowUpQuestion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text は必須");
        }
        options = options == null ? List.of() : List.copyOf(options);
    }
}
