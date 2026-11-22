package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.intake.IntakeEntries;
import dev.inquiryassist.core.intake.IntakeEntry;
import dev.inquiryassist.core.intake.IntakeNext;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.IntakeSnapshot;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
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
            boolean mock,
            boolean failed,
            Long durationMillis) {}

    /** 保存するやり取り1件。担当者の返信と本人の追記を同じ形で持つ。 */
    record StoredMessage(UUID id, MessageAuthor from, String authorName, String body, Instant createdAt) {}

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

    /** 緊急度。本人が申告する。種類とは別に持つ。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    Urgency urgency = Urgency.NORMAL;

    /** 本人が文章で書いたもの（最初の文章、切り替えたときの文章）。書いた順。補足は入口の答えとして別に持つ。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<String> texts = new ArrayList<>();

    /**
     * 答え終えた入口の質問（聞いた順）。置き場所（種類、システム、機能）はここから導く。
     * 別に持つと、戻ったときに答えと置き場所がずれる。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<IntakeEntry> intake = new ArrayList<>();

    /** 答えの読み取りを済ませた設定の質問の識別子。質問ごとに1回だけ読み取る。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prefill_attempted", nullable = false)
    List<String> prefillAttempted = new ArrayList<>();

    /**
     * 聞き手が作った詳しい質問。聞き手をまだ呼んでいなければ null、呼んで質問がなければ空。
     * 次の質問を、答えるたびに作り直さないために保存する。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_questions")
    List<IntakeQuestion> detailQuestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_by_ai")
    StructuredInquiry structuredByAi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_confirmed")
    StructuredInquiry structuredConfirmed;

    @Column(name = "answer_grounded")
    Boolean answerGrounded;

    @Column(name = "answer_answered")
    Boolean answerAnswered;

    /**
     * 確かめられた事実が、本人の問いに答えているかを返す。
     *
     * <p>判定が記録されていない場合は、根拠の有無に従う。この列を足す前に作られた記録は判定を受けておらず、
     * 当時は「根拠があれば答えとして見せる」動きだった。判定がないことを「答えていないと判定した」と
     * 取り違えると、判定していない記録に「問いに答えていない」という印が付く。
     *
     * @return 問いに答えている場合は true。根拠がなければ常に false
     */
    boolean answered() {
        boolean grounded = Boolean.TRUE.equals(answerGrounded);
        if (!grounded) {
            return false;
        }
        return answerAnswered == null || answerAnswered;
    }

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

    @Column(name = "read_at")
    Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_status")
    HandlingStatus handlingStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<StoredMessage> messages = new ArrayList<>();

    @Column(name = "asker_unread_reply", nullable = false)
    boolean askerUnreadReply;

    @Column(name = "slack_notified", nullable = false)
    boolean slackNotified;

    @Column(name = "slack_error")
    String slackError;

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
     * @param urgency 緊急度
     * @param now 作成時刻
     */
    InquiryEntity(String askerId, Urgency urgency, Instant now) {
        this.id = UUID.randomUUID();
        this.askerId = askerId;
        this.urgency = urgency;
        this.step = InquiryStep.INTAKE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * スキル1回の利用量を追記する。モック由来なら記録全体を mock とする。
     *
     * @param usage スキルごとの利用量
     */
    void addUsage(SkillUsage usage) {
        Usage u = usage.usage();
        usages.add(new StoredUsage(
                usage.skill().id(),
                u.model(),
                u.promptVersion(),
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.mock(),
                usage.failed(),
                usage.durationMillis()));
        mock = mock || u.mock();
    }

    /**
     * 成功したスキルの利用量を追記する。
     *
     * @param skill 呼んだスキル
     * @param usage 利用量
     */
    void addUsage(Skill skill, Usage usage) {
        addUsage(new SkillUsage(skill, usage));
    }

    /**
     * 成功したスキルの利用量を、かかった時間つきで追記する。
     *
     * @param skill 呼んだスキル
     * @param usage 利用量
     * @param durationMillis 呼び出しにかかった時間（ミリ秒）
     */
    void addUsage(Skill skill, Usage usage, long durationMillis) {
        addUsage(new SkillUsage(skill, usage, false, durationMillis));
    }

    /**
     * やり取りに1件追記する。投稿者の名前は、投稿した時点の値を保存する。
     *
     * @param from 投稿者の区分
     * @param authorName 表示用の名前
     * @param body 本文
     * @param now 投稿時刻
     */
    void addMessage(MessageAuthor from, String authorName, String body, Instant now) {
        messages.add(new StoredMessage(UUID.randomUUID(), from, authorName, body, now));
    }

    /**
     * 本人が書いた文章を、書いた順に空行でつないで返す。補足は含めない。
     *
     * @return 文章。書いていなければ null
     */
    String writtenText() {
        return texts.isEmpty() ? null : String.join("\n\n", texts);
    }

    /**
     * 本人が書いた文章（最初の文章、切り替えたときの文章、補足）を、書いた順に空行でつないで返す。
     *
     * @return 文章。何も書いていなければ null
     */
    String utterance() {
        List<String> parts = new ArrayList<>(texts);
        String note = IntakeEntries.noteText(intake);
        if (note != null) {
            parts.add(note);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /**
     * 置き場所（種類、システム、機能）を返す。本人が選んだものと、受付が置いたものの両方を含む。
     *
     * @return 置き場所。決まっていない項目は null
     */
    Placement placement() {
        return IntakeEntries.placement(intake);
    }

    /**
     * @return 種類。決まっていなければ null
     */
    InquiryType type() {
        return placement().type();
    }

    /**
     * 次の質問を決める入力にする。
     *
     * @return 入口の状態
     */
    IntakeSnapshot snapshot() {
        return new IntakeSnapshot(intake, detailQuestions, !texts.isEmpty(), urgency, prefillAttempted);
    }

    /**
     * いま「答えを待たずに担当者へ送る」を実行できるかを返す。
     *
     * <p>問いを確かめた後に限る（入力が足りないまま担当者に渡すと、聞き返しが増えるだけになるため）。
     * ただし、AI の失敗で先へ進めない場合（入口を終えたのに組み立てられない）と、言い直しを作り直せる回数を
     * 使い切った場合は、こちらの側の事情なので、本人を止めない。
     *
     * @param next 入口で次にすること。{@code INTAKE} のときだけ見る
     * @return 実行できる場合は true
     */
    boolean canSendNow(IntakeNext next) {
        return switch (step) {
            case ANSWERING, ANSWERED -> true;
            case INTAKE -> next instanceof IntakeNext.Complete;
            case STRUCTURED -> revisionsLeft == 0;
            case CLOSED -> false;
        };
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
