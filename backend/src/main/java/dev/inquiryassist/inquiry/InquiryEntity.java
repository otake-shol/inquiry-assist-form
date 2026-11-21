package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Usage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 問い合わせ記録（{@code inquiries} テーブル）。サービスと同じパッケージ内だけで扱う。
 *
 * <p>JSONB 列は Hibernate の JSON マッピングで保存する。
 */
@Entity
@Table(name = "inquiries")
class InquiryEntity {

    /** 保存する追加質問。回答つき。 */
    record StoredQuestion(String id, String text, List<String> options, String answer) {}

    /** 保存する出典。照合結果つき。 */
    record StoredCitation(
            Citation.SourceType sourceType,
            String repository,
            String path,
            int startLine,
            int endLine,
            String quote,
            String revision,
            boolean verified) {}

    /** 保存する AI 処理ごとの利用量。 */
    record StoredUsage(
            String step,
            String model,
            String promptVersion,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            boolean mock) {}

    /** 言い直しを作り直せる回数の初期値。 */
    static final int INITIAL_REVISIONS = 2;

    @Id
    UUID id;

    @Version
    long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(name = "asker_id", nullable = false)
    String askerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    InquiryStep step;

    @Column(nullable = false)
    String utterance;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_by_ai")
    InquiryCategory categoryByAi;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_confirmed")
    InquiryCategory categoryConfirmed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "follow_ups", nullable = false)
    List<StoredQuestion> followUps = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_questions", nullable = false)
    List<StoredQuestion> aiQuestions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_by_ai")
    StructuredInquiry structuredByAi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_confirmed")
    StructuredInquiry structuredConfirmed;

    @Column(name = "answer_grounded")
    Boolean answerGrounded;

    @Column(name = "asker_explanation")
    String askerExplanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "technical_memo")
    TechnicalMemo technicalMemo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<StoredCitation> citations = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    InquiryOutcome outcome = InquiryOutcome.IN_PROGRESS;

    @Column(name = "sent_immediately", nullable = false)
    boolean sentImmediately;

    @Column(name = "revisions_left", nullable = false)
    int revisionsLeft = INITIAL_REVISIONS;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revision_notes", nullable = false)
    List<String> revisionNotes = new ArrayList<>();

    @Column(name = "filed_at")
    Instant filedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<StoredUsage> usages = new ArrayList<>();

    @Column(nullable = false)
    boolean mock;

    /** JPA 用。 */
    protected InquiryEntity() {}

    /**
     * 新しい問い合わせを作る。
     *
     * @param askerId 投稿者の識別子
     * @param utterance 本人が書いた元の発話
     * @param now 作成時刻
     */
    InquiryEntity(String askerId, String utterance, Instant now) {
        this.id = UUID.randomUUID();
        this.askerId = askerId;
        this.utterance = utterance;
        this.step = InquiryStep.TRIAGED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * AI 処理の利用量を追記する。モック由来なら記録全体を mock とする。
     *
     * @param stepName 処理の名前（triage / structure / answer）
     * @param usage 利用量
     */
    void addUsage(String stepName, Usage usage) {
        usages.add(new StoredUsage(
                stepName,
                usage.model(),
                usage.promptVersion(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.mock()));
        mock = mock || usage.mock();
    }

    /**
     * 本人が確認した後の種別を返す。
     *
     * @return 確認後の種別
     */
    InquiryCategory category() {
        return categoryConfirmed;
    }

    /**
     * 整形後の問いを返す。本人が確定していればその値、そうでなければ AI の出力。
     *
     * @return 整形後の問い。未作成なら null
     */
    StructuredInquiry structured() {
        return structuredConfirmed != null ? structuredConfirmed : structuredByAi;
    }
}
