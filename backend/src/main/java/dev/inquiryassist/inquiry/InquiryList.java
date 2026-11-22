package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.Turn;
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
     * @param language 本人の言語
     * @param summary 言い直しの文章。なければ元の発話の先頭80文字
     * @param urgency 緊急度
     * @param phase 区間
     * @param turn 誰の番か。終わった件は null
     * @param awaiting 待っていること。終わった件は null
     * @param ending 終わり方。終わっていない件は null
     * @param handedOverAt 担当者に初めて渡した時刻。渡していなければ null。結果を確かめている件の結果が、AI の答えか担当者の結果かを見分けるために使う
     * @param unreadReply 未読の返信があるか
     * @param messageCount メッセージの件数
     */
    public record Item(
            UUID id,
            Instant createdAt,
            String language,
            String summary,
            Urgency urgency,
            Phase phase,
            Turn turn,
            Awaiting awaiting,
            Ending ending,
            Instant handedOverAt,
            boolean unreadReply,
            int messageCount) {}
}
