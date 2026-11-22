package dev.inquiryassist.core.lifecycle;

import dev.inquiryassist.core.Placement;
import java.util.Map;

/**
 * 状態の遷移の結果。
 *
 * @param to 遷移後の状態
 * @param event 出来事。状態が変わらない操作（担当者の番の間の本人の追記）では {@code null}
 * @param actor 変化を起こした人
 * @param handsOver 番が担当者に移ったか（初めて渡したとき、聞き返しや結果への返事、開き直し）。移ったとき、担当者の側の未読に戻す
 * @param detail 履歴の補足（置き直しの前後）。なければ {@code null}
 */
public record Transition(
        InquiryState to, HistoryEvent event, Actor actor, boolean handsOver, Map<String, Placement> detail) {}
