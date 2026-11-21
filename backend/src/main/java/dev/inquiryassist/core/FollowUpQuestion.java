package dev.inquiryassist.core;

import java.util.List;

/**
 * AIが本人に出す追加質問。
 *
 * <p>選択肢つきで出す。「わからない」は画面側が常に追加するため、ここには含めない。
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
