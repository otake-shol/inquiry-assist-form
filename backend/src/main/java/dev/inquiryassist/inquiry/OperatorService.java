package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.Declined;
import dev.inquiryassist.core.lifecycle.DeclineReason;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import dev.inquiryassist.core.lifecycle.Transition;
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
 * オペレータ向けの一覧・詳細・既読化と、担当者の操作（聞き返す・結果を伝える、見送る、区間を変える、置き直す）、
 * 通知結果の記録。担当者に渡したことのある問い合わせだけを扱う。状態の遷移の規則は、{@link InquiryStateMachine} が決める。
 */
@Service
public class OperatorService {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);
    private static final int MAX_REASON_LENGTH = 2000;

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;
    private final InquiryPresenter presenter;
    private final IntakeLocales locales;
    private final ApplicationEventPublisher events;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 投稿先と担当者の表示名の設定
     * @param implementations スキルごとの実装（実行記録の実装名に使う）
     * @param presenter 一覧と詳細の項目を作る
     * @param locales 置き直しの入力を確かめる、システムと機能の一覧（言語ごと）
     * @param events 更新の確定後にイベントを発行する
     */
    OperatorService(
            InquiryRepository repository,
            InquiryProperties properties,
            SkillImplementations implementations,
            InquiryPresenter presenter,
            IntakeLocales locales,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.implementations = implementations;
        this.presenter = presenter;
        this.locales = locales;
        this.events = events;
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
     * 担当者が区間を変える。番は変えない。
     *
     * @param id 問い合わせの識別子
     * @param phase 変更後の区間（{@code KNOW}、{@code DECIDE}、{@code ACT}）
     * @return 変更後の全内容
     * @throws IllegalArgumentException 区間が範囲外の場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws InvalidStateException 担当者の番でない場合
     */
    public OperatorDetail changePhase(UUID id, Phase phase, String lang) {
        InquiryEntity inquiry = loadHandedOver(id);
        inquiry.apply(InquiryStateMachine.phaseChanged(inquiry.state(), phase), Instant.now());
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

    private OperatorDetail toDetail(InquiryEntity e, String lang) {
        // 説明だけが失敗した場合も、保存済みの調査の内容（技術メモ・出典）を出す。このとき grounded は false
        OperatorDetail.AnswerDetail answer = e.answerGrounded == null && e.technicalMemo == null
                ? null
                : new OperatorDetail.AnswerDetail(
                        Boolean.TRUE.equals(e.answerGrounded),
                        e.answered(),
                        e.askerExplanation,
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
                e.declined,
                presenter.noReplyOf(e),
                MessageBodies.viewsOf(e),
                List.copyOf(e.history),
                TraceViews.of(e, properties, implementations));
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
