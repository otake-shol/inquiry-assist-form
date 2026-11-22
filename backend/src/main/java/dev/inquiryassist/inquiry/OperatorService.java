package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.QuestionKind;
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
 * オペレータ向けの一覧・詳細・既読化・対応状況の変更・返信と、通知結果の記録。
 * 担当者に送られた（{@code FILED}）問い合わせだけを扱う。
 */
@Service
public class OperatorService {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;
    private final InquiryPresenter presenter;
    private final ApplicationEventPublisher events;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 投稿先と担当者の表示名の設定
     * @param implementations スキルごとの実装（実行記録の実装名に使う）
     * @param presenter 一覧と詳細の項目を作る
     * @param events 更新の確定後にイベントを発行する
     */
    OperatorService(
            InquiryRepository repository,
            InquiryProperties properties,
            SkillImplementations implementations,
            InquiryPresenter presenter,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.implementations = implementations;
        this.presenter = presenter;
        this.events = events;
    }

    /**
     * 担当者に送られた問い合わせを新しい順に返す。ただし、業務が止まっている（{@code BLOCKED}）件を先頭に寄せる。
     *
     * @param unreadOnly true なら未読だけに絞る
     * @return 一覧と、未読数（絞り込みにかかわらず全体）
     */
    public OperatorList list(boolean unreadOnly) {
        // 寄せ方は安定。BLOCKED の中、それ以外の中は、送られた新しい順のまま
        List<OperatorItem> items = new ArrayList<>(repository.findByOutcomeOrderByFiledAtDesc(InquiryOutcome.FILED).stream()
                .filter(e -> !unreadOnly || e.readAt == null)
                .map(presenter::toItem)
                .toList());
        items.sort(java.util.Comparator.comparing(item -> item.urgency() != Urgency.BLOCKED));
        return new OperatorList(repository.countByOutcomeAndReadAtIsNull(InquiryOutcome.FILED), List.copyOf(items));
    }

    /**
     * 問い合わせの全内容を返す。
     *
     * @param id 問い合わせの識別子
     * @return 全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に送られていない場合
     */
    public OperatorDetail get(UUID id) {
        return toDetail(loadFiled(id));
    }

    /**
     * 既読にして全内容を返す。すでに既読なら既読の時刻を変えない。
     *
     * @param id 問い合わせの識別子
     * @return 既読後の全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に送られていない場合
     */
    public OperatorDetail markRead(UUID id) {
        loadFiled(id);
        repository.markRead(id, Instant.now());
        return toDetail(loadFiled(id));
    }

    /**
     * 対応状況を変える。
     *
     * @param id 問い合わせの識別子
     * @param status 変更後の対応状況
     * @return 変更後の全内容
     * @throws InquiryNotFoundException 存在しない、または担当者に送られていない場合
     */
    public OperatorDetail changeStatus(UUID id, HandlingStatus status) {
        InquiryEntity inquiry = loadFiled(id);
        inquiry.handlingStatus = status;
        return toDetail(saveAndPublish(inquiry));
    }

    /**
     * 担当者が返信する。未対応（{@code OPEN}）なら対応中になり、本人側は未読の返信ありになる。
     * 対応中と完了は変えない。
     *
     * @param id 問い合わせの識別子
     * @param body 本文。前後の空白を除いて1〜4000文字
     * @return 返信後の全内容
     * @throws IllegalArgumentException 本文が空、または長すぎる場合
     * @throws InquiryNotFoundException 存在しない、または担当者に送られていない場合
     */
    public OperatorDetail reply(UUID id, String body) {
        String trimmed = MessageBodies.validated(body);
        InquiryEntity inquiry = loadFiled(id);
        inquiry.addMessage(MessageAuthor.OPERATOR, properties.dev().operatorName(), trimmed, Instant.now());
        inquiry.askerUnreadReply = true;
        if (inquiry.handlingStatus == HandlingStatus.OPEN) {
            inquiry.handlingStatus = HandlingStatus.IN_PROGRESS;
        }
        return toDetail(saveAndPublish(inquiry));
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
            events.publishEvent(new InquiryUpdatedEvent(presenter.toItem(saved)));
        } catch (RuntimeException e) {
            log.error("更新の通知イベントの配信に失敗 id={}", saved.id, e);
        }
        return saved;
    }

    private InquiryEntity loadFiled(UUID id) {
        return repository
                .findById(id)
                .filter(e -> e.outcome == InquiryOutcome.FILED)
                .orElseThrow(() -> new InquiryNotFoundException(id));
    }

    private OperatorDetail toDetail(InquiryEntity e) {
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
                e.filedAt,
                e.askerId,
                properties.dev().userName(),
                e.utterance(),
                e.urgency,
                presenter.placed(e, QuestionKind.TYPE),
                presenter.placed(e, QuestionKind.SYSTEM),
                presenter.placed(e, QuestionKind.FUNCTION),
                presenter.entries(e),
                presenter.structuredView(e),
                List.copyOf(e.revisionNotes),
                answer,
                e.sentImmediately,
                e.readAt != null,
                slackStatus(e),
                e.handlingStatus,
                MessageBodies.viewsOf(e),
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
