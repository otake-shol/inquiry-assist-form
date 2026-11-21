package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.UUID;

/**
 * 担当者の返信と本人の追記を1本にした時系列の1件（契約書の messages）。
 *
 * @param id 識別子
 * @param from 投稿者の区分
 * @param authorName 表示用の名前。投稿した時点の設定値
 * @param body 本文
 * @param createdAt 投稿時刻
 */
public record MessageView(
        UUID id, MessageAuthor from, String authorName, String body, Instant createdAt) {}
