package dev.inquiryassist.core.lifecycle;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 結果を本人が確かめている件の、返事がないときの決まり（契約書「返事がないとき」）。どの件を知らせ、どの件を終えるかを、
 * 現在時刻を引数で受け取って判定する。結果を確かめる状態（{@code RESULT_CONFIRMATION}）の件にだけ使う。
 *
 * <p>状態を持たず、Spring にも HTTP にも依存しない。
 */
public final class NoReplyRule {

    /**
     * 期間の設定。
     *
     * @param remindAfterBusinessDays 結果を確かめる状態になってから、知らせるまでの営業日の数
     * @param closeAfterBusinessDays 知らせてから、終えるまでの営業日の数
     * @param zone 営業日を判定する時間帯
     */
    public record Periods(int remindAfterBusinessDays, int closeAfterBusinessDays, ZoneId zone) {}

    /** 判定の結果。1回の判定で、同じ件に2つの結果を返さない。 */
    public enum Action {
        /** 何もしない。 */
        NONE,
        /** 返事がないことを知らせる。 */
        REMIND,
        /** 返事がないまま期間が過ぎたので終える（回答済み）。 */
        CLOSE
    }

    /**
     * 予定の時刻。
     *
     * @param remindAt 知らせる予定の時刻
     * @param closesAt 終わる予定の時刻。知らせた後だけ。まだ知らせていなければ {@code null}
     */
    public record Schedule(Instant remindAt, Instant closesAt) {}

    private NoReplyRule() {}

    /**
     * 予定の時刻を計算する。知らせる時刻は、結果を確かめる状態になった時刻から数え、終える時刻は、実際に知らせた時刻から数える。
     *
     * @param awaitingSince 結果を確かめる状態になった時刻
     * @param remindedAt 知らせた時刻。まだなら {@code null}
     * @param periods 期間の設定
     * @return 予定の時刻
     */
    public static Schedule scheduleOf(Instant awaitingSince, Instant remindedAt, Periods periods) {
        Instant remindAt = BusinessDays.plus(awaitingSince, periods.remindAfterBusinessDays(), periods.zone());
        Instant closesAt = remindedAt == null
                ? null
                : BusinessDays.plus(remindedAt, periods.closeAfterBusinessDays(), periods.zone());
        return new Schedule(remindAt, closesAt);
    }

    /**
     * 1件について、いま何をするかを決める。まだ知らせていなければ、知らせる時刻（ちょうどを含む）以降に {@code REMIND}。
     * 知らせていれば、終える時刻（ちょうどを含む）以降に {@code CLOSE}。知らせていない件を、知らせずに終えることはない
     * （サーバーが止まっていた後でも、先に知らせる）。
     *
     * @param awaitingSince 結果を確かめる状態になった時刻。不明（{@code null}）なら何もしない
     * @param remindedAt 知らせた時刻。まだなら {@code null}
     * @param now 現在時刻
     * @param periods 期間の設定
     * @return いますること
     */
    public static Action decide(Instant awaitingSince, Instant remindedAt, Instant now, Periods periods) {
        if (awaitingSince == null) {
            return Action.NONE;
        }
        Schedule schedule = scheduleOf(awaitingSince, remindedAt, periods);
        if (remindedAt == null) {
            return now.isBefore(schedule.remindAt()) ? Action.NONE : Action.REMIND;
        }
        return now.isBefore(schedule.closesAt()) ? Action.NONE : Action.CLOSE;
    }
}
