package dev.inquiryassist.inquiry;

import java.util.Map;

/**
 * 問い合わせを担当者に初めて渡し、保存が確定した後に発行するイベント。
 * ツール内の通知と Slack への投稿がこれを受け取る。
 *
 * @param item 一覧の1件と同じ内容（既定の言語。Slack はこれを使う）
 * @param items 使える言語ごとの、一覧の1件と同じ内容。オペレータの画面へは、接続ごとの言語のものを配る
 */
public record InquiryFiledEvent(OperatorItem item, Map<String, OperatorItem> items) {

    /**
     * @param language 言語
     * @return その言語の一覧の1件。ない言語は既定の言語のもの
     */
    public OperatorItem itemFor(String language) {
        return items.getOrDefault(language, item);
    }
}
