package dev.inquiryassist.inquiry;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 問い合わせ記録の永続化。 */
interface InquiryRepository extends JpaRepository<InquiryEntity, UUID> {}
