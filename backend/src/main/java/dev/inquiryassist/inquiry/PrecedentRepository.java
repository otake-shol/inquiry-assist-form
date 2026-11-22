package dev.inquiryassist.inquiry;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 先例の永続化。 */
interface PrecedentRepository extends JpaRepository<PrecedentEntity, UUID> {

    /** 問い合わせから生まれた先例を、決めた古い順に返す。 */
    List<PrecedentEntity> findByInquiryIdOrderByDecidedAtAsc(UUID inquiryId);
}
