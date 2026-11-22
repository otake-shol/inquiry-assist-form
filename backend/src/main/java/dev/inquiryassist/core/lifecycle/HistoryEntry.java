package dev.inquiryassist.core.lifecycle;

import java.time.Instant;
import java.util.Map;

/**
 * 状態の履歴の1件。状態が変わるたびに記録する。誰の番にどれだけ時間がかかったかを、後から測るため。
 *
 * @param at 変わった時刻
 * @param actor 変化を起こした人
 * @param event 出来事
 * @param phase 変化の後の区間
 * @param turn 変化の後の番
 * @param awaiting 変化の後の待っていること
 * @param ending 変化の後の終わり方
 * @param detail 補足。置き直し（{@code PLACEMENT_CHANGED}）だけが持ち、{@code before} と {@code after} に、変える前と後の
 *     置き場所を {@code {type, system, function}} の名前（既定の言語。決まっていなければ {@code null}）で持つ。それ以外は {@code null}
 */
public record HistoryEntry(
        Instant at,
        Actor actor,
        HistoryEvent event,
        Phase phase,
        Turn turn,
        Awaiting awaiting,
        Ending ending,
        Map<String, Map<String, String>> detail) {}
