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
