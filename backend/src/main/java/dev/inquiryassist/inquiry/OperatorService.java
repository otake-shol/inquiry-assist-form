package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.StructuredInquiry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** オペレータ向けの一覧・詳細・既読化と、通知結果の記録。担当者に送られた（{@code FILED}）問い合わせだけを扱う。 */
@Service
public class OperatorService {

    private static final int SUMMARY_FALLBACK_LENGTH = 80;

    private final InquiryRepository repository;
    private final InquiryProperties properties;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 投稿先の設定
     */
    OperatorService(InquiryRepository repository, InquiryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 担当者に送られた問い合わせを新しい順に返す。
     *
     * @param unreadOnly true なら未読だけに絞る
     * @return 一覧と未読数
     */
    public OperatorList list(boolean unreadOnly) {
        List<InquiryEntity> entities = unreadOnly
                ? repository.findByOutcomeAndReadAtIsNullOrderByFiledAtDesc(InquiryOutcome.FILED)
                : repository.findByOutcomeOrderByFiledAtDesc(InquiryOutcome.FILED);
        return new OperatorList(
                repository.countByOutcomeAndReadAtIsNull(InquiryOutcome.FILED),
                entities.stream().map(OperatorService::toItem).toList());
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
     * Slack への投稿結果を記録する。
     *
     * @param id 問い合わせの識別子
     * @param notified 投稿できたか
     * @param error 失敗の理由。成功または未設定で投稿しなかった場合は null
     */
    public void recordSlackResult(UUID id, boolean notified, String error) {
        repository.markSlackResult(id, notified, error);
    }

    private InquiryEntity loadFiled(UUID id) {
        return repository
                .findById(id)
                .filter(e -> e.outcome == InquiryOutcome.FILED)
                .orElseThrow(() -> new InquiryNotFoundException(id));
    }

    /** 一覧の1件を作る。summary は言い直しの文章、なければ元の発話の先頭80文字。 */
    static OperatorItem toItem(InquiryEntity e) {
        StructuredInquiry structured = e.structured();
        String summary = structured != null
                ? structured.summary()
                : e.utterance.substring(0, Math.min(e.utterance.length(), SUMMARY_FALLBACK_LENGTH));
        return new OperatorItem(
                e.id,
                e.filedAt,
                e.categoryConfirmed,
                e.systemId == null ? null : new InquiryView.SystemView(e.systemId, e.systemLabel),
                summary,
                e.sentImmediately,
                e.readAt != null);
    }

    private OperatorDetail toDetail(InquiryEntity e) {
        StructuredInquiry s = e.structured();
        // 説明だけが失敗した場合も、保存済みの調査の内容（技術メモ・出典）を出す。このとき grounded は false
        OperatorDetail.AnswerDetail answer = e.answerGrounded == null && e.technicalMemo == null
                ? null
                : new OperatorDetail.AnswerDetail(
                        Boolean.TRUE.equals(e.answerGrounded),
                        Boolean.TRUE.equals(e.answerAnswered),
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
                slackStatus(e));
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
