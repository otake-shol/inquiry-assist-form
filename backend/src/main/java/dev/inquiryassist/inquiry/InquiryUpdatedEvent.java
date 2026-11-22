package dev.inquiryassist.inquiry;

import java.util.Map;

/**
 * 担当者に渡した後の問い合わせが更新され（状態の変化、本人の追記、担当者の発言）、保存が確定した後に発行するイベント。
 * オペレータ画面への即時通知がこれを受け取る。Slack には投稿しないため、{@link InquiryFiledEvent} とは分ける。
 *
 * @param item 一覧の1件と同じ内容（既定の言語）
 * @param items 使える言語ごとの、一覧の1件と同じ内容
 */
public record InquiryUpdatedEvent(OperatorItem item, Map<String, OperatorItem> items) {

    /**
     * @param language 言語
     * @return その言語の一覧の1件。ない言語は既定の言語のもの
     */
    public OperatorItem itemFor(String language) {
        return items.getOrDefault(language, item);
    }
}
