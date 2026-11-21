package dev.inquiryassist.inquiry;

import java.util.List;

/** 往復のメッセージの本文の検証と、保存した記録から返す形への変換。本人と担当者で同じ規則を使うため、1か所に置く。 */
final class MessageBodies {

    private static final int MAX_LENGTH = 4000;

    private MessageBodies() {}

    /**
     * 本文を検証し、前後の空白を除いた値を返す。
     *
     * @param body 入力された本文
     * @return 前後の空白を除いた本文
     * @throws IllegalArgumentException 空（空白のみを含む）、または4000文字を超える場合
     */
    static String validated(String body) {
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("body は前後の空白を除いて1〜" + MAX_LENGTH + "文字");
        }
        return trimmed;
    }

    /**
     * 保存したメッセージを、古い順のまま返す形にする。
     *
     * @param entity 問い合わせ記録
     * @return メッセージの一覧。なければ空
     */
    static List<MessageView> viewsOf(InquiryEntity entity) {
        return entity.messages.stream()
                .map(m -> new MessageView(m.id(), m.from(), m.authorName(), m.body(), m.createdAt()))
                .toList();
    }
}
