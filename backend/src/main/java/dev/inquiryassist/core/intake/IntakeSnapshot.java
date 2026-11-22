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
 * @param prefillAttempted 答えの読み取りを済ませた（または、読み取らずに本人に聞く）設定の質問の識別子
 */
public record IntakeSnapshot(
        List<IntakeEntry> entries,
        List<IntakeQuestion> interviewed,
        boolean hasText,
        Urgency urgency,
        List<String> prefillAttempted) {

    /**
     * 答えの読み取りを済ませた質問がない状態を作る。
     *
     * @param entries 答え終えた質問
     * @param interviewed 聞き手が作った詳しい質問
     * @param hasText 本人が文章を書いたか
     * @param urgency 緊急度
     */
    public IntakeSnapshot(
            List<IntakeEntry> entries, List<IntakeQuestion> interviewed, boolean hasText, Urgency urgency) {
        this(entries, interviewed, hasText, urgency, List.of());
    }

    public IntakeSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        interviewed = interviewed == null ? null : List.copyOf(interviewed);
        urgency = urgency == null ? Urgency.NORMAL : urgency;
        prefillAttempted = prefillAttempted == null ? List.of() : List.copyOf(prefillAttempted);
    }
}
