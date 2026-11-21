package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.StructuredInquiry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private static final int SUMMARY_FALLBACK_LENGTH = 80;

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;
    private final ApplicationEventPublisher events;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 投稿先と担当者の表示名の設定
     * @param implementations スキルごとの実装（実行記録の実装名に使う）
     * @param events 更新の確定後にイベントを発行する
     */
    OperatorService(
            InquiryRepository repository,
            InquiryProperties properties,
            SkillImplementations implementations,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.implementations = implementations;
        this.events = events;
    }

    /**
     * 担当者に送られた問い合わせを新しい順に返す。
     *
     * @param unreadOnly true なら未読だけに絞る
     * @param status 指定した対応状況だけに絞る。null なら絞らない
     * @return 一覧と、未読数・対応状況ごとの件数（件数は絞り込みにかかわらず全体）
     */
    public OperatorList list(boolean unreadOnly, HandlingStatus status) {
        List<OperatorItem> items = repository.findByOutcomeOrderByFiledAtDesc(InquiryOutcome.FILED).stream()
                .filter(e -> !unreadOnly || e.readAt == null)
                .filter(e -> status == null || e.handlingStatus == status)
                .map(OperatorService::toItem)
                .toList();
        Map<HandlingStatus, Long> counts = new EnumMap<>(HandlingStatus.class);
        for (HandlingStatus s : HandlingStatus.values()) {
            counts.put(s, repository.countByOutcomeAndHandlingStatus(InquiryOutcome.FILED, s));
        }
        return new OperatorList(repository.countByOutcomeAndReadAtIsNull(InquiryOutcome.FILED), counts, items);
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
            events.publishEvent(new InquiryUpdatedEvent(toItem(saved)));
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

    /** 一覧の要約。言い直しの文章、なければ元の発話の先頭80文字。本人向けの一覧と共有する。 */
    static String summaryOf(InquiryEntity e) {
        StructuredInquiry structured = e.structured();
        return structured != null
                ? structured.summary()
                : e.utterance.substring(0, Math.min(e.utterance.length(), SUMMARY_FALLBACK_LENGTH));
    }

    /** 一覧の1件を作る。 */
    static OperatorItem toItem(InquiryEntity e) {
        return new OperatorItem(
                e.id,
                e.filedAt,
                e.categoryConfirmed,
                e.systemId == null ? null : new InquiryView.SystemView(e.systemId, e.systemLabel),
                summaryOf(e),
                e.sentImmediately,
                e.readAt != null,
                e.handlingStatus,
                e.messages.size());
    }

    private OperatorDetail toDetail(InquiryEntity e) {
        StructuredInquiry s = e.structured();
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
                e.utterance,
                e.categoryByAi,
                e.categoryConfirmed,
                e.systemId == null ? null : new InquiryView.SystemView(e.systemId, e.systemLabel),
                e.systemGuessed,
                e.followUps.stream()
                        .map(q -> followUp(e, q))
                        .toList(),
                s == null
                        ? null
                        : new InquiryView.StructuredView(
                                s.summary(),
                                s.targetSystem(),
                                s.goal(),
                                s.whatHappened(),
                                s.expectedResult(),
                                s.urgency()),
                List.copyOf(e.revisionNotes),
                answer,
                e.sentImmediately,
                e.readAt != null,
                slackStatus(e),
                e.handlingStatus,
                MessageBodies.viewsOf(e),
                TraceViews.of(e, properties, implementations));
    }

    private static OperatorDetail.FollowUpDetail followUp(InquiryEntity e, InquiryEntity.StoredQuestion q) {
        if (!e.answersSubmitted) {
            return new OperatorDetail.FollowUpDetail(q.text(), null, OperatorDetail.FollowUpStatus.NOT_ANSWERED);
        }
        boolean unknown = q.answer() == null || q.answer().isBlank();
        return new OperatorDetail.FollowUpDetail(
                q.text(),
                unknown ? null : q.answer(),
                unknown ? OperatorDetail.FollowUpStatus.UNKNOWN : OperatorDetail.FollowUpStatus.ANSWERED);
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
        return properties.webhookUrlFor(e.systemId) == null ? SlackStatus.SKIPPED : SlackStatus.PENDING;
    }
}
