package dev.inquiryassist.inquiry;

/**
 * 問い合わせを担当者に初めて渡し、保存が確定した後に発行するイベント。
 * ツール内の通知と Slack への投稿がこれを受け取る。
 *
 * @param item 一覧の1件と同じ内容
 */
public record InquiryFiledEvent(OperatorItem item) {}
