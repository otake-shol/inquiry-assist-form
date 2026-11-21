package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryCategory;
import java.time.Instant;
import java.util.UUID;

/**
 * オペレータ向けの一覧の1件。ツール内の通知（SSE の {@code filed} と {@code updated}）の data も同じ形。
 *
 * @param id 問い合わせの識別子
 * @param filedAt 担当者に送られた時刻
 * @param category 確認済みの種別。判定に失敗した問い合わせでは null
 * @param system 対象システム。決まっていなければ null
 * @param summary 言い直しの文章。なければ元の発話の先頭80文字
 * @param sentImmediately 「今すぐ送る」による送付か
 * @param read 既読か
 * @param handlingStatus 対応状況
 * @param messageCount やり取り（担当者の返信と本人の追記）の件数
 */
public record OperatorItem(
        UUID id,
        Instant filedAt,
        InquiryCategory category,
        InquiryView.SystemView system,
        String summary,
        boolean sentImmediately,
        boolean read,
        HandlingStatus handlingStatus,
        int messageCount) {}
