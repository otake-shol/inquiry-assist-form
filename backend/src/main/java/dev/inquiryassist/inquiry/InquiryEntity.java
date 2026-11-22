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
import dev.inquiryassist.core.lifecycle.Actor;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Declined;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.HandoverReason;
import dev.inquiryassist.core.lifecycle.HistoryEntry;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.InquiryState;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.Transition;
import dev.inquiryassist.core.lifecycle.Turn;
import dev.inquiryassist.core.skill.Explanation;
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
// 変えた列だけを更新する。読み込んだ記録を、別のスレッドの直接の更新（既読、未読の解除）と重なる短い間に保存しても、
// 変えていない列（既読の時刻など）を古い値で上書きしない
@org.hibernate.annotations.DynamicUpdate
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
    record StoredMessage(
            UUID id, MessageAuthor from, MessageKind kind, String authorName, String body, Instant createdAt) {}

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

    /** 本人の言語。AI が書くものはこの言語で書く。作成のときに決め、本人が行う状態を変えるリクエストのたびに合わせ直す。 */
    @Column(nullable = false)
    String language = "ja";

    /**
     * いま処理しているリクエストの、見る人の言語。保存しない。応答に出す名前と、入口の決まった質問をこの言語にするため、
     * 読み出した記録に持たせる。
     */
    @jakarta.persistence.Transient
    String viewerLanguage;

    /** 区間。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    Phase phase = Phase.INTAKE;

    /** 誰の番か。終わった件は null。 */
    @Enumerated(EnumType.STRING)
    Turn turn = Turn.ASKER;

    /** 待っていること。終わった件は null。 */
    @Enumerated(EnumType.STRING)
    Awaiting awaiting = Awaiting.INTAKE_ANSWER;

    /** 終わり方。終わっていない件は null。 */
    @Enumerated(EnumType.STRING)
    Ending ending;

    /** 終わらせた人（本人、担当者、時間）。終わっていない件は null。開き直すと null に戻る。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "closed_by")
    Actor closedBy;

    /**
     * 待っていることが、いまのものになった時刻（返事がないときの決まりの起点）。待っていることが変わる遷移のたびに更新する。
     * 状態が変わらない遷移（知らせる）では変えない。
     */
    @Column(name = "awaiting_since")
    Instant awaitingSince;

    /** 返事がないことを本人に知らせた時刻。まだなら null。待っていることが変わると消える。 */
    @Column(name = "reminded_at")
    Instant remindedAt;

    /** 終わる前の区間。終わった件を開き直したとき、この区間に戻す。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "phase_before_closed")
    Phase phaseBeforeClosed;

    /** 見送りの内容。見送りで終わった件だけが持つ。 */
    @JdbcTypeCode(SqlTypes.JSON)
    Declined declined;

    /** 状態の履歴。状態が変わるたびに1件。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<HistoryEntry> history = new ArrayList<>();

    /**
     * 担当者が置き直した置き場所。ある場合は、入口の答えから導いた置き場所の代わりに使う。
     * 入口で本人が何を選んだかは、入口の答え（{@link #intake}）にそのまま残す。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "placement_override")
    Placement placementOverride;

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

    /** 説明が出した、本人が自分で行える手順（行う順）。ほかの答えの項目と同じ時点で保存し、同じ時点で置き換える。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_steps", nullable = false)
    List<Explanation.Step> answerSteps = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "technical_memo")
    TechnicalMemo technicalMemo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<StoredCitation> citations = new ArrayList<>();

    @Column(name = "sent_immediately", nullable = false)
    boolean sentImmediately;

    @Column(name = "revisions_left", nullable = false)
    int revisionsLeft = INITIAL_REVISIONS;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revision_notes", nullable = false)
    List<String> revisionNotes = new ArrayList<>();

    /** 直近に担当者へ渡した理由。渡したことがなければ null。聞き返しへの返事などでは変えない。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "handover_reason")
    HandoverReason handoverReason;

    /** 調査の状況。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "investigation_status", nullable = false)
    InvestigationStatus investigationStatus = InvestigationStatus.NOT_RUN;

    /**
     * 保存している調査の結果を誰に見せるか。結果がなければ null。調査の最中や失敗の後も、前の結果が残っていれば、その結果の分を持つ
     * （本人向けの答えを隠すかどうかは、これで決める）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "investigation_audience")
    InvestigationAudience investigationAudience;

    /** 直近の調査を始めた時刻。 */
    @Column(name = "investigation_started_at")
    Instant investigationStartedAt;

    /** 直近の調査が終わった（または失敗した）時刻。調べている最中は null。 */
    @Column(name = "investigation_finished_at")
    Instant investigationFinishedAt;

    /** 調査のもとにした問い（言い直し）を、本人が確かめていないか。 */
    @Column(name = "investigation_unconfirmed_question", nullable = false)
    boolean investigationUnconfirmedQuestion;

    /**
     * 担当者のための訳。訳す先の言語ごとに、原文から訳への対応を持つ（原文の SHA-256 を16進数にしたものをキーにする）。
     * 同じ文章を2度訳さないために保存する。本人向けの応答には含めない。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "translations", nullable = false)
    java.util.Map<String, java.util.Map<String, String>> translations = new java.util.LinkedHashMap<>();

    /** 仕様の持ち主への相談（古い順）。決定を待っている（{@code OPEN}）ものは、同時に1つだけ。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consultations", nullable = false)
    List<Consultation> consultations = new ArrayList<>();

    /** AI の案（助言）の状況。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "advice_status", nullable = false)
    AdviceStatus adviceStatus = AdviceStatus.NOT_RUN;

    /** AI の案の中身。できた（{@code DONE}）ときだけ持つ。調査の結果が置き換わると消える。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "advice")
    AdviceContent advice;

    /** 担当者に初めて渡した時刻。渡したことがなければ null。 */
    @Column(name = "handed_over_at")
    Instant handedOverAt;

    @Column(name = "read_at")
    Instant readAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    List<StoredMessage> messages = new ArrayList<>();

    @Column(name = "asker_unread_reply", nullable = false)
    boolean askerUnreadReply;

    /**
     * Slack に投稿できたか。投稿の結果は、保存の確定後に別のスレッドが直接更新する（{@link InquiryRepository#markSlackResult}）。
     * 記録を読み込んで保存し直す操作（裏での調査の結果の保存など）が、古い値で上書きしないよう、記録の保存では更新しない。
     */
    @Column(name = "slack_notified", nullable = false, updatable = false)
    boolean slackNotified;

    /** Slack への投稿の失敗。{@link #slackNotified} と同じ理由で、記録の保存では更新しない。 */
    @Column(name = "slack_error", updatable = false)
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
        this.createdAt = now;
        this.updatedAt = now;
        this.awaitingSince = now;
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
     * @param kind 発言の種類
     * @param authorName 表示用の名前
     * @param body 本文
     * @param now 投稿時刻
     */
    void addMessage(MessageAuthor from, MessageKind kind, String authorName, String body, Instant now) {
        messages.add(new StoredMessage(UUID.randomUUID(), from, kind, authorName, body, now));
    }

    /**
     * @return 決定を待っている相談。なければ null
     */
    Consultation openConsultation() {
        return consultations.stream().filter(Consultation::open).findFirst().orElse(null);
    }

    /**
     * 決定を待っている相談を、取り下げた相談にする（本人が問い合わせを取り下げたとき）。なければ何もしない。
     *
     * @param now 取り下げた時刻
     */
    void cancelOpenConsultations(Instant now) {
        consultations = new ArrayList<>(consultations.stream()
                .map(c -> c.open() ? c.canceled(now) : c)
                .toList());
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
     * 置き場所（種類、システム、機能）を返す。担当者が置き直していればそれ、なければ入口の答えから導く
     * （本人が選んだものと、受付が置いたものの両方を含む）。
     *
     * @return 置き場所。決まっていない項目は null
     */
    Placement placement() {
        return placementOverride != null ? placementOverride : IntakeEntries.placement(intake);
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
     * @return いまの状態
     */
    InquiryState state() {
        return new InquiryState(phase, turn, awaiting, ending);
    }

    /**
     * 状態の遷移に使う、状態以外の情報を作る。
     *
     * @param next 入口で次にすること。入口の質問に答えている間だけ見る
     * @return 文脈
     */
    InquiryStateMachine.Context context(IntakeNext next) {
        return new InquiryStateMachine.Context(
                type(),
                handedOverAt != null,
                phaseBeforeClosed,
                next instanceof IntakeNext.Complete,
                revisionsLeft,
                closedBy);
    }

    /**
     * 遷移の結果を、状態と履歴に反映する。状態が変わらない遷移（出来事がないもの）は、何もしない。
     * 番が担当者に移ったときは、担当者の側を未読に戻し、初めてなら担当者に渡した時刻を持つ。
     *
     * @param transition 遷移の結果
     * @param now 現在時刻
     */
    void apply(Transition transition, Instant now) {
        apply(transition, now, null);
    }

    /**
     * 遷移の結果を、状態と履歴に反映する。履歴の補足（置き直しの前後の名前）を添える。
     *
     * @param transition 遷移の結果
     * @param now 現在時刻
     * @param detail 履歴の補足。なければ null
     */
    void apply(Transition transition, Instant now, java.util.Map<String, java.util.Map<String, String>> detail) {
        if (transition.event() == null) {
            return;
        }
        InquiryState to = transition.to();
        if (to.closed() && !phase.equals(Phase.CLOSED)) {
            phaseBeforeClosed = phase;
        }
        if (to.awaiting() != awaiting) {
            // 待っていることが変わったら、返事がないときの決まりを最初から数える。知らせる遷移は、状態が変わらないので通らない
            awaitingSince = now;
            remindedAt = null;
        }
        phase = to.phase();
        turn = to.turn();
        awaiting = to.awaiting();
        ending = to.ending();
        // 終わらせた遷移の actor を持つ。開き直す（終わっていない状態になる）と消える
        closedBy = to.closed() ? transition.actor() : null;
        if (transition.event() == dev.inquiryassist.core.lifecycle.HistoryEvent.REMINDED) {
            remindedAt = now;
        }
        if (transition.handsOver()) {
            readAt = null;
            if (handedOverAt == null) {
                handedOverAt = now;
            }
        }
        if (transition.event() == dev.inquiryassist.core.lifecycle.HistoryEvent.REOPENED) {
            declined = null;
        }
        if (transition.handoverReason() != null) {
            handoverReason = transition.handoverReason();
        }
        history.add(new HistoryEntry(
                now, transition.actor(), transition.event(), phase, turn, awaiting, ending, detail));
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
