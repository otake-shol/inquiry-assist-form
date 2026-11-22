package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.NoReplyRule;
import dev.inquiryassist.core.lifecycle.Transition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 時間で動く決まりを、本人の返事を待っている件（結果の確かめ、聞き返しへの返事）に適用する（契約書「返事がないとき」）。どの件を知らせ、どの件を終えるかは、
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
     * @param closed 終えた件数（結果の確かめは回答済み、聞き返しへの返事は取り下げ）
     */
    public record Result(int reminded, int closed) {}

    /** 決まりの対象の、待っていること（本人の返事を待っている件）。 */
    private static final List<Awaiting> TARGETS = List.of(Awaiting.RESULT_CONFIRMATION, Awaiting.ASKER_REPLY);

    private final InquiryRepository repository;
    private final InquiryProperties properties;
    private final InquiryCommitter committer;

    /**
     * @param repository 問い合わせ記録の保存先（対象の件を引く）
     * @param properties 期間の設定
     * @param committer 保存と、確定後の通知（担当者のストリームへの配信と、案を作り始める判定を、1か所で行う）
     */
    public TimeRuleService(InquiryRepository repository, InquiryProperties properties, InquiryCommitter committer) {
        this.repository = repository;
        this.properties = properties;
        this.committer = committer;
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
        for (UUID id : repository.findIdsByAwaitingIn(TARGETS)) {
            NoReplyRule.Action[] action = new NoReplyRule.Action[1];
            try {
                committer.update(id, inquiry -> runOne(inquiry, now, action));
            } catch (OptimisticLockingFailureException e) {
                // 本人の返事などと競合した。次の回に見直す
                log.info("返事がないときの決まりの更新が競合したため、次の回に回します id={}", id);
                continue;
            } catch (RuntimeException e) {
                // 1件の失敗で、残りの件の判定を止めない
                log.error("返事がないときの決まりの適用に失敗 id={}", id, e);
                continue;
            }
            if (action[0] == NoReplyRule.Action.REMIND) {
                reminded++;
            } else if (action[0] == NoReplyRule.Action.CLOSE) {
                closed++;
            }
        }
        return new Result(reminded, closed);
    }

    /** 読み込んだ1件を判定して変更する。何もしない件は null（保存しない）。終えた件だけ、担当者のストリームへ更新を流す。 */
    private InquiryCommitter.Update runOne(InquiryEntity inquiry, Instant now, NoReplyRule.Action[] decided) {
        if (InquiryStateMachine.timeEnding(inquiry.awaiting) == null) {
            return null;
        }
        NoReplyRule.Action action =
                NoReplyRule.decide(inquiry.awaitingSince, inquiry.remindedAt, now, properties.rules().periods());
        Transition transition;
        switch (action) {
            case REMIND -> transition = InquiryStateMachine.reminded(inquiry.state());
            case CLOSE -> transition = InquiryStateMachine.closedByTime(inquiry.state());
            default -> {
                return null;
            }
        }
        inquiry.apply(transition, now);
        // どちらも、本人に見てほしい知らせ。担当者の側の未読は変えない
        inquiry.askerUnreadReply = true;
        decided[0] = action;
        return new InquiryCommitter.Update(
                null,
                action == NoReplyRule.Action.CLOSE
                        ? InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER
                        : InquiryCommitter.Publish.NONE);
    }
}
