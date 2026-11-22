package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 先例の永続化。 */
interface PrecedentRepository extends JpaRepository<PrecedentEntity, UUID>, JpaSpecificationExecutor<PrecedentEntity> {

    /** 問い合わせから生まれた先例を、決めた古い順に返す。 */
    List<PrecedentEntity> findByInquiryIdOrderByDecidedAtAsc(UUID inquiryId);

    /** システムと種類が同じ先例を、新しい順に返す。指定した問い合わせから生まれた先例は除く。 */
    @Query("select p from PrecedentEntity p where p.systemId = :systemId and p.type = :type"
            + " and p.inquiryId <> :inquiryId order by p.decidedAt desc")
    List<PrecedentEntity> findSamePlace(
            @Param("systemId") String systemId,
            @Param("type") InquiryType type,
            @Param("inquiryId") UUID inquiryId,
            Pageable pageable);
}
