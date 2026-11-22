package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.NoReplyRule;
import dev.inquiryassist.core.lifecycle.Transition;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 時間で動く決まりを、結果を本人が確かめている件に適用する（契約書「返事がないとき」）。どの件を知らせ、どの件を終えるかは、
 * Spring に依存しない {@link NoReplyRule} が決める。ここは、対象の件を引き、1件ずつ別のトランザクションで更新し、通知する。
 *
 * <p>同じ件を別の操作（本人の返事など）と同時に変えようとして楽観ロックが失敗したら、その件は次の回に回す。
 * 知らせは Slack に投稿しない（ツールの中の未読だけ）。
 */
@Service
public class TimeRuleService {

    private static final Logger log = LoggerFactory.getLogger(TimeRuleService.class);

    /**
     * 判定の結果。
     *
     * @param reminded 知らせた件数
     * @param closed 回答済みで終えた件数
     */
    public record Result(int reminded, int closed) {}

    /** 1件の更新の結果。 */
    private record Outcome(NoReplyRule.Action action, InquiryEntity saved) {}

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final InquiryPresenter presenter;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param properties 期間の設定
     * @param presenter 担当者向けの表現を作る
     * @param transaction 1件ごとのトランザクション
     * @param events 担当者のストリームへ更新を流す
     */
    public TimeRuleService(
            InquiryRepository repository,
            InquiryProperties properties,
            InquiryPresenter presenter,
            TransactionTemplate transaction,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.presenter = presenter;
        this.transaction = transaction;
        this.events = events;
    }

    /**
     * 与えた時刻を現在として、返事がないときの決まりを1回判定し、適用する。1回の判定で、同じ件を知らせて続けて終えることはしない。
     *
     * @param now 現在時刻。知らせた時刻と履歴の時刻にも使う
     * @return 知らせた件数と、終えた件数
     */
    public Result run(Instant now) {
        int reminded = 0;
        int closed = 0;
        for (UUID id : repository.findIdsByAwaiting(Awaiting.RESULT_CONFIRMATION)) {
            Outcome outcome;
            try {
                outcome = transaction.execute(status -> runOne(id, now));
            } catch (OptimisticLockingFailureException e) {
                // 本人の返事などと競合した。次の回に見直す
                log.info("返事がないときの決まりの更新が競合したため、次の回に回します id={}", id);
                continue;
            } catch (RuntimeException e) {
                // 1件の失敗で、残りの件の判定を止めない
                log.error("返事がないときの決まりの適用に失敗 id={}", id, e);
                continue;
            }
            if (outcome == null) {
                continue;
            }
            if (outcome.action() == NoReplyRule.Action.REMIND) {
                reminded++;
            } else if (outcome.action() == NoReplyRule.Action.CLOSE) {
                closed++;
                publishUpdated(outcome.saved());
            }
        }
        return new Result(reminded, closed);
    }

    /** 1件を判定して更新する。何もしない件は null。 */
    private Outcome runOne(UUID id, Instant now) {
        InquiryEntity inquiry = repository.findById(id).orElse(null);
        if (inquiry == null || inquiry.awaiting != Awaiting.RESULT_CONFIRMATION) {
            return null;
        }
        NoReplyRule.Action action =
                NoReplyRule.decide(inquiry.awaitingSince, inquiry.remindedAt, now, properties.rules().periods());
        Transition transition;
        switch (action) {
            case REMIND -> transition = InquiryStateMachine.reminded(inquiry.state());
            case CLOSE -> transition = InquiryStateMachine.answeredByTime(inquiry.state());
            default -> {
                return null;
            }
        }
        inquiry.apply(transition, now);
        // どちらも、本人に見てほしい知らせ。担当者の側の未読は変えない
        inquiry.askerUnreadReply = true;
        inquiry.updatedAt = Instant.now();
        // 競合は、このトランザクションの中で検出する（コミットの時点まで持ち越さない）
        InquiryEntity saved = repository.saveAndFlush(inquiry);
        return new Outcome(action, saved);
    }

    /** 担当者に渡したことのある件が終わったら、担当者のストリームへ更新を流す。配信の失敗で、確定済みの更新を失敗にしない。 */
    private void publishUpdated(InquiryEntity saved) {
        if (saved.handedOverAt == null) {
            return;
        }
        try {
            events.publishEvent(presenter.updatedEvent(saved));
        } catch (RuntimeException e) {
            log.error("更新の通知イベントの配信に失敗 id={}", saved.id, e);
        }
    }
}
