package dev.inquiryassist.inquiry;

/**
 * 担当者に送った後の問い合わせが更新され（本人の追記、対応状況の変更、担当者の返信）、保存が確定した後に発行するイベント。
 * オペレータ画面への即時通知がこれを受け取る。Slack には投稿しないため、{@link InquiryFiledEvent} とは分ける。
 *
 * @param item 一覧の1件と同じ内容
 */
public record InquiryUpdatedEvent(OperatorItem item) {}
