package dev.inquiryassist.core.intake;

import dev.inquiryassist.core.Urgency;
import java.util.List;

/**
 * 次の質問を決めるのに必要な、問い合わせの入口の状態。
 *
 * @param entries 答え終えた質問（聞いた順）
 * @param interviewed 聞き手が作った詳しい質問。聞き手をまだ呼んでいなければ {@code null}、呼んで質問がなければ空
 * @param hasText 本人が文章を書いたか（最初から、または途中で。補足は含めない）
 * @param urgency 緊急度
 */
public record IntakeSnapshot(
        List<IntakeEntry> entries, List<IntakeQuestion> interviewed, boolean hasText, Urgency urgency) {

    public IntakeSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        interviewed = interviewed == null ? null : List.copyOf(interviewed);
        urgency = urgency == null ? Urgency.NORMAL : urgency;
    }
}
