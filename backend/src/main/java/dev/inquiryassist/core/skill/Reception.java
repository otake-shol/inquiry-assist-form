package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import java.util.List;

/**
 * 受付の出力。文章から読み取れた置き場所。読み取れなかった項目は {@code null}。
 *
 * @param type 文章から読み取った種類
 * @param systemId 文章から読み取ったシステムの識別子
 * @param functionId 文章から読み取った機能の識別子
 */
public record Reception(InquiryType type, String systemId, String functionId) {

    /**
     * 受付の出力を、本人が選んだ置き場所と候補に照らして整える。モデルの出力は信用しない。
     *
     * <p>本人が既に選んだ項目は、出力にかかわらず選んだ値にする。候補にない id は {@code null} にする。
     * 機能は、決まったシステムの機能でなければ {@code null} にする。
     *
     * @param raw 受付の出力
     * @param candidates システムと機能の候補
     * @param decided 本人が既に選んだ置き場所
     * @return 整えた置き場所
     */
    public static Reception normalize(Reception raw, List<SystemOption> candidates, Placement decided) {
        InquiryType type = decided.type() != null ? decided.type() : raw.type();
        String systemId = decided.systemId() != null ? decided.systemId() : raw.systemId();
        SystemOption system = candidates.stream()
                .filter(c -> c.id().equals(systemId))
                .findFirst()
                .orElse(null);
        String functionId = decided.functionId() != null ? decided.functionId() : raw.functionId();
        if (system == null) {
            // 候補にないシステムは置かない。システムがなければ機能も置けない
            return new Reception(type, null, null);
        }
        boolean known = system.functions().stream().map(FunctionOption::id).anyMatch(id -> id.equals(functionId));
        return new Reception(type, system.id(), known ? functionId : null);
    }
}
