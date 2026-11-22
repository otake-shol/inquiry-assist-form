package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 先例（{@code precedents} テーブル）。仕様の持ち主が下した決定の記録。問い合わせとは別に持ち、問い合わせが終わっても残す。
 * 先例のマス（種類、システム、機能）は、決めた時点の問い合わせの置き場所で、後から問い合わせを置き直しても変えない。
 */
@Entity
@Table(name = "precedents")
class PrecedentEntity {

    @Id
    UUID id;

    @Column(name = "inquiry_id", nullable = false)
    UUID inquiryId;

    /** 決めた時点の種類。決まっていなければ null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    InquiryType type;

    @Column(name = "system_id")
    String systemId;

    @Column(name = "function_id")
    String functionId;

    /** どんな状況についての決定か。書かれたときのまま持ち、訳さない。 */
    @Column(nullable = false)
    String situation;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_kind", nullable = false)
    DecisionKind decisionKind;

    @Column(nullable = false)
    String decision;

    @Column(nullable = false)
    String reason;

    @Column(name = "decided_by", nullable = false)
    String decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "recorded_by", nullable = false)
    RecordedBy recordedBy;

    /** 決めた人が、内容を確かめたか。持ち主が自分で記録した先例は、最初から true。 */
    @Column(nullable = false)
    boolean confirmed;

    /** 決めたときの、見る人の言語。 */
    @Column(nullable = false)
    String language;

    @Column(name = "decided_at", nullable = false)
    Instant decidedAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** JPA 用。 */
    protected PrecedentEntity() {}

    /**
     * 先例を作る。
     *
     * @param inquiryId 問い合わせの識別子
     * @param type 決めた時点の種類
     * @param systemId 決めた時点のシステム
     * @param functionId 決めた時点の機能
     * @param situation 状況
     * @param kind 決定の種類
     * @param decision 決定の内容
     * @param reason 理由
     * @param decidedBy 決めた人の名前
     * @param language 決めたときの見る人の言語
     * @param now 決めた時刻
     */
    PrecedentEntity(
            UUID inquiryId,
            InquiryType type,
            String systemId,
            String functionId,
            String situation,
            DecisionKind kind,
            String decision,
            String reason,
            String decidedBy,
            String language,
            Instant now) {
        this.id = UUID.randomUUID();
        this.inquiryId = inquiryId;
        this.type = type;
        this.systemId = systemId;
        this.functionId = functionId;
        this.situation = situation;
        this.decisionKind = kind;
        this.decision = decision;
        this.reason = reason;
        this.decidedBy = decidedBy;
        this.recordedBy = RecordedBy.OWNER;
        this.confirmed = true;
        this.language = language;
        this.decidedAt = now;
        this.updatedAt = now;
    }
}
