package dev.inquiryassist.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 営業日の計算（契約書「返事がないとき」）を、Spring なしで確かめる。2026-11-20 は金曜、2026-11-21 は土曜。 */
class BusinessDaysTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    @Test
    void 金曜の起点は土日を数えず翌週の営業日になる() {
        // 金曜 12:00 の1営業日後は月曜 12:00
        assertThat(BusinessDays.plus(utc("2026-11-20T03:00:00Z"), 1, UTC)).isEqualTo(utc("2026-11-23T03:00:00Z"));
        assertThat(BusinessDays.plus(utc("2026-11-20T03:00:00Z"), 2, UTC)).isEqualTo(utc("2026-11-24T03:00:00Z"));
    }

    @Test
    void 土曜の起点は起点自体を数えず月曜が1営業日後になる() {
        assertThat(BusinessDays.plus(utc("2026-11-21T03:00:00Z"), 1, UTC)).isEqualTo(utc("2026-11-23T03:00:00Z"));
        assertThat(BusinessDays.plus(utc("2026-11-22T03:00:00Z"), 1, UTC)).isEqualTo(utc("2026-11-23T03:00:00Z"));
    }

    @Test
    void 週をまたぐ5営業日後は翌週の同じ曜日() {
        // 月曜の5営業日後は翌週の月曜
        assertThat(BusinessDays.plus(utc("2026-11-23T03:00:00Z"), 5, UTC)).isEqualTo(utc("2026-11-30T03:00:00Z"));
        // 水曜の5営業日後も翌週の水曜
        assertThat(BusinessDays.plus(utc("2026-11-25T03:00:00Z"), 5, UTC)).isEqualTo(utc("2026-12-02T03:00:00Z"));
        // 金曜の5営業日後は翌週の金曜
        assertThat(BusinessDays.plus(utc("2026-11-20T03:00:00Z"), 5, UTC)).isEqualTo(utc("2026-11-27T03:00:00Z"));
    }

    @Test
    void 平日のうちに収まる日数はそのまま進む() {
        assertThat(BusinessDays.plus(utc("2026-11-23T03:00:00Z"), 3, UTC)).isEqualTo(utc("2026-11-26T03:00:00Z"));
    }

    @Test
    void 日数が0なら起点そのもの() {
        Instant saturday = utc("2026-11-21T03:00:00Z");
        assertThat(BusinessDays.plus(saturday, 0, UTC)).isEqualTo(saturday);
    }

    @Test
    void 負の日数は受け付けない() {
        assertThatThrownBy(() -> BusinessDays.plus(utc("2026-11-20T03:00:00Z"), -1, UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 営業日は指定した時間帯の曜日で判定する() {
        // UTC では金曜 20:00 だが、東京では土曜 05:00。東京の土曜からの1営業日後は月曜 05:00（UTC の日曜 20:00）
        Instant from = utc("2026-11-20T20:00:00Z");
        assertThat(BusinessDays.plus(from, 1, TOKYO)).isEqualTo(utc("2026-11-22T20:00:00Z"));
        // UTC で数えれば、金曜の1営業日後は月曜 20:00
        assertThat(BusinessDays.plus(from, 1, UTC)).isEqualTo(utc("2026-11-23T20:00:00Z"));
    }

    @Test
    void 月から金が営業日で土日は営業日でない() {
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            boolean weekend = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY;
            assertThat(BusinessDays.isBusinessDay(d)).isEqualTo(!weekend);
        }
    }
}
