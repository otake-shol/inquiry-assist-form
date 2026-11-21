package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 本人向けの、自分が作った問い合わせの一覧（契約書 10）。
 *
 * @param unreadReplyCount 本人がまだ読んでいない返信がある問い合わせの件数
 * @param items 一覧（新しい順）
 */
public record InquiryList(long unreadReplyCount, List<Item> items) {

    /**
     * 一覧の1件。
     *
     * @param id 識別子
     * @param createdAt 作成日時
     * @param summary 言い直しの文章。なければ元の発話の先頭80文字
     * @param step 段階
     * @param outcome 結果
     * @param handlingStatus 対応状況。担当者に送られていなければ null
     * @param unreadReply 未読の返信があるか
     * @param messageCount メッセージの件数
     */
    public record Item(
            UUID id,
            Instant createdAt,
            String summary,
            InquiryStep step,
            InquiryOutcome outcome,
            HandlingStatus handlingStatus,
            boolean unreadReply,
            int messageCount) {}
}
