package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Declined;
import dev.inquiryassist.core.lifecycle.DeclineReason;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import dev.inquiryassist.core.lifecycle.Transition;
import dev.inquiryassist.core.skill.Draft;
import dev.inquiryassist.core.skill.DraftRequest;
import dev.inquiryassist.core.skill.DrafterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * オペレータ向けの一覧・詳細・既読化と、担当者の操作（聞き返す・結果を伝える、見送る、置き直す）、
 * 通知結果の記録。担当者に渡したことのある問い合わせだけを扱う。状態の遷移の規則は、{@link InquiryStateMachine} が決める。
 */
@Service
public class OperatorService {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);
    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MAX_NOTE_LENGTH = 2000;
    private static final int MAX_CONSULTATION_LENGTH = 4000;
    private static final int MAX_CONSULTANTS = 5;
    private static final int MAX_CONSULTANT_NAME_LENGTH = 100;

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;
    private final InquiryPresenter presenter;
    private final IntakeLocales locales;
    private final ApplicationEventPublisher events;
    private final DrafterSkill drafter;
    private final InquiryCommitter committer;
    private final PrecedentRepository precedents;
    private final PrecedentFinder precedentFinder;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 投稿先と担当者の表示名の設定
     * @param implementations スキルごとの実装（実行記録の実装名に使う）
     * @param presenter 一覧と詳細の項目を作る
     * @param locales 置き直しの入力を確かめる、システムと機能の一覧（言語ごと）
     * @param events 更新の確定後にイベントを発行する
     * @param drafter 返信の下書き
     * @param committer 下書きの利用量の保存
     * @param precedents 先例（詳細の {@code decisions}）
     * @param precedentFinder 同じ置き場所の先例（詳細の {@code precedents}）
     */
    OperatorService(
            InquiryRepository repository,
            InquiryProperties properties,
            SkillImplementations implementations,
            InquiryPresenter presenter,
            IntakeLocales locales,
            ApplicationEventPublisher events,
            DrafterSkill drafter,
            InquiryCommitter committer,
            PrecedentRepository precedents,
            PrecedentFinder precedentFinder) {
        this.repository = repository;
        this.properties = properties;
        this.implementations = implementations;
        this.presenter = presenter;
        this.locales = locales;
        this.events = events;
        this.drafter = drafter;
        this.committer = committer;
        this.precedents = precedents;
        this.precedentFinder = precedentFinder;
    }

    /**
     * 担当者に渡したことのある問い合わせを、初めて渡した新しい順に返す。ただし、業務が止まっている（{@code BLOCKED}）件を先頭に寄せる。
     *
     * @param unreadOnly true なら未読だけに絞る
     * @return 一覧と、未読数（絞り込みにかかわらず全体）
     */
    public OperatorList list(boolean unreadOnly, String lang) {
        // 寄せ方は安定。BLOCKED の中、それ以外の中は、送られた新しい順のまま
        List<OperatorItem> items = new ArrayList<>(repository.findByHandedOverAtIsNotNullOrderByHandedOverAtDesc().stream()
                .filter(e -> !unreadOnly || e.readAt == null)
                .map(e -> presenter.toItem(e, lang))
                .toList());
        items.sort(java.util.Comparator.comparing(item -> item.urgency() != Urgency.BLOCKED));
        return new OperatorList(repository.countByHandedOverAtIsNotNullAndReadAtIsNull(), List.copyOf(items));
    }

    /**
     * 問い合わせの全内容を返す。
     *
     * @param id 問い合わせの識別子
     * @return 全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     */
    public OperatorDetail get(UUID id, String lang) {
        return toDetail(loadHandedOver(id), lang);
    }

    /**
     * 既読にして全内容を返す。すでに既読なら既読の時刻を変えない。
     *
     * @param id 問い合わせの識別子
     * @return 既読後の全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     */
    public OperatorDetail markRead(UUID id, String lang) {
        loadHandedOver(id);
        repository.markRead(id, Instant.now());
        return toDetail(loadHandedOver(id), lang);
    }

    /**
     * 担当者が本人に返す。聞き返しなら本人の返事を待ち、結果を伝えるなら本人が確かめる。区間は変えない。
     * 本人側は未読の発言ありになる。
     *
     * @param id 問い合わせの識別子
     * @param kind 発言の種類
     * @param body 本文。前後の空白を除いて1〜4000文字
     * @return 返信後の全内容
     * @throws IllegalArgumentException 本文が空、または長すぎる場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws InvalidStateException 担当者の番でない場合
     */
    public OperatorDetail reply(UUID id, ReplyKind kind, String body, String lang) {
        String trimmed = MessageBodies.validated(body);
        InquiryEntity inquiry = loadHandedOver(id);
        Transition transition = InquiryStateMachine.operatorReplied(inquiry.state(), kind);
        inquiry.addMessage(
                MessageAuthor.OPERATOR,
                kind == ReplyKind.ASK ? MessageKind.ASK : MessageKind.RESULT,
                properties.dev().operatorName(),
                trimmed,
                Instant.now());
        inquiry.askerUnreadReply = true;
        inquiry.apply(transition, Instant.now());
        return toDetail(saveAndPublish(inquiry), lang);
    }

    /**
     * 担当者が見送る。理由をメッセージ（{@code DECLINE}）として足し、終わる。
     *
     * @param id 問い合わせの識別子
     * @param reasonKind 理由の種類
     * @param reason 理由の文章。前後の空白を除いて1〜2000文字
     * @return 見送り後の全内容
     * @throws IllegalArgumentException 理由が空、または長すぎる場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws InvalidStateException 担当者の番でない場合
     */
    public OperatorDetail decline(UUID id, DeclineReason reasonKind, String reason, String lang) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason は前後の空白を除いて1〜" + MAX_REASON_LENGTH + "文字");
        }
        InquiryEntity inquiry = loadHandedOver(id);
        Transition transition = InquiryStateMachine.declined(inquiry.state());
        inquiry.addMessage(
                MessageAuthor.OPERATOR, MessageKind.DECLINE, properties.dev().operatorName(), trimmed, Instant.now());
        inquiry.askerUnreadReply = true;
        inquiry.declined = new Declined(reasonKind, trimmed);
        inquiry.apply(transition, Instant.now());
        return toDetail(saveAndPublish(inquiry), lang);
    }

    /**
     * 担当者が置き直す。置いた人は担当者になる。種類が変わったら、区間を新しい種類の決まりに合わせる。番は変えない。
     * 入口で本人が選んだ答えは、そのまま残す。変える前と後は、状態の履歴に残す。
     *
     * @param id 問い合わせの識別子
     * @param type 種類。決まっていなければ null
     * @param systemId システムの識別子。決まっていなければ null
     * @param functionId 機能の識別子。決まっていなければ null
     * @return 変更後の全内容
     * @throws IllegalArgumentException システムが一覧にない、機能がそのシステムにない、またはシステムなしの機能の場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws InvalidStateException 終わっている場合
     */
    public OperatorDetail replacePlacement(
            UUID id, InquiryType type, String systemId, String functionId, String lang) {
        validatePlacement(systemId, functionId);
        InquiryEntity inquiry = loadHandedOver(id);
        Placement before = inquiry.placement();
        Placement after = new Placement(type, systemId, functionId);
        Transition transition = InquiryStateMachine.placementChanged(inquiry.state(), inquiry.context(null), before, after);
        inquiry.placementOverride = after;
        // 履歴には、設定が変わっても読めるよう、そのときの名前で残す
        inquiry.apply(
                transition,
                Instant.now(),
                java.util.Map.of("before", namesOf(before), "after", namesOf(after)));
        return toDetail(saveAndPublish(inquiry), lang);
    }

    /**
     * 担当者が仕様の持ち主に相談する。相談を足し、仕様の持ち主の番にする（区間は変えない）。
     *
     * @param id 問い合わせの識別子
     * @param to 相談先の名前（1〜5人）。{@code null} か空なら、既定の相談先
     * @param question 相談の文章。前後の空白を除いて1〜4000文字
     * @return 相談後の全内容
     * @throws IllegalArgumentException 相談の文章が不正、相談先が1人も決まらない、6人以上、名前が不正な場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 担当者の番でない場合
     */
    public OperatorDetail consult(UUID id, List<String> to, String question, String lang) {
        String trimmed = question == null ? "" : question.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CONSULTATION_LENGTH) {
            throw new IllegalArgumentException("question は前後の空白を除いて1〜" + MAX_CONSULTATION_LENGTH + "文字");
        }
        InquiryEntity inquiry = loadHandedOver(id);
        List<String> targets = consultants(to, inquiry);
        Transition transition = InquiryStateMachine.consulted(inquiry.state());
        Instant now = Instant.now();
        List<Consultation> all = new ArrayList<>(inquiry.consultations);
        all.add(new Consultation(
                UUID.randomUUID(),
                now,
                properties.dev().operatorName(),
                targets,
                trimmed,
                ConsultationStatus.OPEN,
                null,
                null));
        inquiry.consultations = all;
        inquiry.apply(transition, now);
        return toDetail(saveAndPublish(inquiry), lang);
    }

    /**
     * 担当者が相談を取り下げる。相談を取り下げた相談にし、担当者の番に戻す（渡した理由は変えない）。
     *
     * @param id 問い合わせの識別子
     * @return 取り下げ後の全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 持ち主の決定を待っている状態でない場合
     */
    public OperatorDetail cancelConsultation(UUID id, String lang) {
        InquiryEntity inquiry = loadHandedOver(id);
        Transition transition = InquiryStateMachine.consultationCanceled(inquiry.state());
        Instant now = Instant.now();
        inquiry.cancelOpenConsultations(now);
        inquiry.apply(transition, now);
        return toDetail(saveAndPublish(inquiry), lang);
    }

    /** 既定の相談先。全体で1つの一覧（設定 {@code inquiry.consultants}）。なければ空。 */
    private List<String> defaultConsultants() {
        return properties.consultants() == null ? List.of() : properties.consultants();
    }

    /** 相談先を決める。省くか空なら既定の相談先。1〜5人、名前は前後の空白を除いて1〜100文字。 */
    private List<String> consultants(List<String> to, InquiryEntity inquiry) {
        List<String> chosen = to == null || to.isEmpty() ? defaultConsultants() : to;
        List<String> names = new ArrayList<>();
        for (String name : chosen) {
            String trimmed = name == null ? "" : name.strip();
            if (trimmed.isEmpty() || trimmed.length() > MAX_CONSULTANT_NAME_LENGTH) {
                throw new IllegalArgumentException("相談先の名前は前後の空白を除いて1〜" + MAX_CONSULTANT_NAME_LENGTH + "文字");
            }
            if (!names.contains(trimmed)) {
                names.add(trimmed);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("相談先が決まりません（設定にも、指定にもありません）");
        }
        if (names.size() > MAX_CONSULTANTS) {
            throw new IllegalArgumentException("相談先は" + MAX_CONSULTANTS + "人までです");
        }
        return List.copyOf(names);
    }

    /**
     * 担当者が本人に送る文章の下書きを作る。状態は変えず、メッセージも足さない。担当者が確かめ、直してから、別の操作で送る。
     * 利用量だけを記録する（記録の保存が、ほかの操作と競合しても、下書きは返す）。
     *
     * @param id 問い合わせの識別子
     * @param kind 何を書くか（{@code ASK} 聞き返し、{@code RESULT} 結果）
     * @param note 担当者が伝えたいこと（任意）。前後の空白を除いて1〜2000文字
     * @return 下書きと、書いた言語（本人の言語）
     * @throws IllegalArgumentException note が空白だけ、または長すぎる場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 担当者の番でない場合
     * @throws dev.inquiryassist.core.AiRefusalException モデルが回答を拒否した場合
     * @throws dev.inquiryassist.core.AiUnavailableException モデルを呼び出せなかった場合
     */
    public DraftResult draft(UUID id, ReplyKind kind, String note) {
        String trimmed = null;
        if (note != null) {
            trimmed = note.strip();
            if (trimmed.isEmpty() || trimmed.length() > MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException("note は前後の空白を除いて1〜" + MAX_NOTE_LENGTH + "文字");
            }
        }
        InquiryEntity inquiry = loadHandedOver(id);
        InquiryStateMachine.require(inquiry.state(), Awaiting.HANDLING);
        List<DraftRequest.Message> messages = inquiry.messages.stream()
                .map(m -> new DraftRequest.Message(
                        m.from() == MessageAuthor.OPERATOR ? DraftRequest.Author.OPERATOR : DraftRequest.Author.ASKER,
                        m.body()))
                .toList();
        String judgment = inquiry.adviceStatus == AdviceStatus.DONE && inquiry.advice != null && inquiry.advice.judgment() != null
                ? inquiry.advice.judgment().text()
                : null;
        // その問い合わせについての決定（最後のもの）の内容
        List<PrecedentEntity> decided = precedents.findByInquiryIdOrderByDecidedAtAsc(inquiry.id);
        String decision = decided.isEmpty() ? null : decided.get(decided.size() - 1).decision;
        DraftRequest request = new DraftRequest(
                kind,
                inquiry.structured(),
                AdviceRunner.confirmedFindings(inquiry),
                judgment,
                decision,
                messages,
                trimmed,
                inquiry.language);
        long start = System.nanoTime();
        AiResult<Draft> result;
        try {
            result = drafter.draft(request);
        } catch (AiRefusalException | AiUnavailableException e) {
            Usage usage = e instanceof AiUnavailableException u ? u.usage() : ((AiRefusalException) e).usage();
            recordUsage(
                    id,
                    new SkillUsage(
                            Skill.DRAFTER,
                            usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, implementations.isMock(Skill.DRAFTER)),
                            true,
                            (System.nanoTime() - start) / 1_000_000L));
            throw e;
        }
        recordUsage(
                id,
                new SkillUsage(Skill.DRAFTER, result.usage(), false, (System.nanoTime() - start) / 1_000_000L));
        return new DraftResult(result.value().text(), inquiry.language);
    }

    /** 下書きの利用量を記録する。ほかの操作と競合したら、やり直す。やり直しても競合する場合は、記録を諦める（下書きは返す）。 */
    private void recordUsage(UUID id, SkillUsage usage) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                committer.update(id, inquiry -> {
                    inquiry.addUsage(usage);
                    return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.info("下書きの利用量の保存が競合しました attempt={} id={}", attempt, id);
            } catch (RuntimeException e) {
                log.warn("下書きの利用量を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                return;
            }
        }
    }

    /** 置き場所を、既定の言語の名前にする。決まっていない項目は null。 */
    private java.util.Map<String, String> namesOf(Placement placement) {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("type", placement.type() == null ? null : defaultCatalog().typeLabel(placement.type()));
        names.put("system", placement.systemId() == null ? null : defaultCatalog().systemLabel(placement.systemId()));
        names.put(
                "function",
                placement.functionId() == null ? null : defaultCatalog().functionLabel(placement.systemId(), placement.functionId()));
        return names;
    }

    /** 履歴に残す名前と、置き直しの入力の確かめは、既定の言語の名前で行う（言語を切り替えないもの）。 */
    private IntakeCatalog defaultCatalog() {
        return locales.catalog(locales.defaultLanguage());
    }

    private void validatePlacement(String systemId, String functionId) {
        if (systemId == null && functionId != null) {
            throw new IllegalArgumentException("機能はシステムが決まっているときだけ指定できます");
        }
        if (systemId != null) {
            IntakeCatalog.SystemInfo system = defaultCatalog().system(systemId);
            if (system == null) {
                throw new IllegalArgumentException("一覧にないシステムです: " + systemId);
            }
            if (functionId != null && system.functions().stream().noneMatch(f -> f.id().equals(functionId))) {
                throw new IllegalArgumentException("そのシステムにない機能です: " + functionId);
            }
        }
    }

    /**
     * Slack への投稿結果を記録する。
     *
     * @param id 問い合わせの識別子
     * @param notified 投稿できたか
     * @param error 失敗の理由。成功または未設定で投稿しなかった場合は null
     */
    public void recordSlackResult(UUID id, boolean notified, String error) {
        repository.markSlackResult(id, notified, error);
    }

    /**
     * 保存し、確定後に更新をオペレータの画面へ知らせる。他の操作との競合は {@link InvalidStateException} にする。
     * 配信側の失敗で、確定済みの更新を失敗にしない。
     */
    private InquiryEntity saveAndPublish(InquiryEntity inquiry) {
        inquiry.updatedAt = Instant.now();
        InquiryEntity saved;
        try {
            saved = repository.saveAndFlush(inquiry);
        } catch (OptimisticLockingFailureException e) {
            throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
        }
        try {
            events.publishEvent(presenter.updatedEvent(saved));
        } catch (RuntimeException e) {
            log.error("更新の通知イベントの配信に失敗 id={}", saved.id, e);
        }
        return saved;
    }

    private InquiryEntity loadHandedOver(UUID id) {
        return repository
                .findById(id)
                .filter(e -> e.handedOverAt != null)
                .orElseThrow(() -> new InquiryNotFoundException(id));
    }

    /**
     * 全内容を作る。担当者向けと、仕様の持ち主向け（{@link OwnerService}）で同じ形を返すため、ここで組み立てる。
     *
     * @param e 問い合わせ記録
     * @param lang 見る人の言語
     * @return 全内容
     */
    OperatorDetail toDetail(InquiryEntity e, String lang) {
        // 説明だけが失敗した場合も、保存済みの調査の内容（技術メモ・出典）を出す。このとき grounded は false
        OperatorDetail.AnswerDetail answer = e.answerGrounded == null && e.technicalMemo == null
                ? null
                : new OperatorDetail.AnswerDetail(
                        Boolean.TRUE.equals(e.answerGrounded),
                        e.answered(),
                        e.askerExplanation,
                        List.copyOf(e.answerSteps),
                        e.technicalMemo,
                        e.citations.stream()
                                .map(c -> new OperatorDetail.CitationDetail(
                                        c.sourceType(),
                                        c.repository(),
                                        c.path(),
                                        c.startLine(),
                                        c.endLine(),
                                        c.quote(),
                                        c.revision(),
                                        c.verified()))
                                .toList());
        return new OperatorDetail(
                e.id,
                e.handedOverAt,
                e.language,
                e.askerId,
                properties.dev().userName(),
                e.utterance(),
                e.urgency,
                presenter.placed(e, QuestionKind.TYPE, lang),
                presenter.placed(e, QuestionKind.SYSTEM, lang),
                presenter.placed(e, QuestionKind.FUNCTION, lang),
                presenter.entries(e, lang),
                presenter.structuredView(e),
                List.copyOf(e.revisionNotes),
                answer,
                e.sentImmediately,
                e.readAt != null,
                slackStatus(e),
                e.phase,
                e.turn,
                e.awaiting,
                e.ending,
                e.closedBy,
                e.declined,
                e.handoverReason,
                presenter.investigationOf(e),
                presenter.adviceOf(e),
                defaultConsultants(),
                List.copyOf(e.consultations),
                precedents.findByInquiryIdOrderByDecidedAtAsc(e.id).stream()
                        .map(p -> presenter.precedentOf(p, lang))
                        .toList(),
                precedentFinder.find(e).stream().map(p -> presenter.precedentOf(p, lang)).toList(),
                presenter.noReplyOf(e),
                MessageBodies.viewsOf(e),
                List.copyOf(e.history),
                TraceViews.of(e, properties, implementations, true));
    }

    /**
     * Slack への投稿の状態を判定する。投稿済みなら POSTED、失敗が記録されていれば FAILED。
     * どちらでもなければ、読み出し時点で投稿先が設定されていなければ SKIPPED、あれば PENDING。
     */
    private SlackStatus slackStatus(InquiryEntity e) {
        if (e.slackNotified) {
            return SlackStatus.POSTED;
        }
        if (e.slackError != null) {
            return SlackStatus.FAILED;
        }
        return properties.webhookUrlFor(e.placement().systemId()) == null ? SlackStatus.SKIPPED : SlackStatus.PENDING;
    }
}
