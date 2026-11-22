package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 訳の出力。
 *
 * @param texts 訳した文章。渡した順。数が渡した数と同じかは、呼ぶ側のコードが確かめる（スキルの実装によらず、同じ確かめを通すため）
 */
public record Translation(List<String> texts) {

    public Translation {
        if (texts == null) {
            throw new IllegalArgumentException("texts は必須");
        }
        texts = List.copyOf(texts);
    }
}
