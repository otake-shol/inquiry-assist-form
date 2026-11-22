package dev.inquiryassist.core;

import dev.inquiryassist.core.skill.Advice;
import java.util.List;

/**
 * 助言の出力を、通常のコードで確かめる（{@code docs/skill-architecture.md}「案は、事実に基づくものだけを出す」）。
 * 機械的に確かめられることを、AI に任せない。
 *
 * <ul>
 *   <li>{@code basedOn} のうち、渡した事実の番号として存在しないもの（1未満、または事実の数より大きい）は外す。
 *   <li>外した結果、{@code basedOn} が空になった案（判断の案、手順の案のそれぞれ）は捨てる。基づくものがない案は出さない。
 * </ul>
 *
 * <p>状態を持たず、Spring にも HTTP にも依存しない。
 */
public final class AdviceVerifier {

    private AdviceVerifier() {}

    /**
     * 案を確かめる（先例なし）。
     *
     * @param advice 助言の出力
     * @param findingsCount 助言に渡した事実の数（番号は1から、この数まで）
     * @return 確かめた後の案。両方が捨てられた場合は、どちらも {@code null}
     */
    public static Advice verify(Advice advice, int findingsCount) {
        return verify(advice, findingsCount, 0);
    }

    /**
     * 案を確かめる。事実の番号と、先例の番号のそれぞれについて、存在しないものを外し、外した結果どちらも空になった案
     * （判断の案、手順の案のそれぞれ）は捨てる。
     *
     * @param advice 助言の出力
     * @param findingsCount 助言に渡した事実の数（事実の番号は1から、この数まで）
     * @param precedentsCount 助言に渡した先例の数（先例の番号は1から、この数まで）
     * @return 確かめた後の案。両方が捨てられた場合は、どちらも {@code null}
     */
    public static Advice verify(Advice advice, int findingsCount, int precedentsCount) {
        Advice.Judgment judgment = null;
        if (advice.judgment() != null) {
            Advice.Judgment j = advice.judgment();
            List<Integer> basedOn = existing(j.basedOn(), findingsCount);
            List<Integer> precedents = existing(j.basedOnPrecedents(), precedentsCount);
            if (!basedOn.isEmpty() || !precedents.isEmpty()) {
                judgment = new Advice.Judgment(j.text(), j.needsConsultation(), j.consultationReason(), basedOn, precedents);
            }
        }
        Advice.Procedure procedure = null;
        if (advice.procedure() != null) {
            Advice.Procedure p = advice.procedure();
            List<Integer> basedOn = existing(p.basedOn(), findingsCount);
            List<Integer> precedents = existing(p.basedOnPrecedents(), precedentsCount);
            if (!basedOn.isEmpty() || !precedents.isEmpty()) {
                procedure = new Advice.Procedure(p.steps(), p.rollback(), basedOn, precedents);
            }
        }
        return new Advice(judgment, procedure);
    }

    /** 存在する番号だけを、重複を除いて、並びを保って返す。 */
    private static List<Integer> existing(List<Integer> numbers, int findingsCount) {
        return numbers.stream()
                .filter(n -> n != null && n >= 1 && n <= findingsCount)
                .distinct()
                .toList();
    }
}
