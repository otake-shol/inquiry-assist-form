package dev.inquiryassist.inquiry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 問い合わせ記録の永続化。 */
interface InquiryRepository extends JpaRepository<InquiryEntity, UUID> {

    /** 担当者に渡したことのある問い合わせを、初めて渡した新しい順に返す。 */
    List<InquiryEntity> findByHandedOverAtIsNotNullOrderByHandedOverAtDesc();

    /** 担当者に渡したことのある問い合わせのうち、担当者の側が未読のものの件数を返す。 */
    long countByHandedOverAtIsNotNullAndReadAtIsNull();

    /** 本人が作った問い合わせを新しい順に、最大50件返す。 */
    List<InquiryEntity> findTop50ByAskerIdOrderByCreatedAtDesc(String askerId);

    /** 本人が作った問い合わせのうち、未読の返信があるものの件数を返す。 */
    long countByAskerIdAndAskerUnreadReplyTrue(String askerId);

    /** 指定した「待っていること」のどれかの問い合わせの識別子を返す（返事がないときの決まりの判定の対象を引く）。 */
    @Query("select e.id from InquiryEntity e where e.awaiting in :awaiting")
    List<UUID> findIdsByAwaitingIn(@Param("awaiting") java.util.Collection<dev.inquiryassist.core.lifecycle.Awaiting> awaiting);

    /**
     * 調査を始める。調べている最中でなければ、状況を {@code RUNNING} にする（同時に始めようとした一方だけが1件を更新する）。
     * 版を進めるので、始める前に読み込んだ記録を保存し直そうとする操作は、競合として拒否される（古い状況で上書きしない）。
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update InquiryEntity e set e.investigationStatus = dev.inquiryassist.inquiry.InvestigationStatus.RUNNING,"
            + " e.investigationStartedAt = :now, e.investigationFinishedAt = null,"
            + " e.investigationUnconfirmedQuestion = :unconfirmed, e.version = e.version + 1"
            + " where e.id = :id and e.investigationStatus <> dev.inquiryassist.inquiry.InvestigationStatus.RUNNING")
    int startInvestigation(
            @Param("id") UUID id, @Param("now") Instant now, @Param("unconfirmed") boolean unconfirmed);

    /** 起動時に、調べている最中のまま残っている調査を失敗にする。楽観ロックの対象外で、起動直後の1回だけ使う。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update InquiryEntity e set e.investigationStatus = dev.inquiryassist.inquiry.InvestigationStatus.FAILED,"
            + " e.investigationFinishedAt = :now where e.investigationStatus = dev.inquiryassist.inquiry.InvestigationStatus.RUNNING")
    int failRunningInvestigations(@Param("now") Instant now);

    /** 返信を読んだことにする。楽観ロックの対象外の列だけを更新し、取得が他の操作と競合しないようにする。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update InquiryEntity e set e.askerUnreadReply = false where e.id = :id and e.askerUnreadReply = true")
    int clearUnreadReply(@Param("id") UUID id);

    /** 既読にする（すでに既読なら時刻を変えない）。楽観ロックの対象外の列だけを更新する。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update InquiryEntity e set e.readAt = coalesce(e.readAt, :now) where e.id = :id")
    int markRead(@Param("id") UUID id, @Param("now") Instant now);

    /** Slack への投稿結果を記録する。起票の保存と競合しないよう、この列だけを直接更新する。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update InquiryEntity e set e.slackNotified = :notified, e.slackError = :error where e.id = :id")
    int markSlackResult(@Param("id") UUID id, @Param("notified") boolean notified, @Param("error") String error);
}
