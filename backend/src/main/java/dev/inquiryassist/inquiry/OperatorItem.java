package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Urgency;
import java.time.Instant;
import java.util.UUID;

/**
 * オペレータ向けの一覧の1件。ツール内の通知（SSE の {@code filed} と {@code updated}）の data も同じ形。
 *
 * @param id 問い合わせの識別子
 * @param filedAt 担当者に送られた時刻
 * @param type 種類。決まっていなければ null
 * @param system システム。決まっていなければ null
 * @param function 機能。決まっていなければ null
 * @param urgency 緊急度
 * @param summary 言い直しの文章。なければ本人が書いた文章の先頭80文字、それもなければ置き場所の名前
 * @param sentImmediately 答えを待たずに担当者へ送られたか
 * @param read 既読か
 * @param handlingStatus 対応状況
 * @param messageCount やり取り（担当者の返信と本人の追記）の件数
 */
public record OperatorItem(
        UUID id,
        Instant filedAt,
        Ref type,
        Ref system,
        Ref function,
        Urgency urgency,
        String summary,
        boolean sentImmediately,
        boolean read,
        HandlingStatus handlingStatus,
        int messageCount) {

    /**
     * 置き場所（種類、システム、機能のどれか）の名前。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public record Ref(String id, String label) {}
}
