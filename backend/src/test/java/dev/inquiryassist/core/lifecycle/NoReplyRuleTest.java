package dev.inquiryassist.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.lifecycle.NoReplyRule.Action;
import dev.inquiryassist.core.lifecycle.NoReplyRule.Periods;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 返事がないときの判定（契約書「返事がないとき」）を、Spring なしで確かめる。2026-11-23 は月曜。 */
class NoReplyRuleTest {

    private static final Periods PERIODS = new Periods(5, 5, ZoneId.of("UTC"));
    /** 月曜 12:00。5営業日後は翌週の月曜 12:00。 */
    private static final Instant SINCE = Instant.parse("2026-11-23T03:00:00Z");
    private static final Instant REMIND_AT = Instant.parse("2026-11-30T03:00:00Z");

    private static Action decide(Instant remindedAt, Instant now) {
        return NoReplyRule.decide(SINCE, remindedAt, now, PERIODS);
    }

    @Test
    void 予定の時刻は起点から数え_終える時刻は知らせた時刻から数える() {
        NoReplyRule.Schedule before = NoReplyRule.scheduleOf(SINCE, null, PERIODS);
        assertThat(before.remindAt()).isEqualTo(REMIND_AT);
        assertThat(before.closesAt()).isNull();

        // 遅れて（水曜に）知らせた場合、終える時刻は知らせた時刻の5営業日後
        Instant reminded = Instant.parse("2026-12-02T03:00:00Z");
        NoReplyRule.Schedule after = NoReplyRule.scheduleOf(SINCE, reminded, PERIODS);
        assertThat(after.remindAt()).isEqualTo(REMIND_AT);
        assertThat(after.closesAt()).isEqualTo(Instant.parse("2026-12-09T03:00:00Z"));
    }

    @Test
    void 知らせる時刻の前は何もしない() {
        assertThat(decide(null, SINCE)).isEqualTo(Action.NONE);
        assertThat(decide(null, REMIND_AT.minusSeconds(1))).isEqualTo(Action.NONE);
    }

    @Test
    void 知らせる時刻ちょうどから知らせる() {
        assertThat(decide(null, REMIND_AT)).isEqualTo(Action.REMIND);
        assertThat(decide(null, REMIND_AT.plusSeconds(1))).isEqualTo(Action.REMIND);
    }

    @Test
    void 知らせた直後は終えない() {
        assertThat(decide(REMIND_AT, REMIND_AT)).isEqualTo(Action.NONE);
        assertThat(decide(REMIND_AT, REMIND_AT.plusSeconds(3600))).isEqualTo(Action.NONE);
    }

    @Test
    void 終える時刻の前は終えず_ちょうどから終える() {
        Instant closesAt = Instant.parse("2026-12-07T03:00:00Z");
        assertThat(decide(REMIND_AT, closesAt.minusSeconds(1))).isEqualTo(Action.NONE);
        assertThat(decide(REMIND_AT, closesAt)).isEqualTo(Action.CLOSE);
        assertThat(decide(REMIND_AT, closesAt.plusSeconds(1))).isEqualTo(Action.CLOSE);
    }

    @Test
    void 長く止まっていた後でも_知らせていなければ終えずに先に知らせる() {
        Instant muchLater = Instant.parse("2027-06-01T00:00:00Z");
        assertThat(decide(null, muchLater)).isEqualTo(Action.REMIND);
    }

    @Test
    void 終えるまでの期間が0日でも_知らせた回では終えない() {
        Periods immediate = new Periods(5, 0, ZoneId.of("UTC"));
        // 知らせていない間は、期間が0日でも知らせるだけ（1回の判定で両方を返さない）
        assertThat(NoReplyRule.decide(SINCE, null, REMIND_AT, immediate)).isEqualTo(Action.REMIND);
        // 知らせた後の次の判定で、終える
        assertThat(NoReplyRule.decide(SINCE, REMIND_AT, REMIND_AT, immediate)).isEqualTo(Action.CLOSE);
    }

    @Test
    void 起点が不明なら何もしない() {
        assertThat(NoReplyRule.decide(null, null, REMIND_AT, PERIODS)).isEqualTo(Action.NONE);
    }
}
