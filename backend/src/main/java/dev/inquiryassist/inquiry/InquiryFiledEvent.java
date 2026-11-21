package dev.inquiryassist.inquiry;

/**
 * 問い合わせが担当者に送られ（{@code FILED}）、保存が確定した後に発行するイベント。
 * ツール内の通知と Slack への投稿がこれを受け取る。
 *
 * @param item 一覧の1件と同じ内容
 */
public record InquiryFiledEvent(OperatorItem item) {}
