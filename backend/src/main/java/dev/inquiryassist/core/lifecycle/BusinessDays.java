package dev.inquiryassist.core.lifecycle;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 営業日の計算（契約書「返事がないとき」）。営業日は月曜から金曜。祝日は扱わない。
 *
 * <p>状態を持たず、Spring にも HTTP にも依存しない。
 */
public final class BusinessDays {

    private BusinessDays() {}

    /**
     * @param day 曜日
     * @return 営業日（月曜から金曜）なら true
     */
    public static boolean isBusinessDay(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * 起点の N 営業日後を返す。起点の時刻から日を1日ずつ進め、進めた先が営業日のときだけ数えて、N 回数えた日の同じ時刻。
     * 起点が休日でも、そこから進めるだけなので、起点自体は数えない。夏時間などで同じ時刻がない日は、時間帯の規則に従う。
     *
     * @param from 起点
     * @param days 営業日の数（0以上）。0 なら起点そのもの
     * @param zone 営業日を判定する時間帯
     * @return N 営業日後の時刻
     * @throws IllegalArgumentException 営業日の数が負の場合
     */
    public static Instant plus(Instant from, int days, ZoneId zone) {
        if (days < 0) {
            throw new IllegalArgumentException("営業日の数は0以上: " + days);
        }
        ZonedDateTime cursor = from.atZone(zone);
        int counted = 0;
        while (counted < days) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor.getDayOfWeek())) {
                counted++;
            }
        }
        return cursor.toInstant();
    }
}
