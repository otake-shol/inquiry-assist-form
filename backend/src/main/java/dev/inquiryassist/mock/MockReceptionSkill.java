package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 受付のモック。キーワードで種類を決め、文章に候補の名前が含まれればシステムと機能を置く（契約書「受付」）。
 * 緊急度は文章から決めない。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.reception.provider", havingValue = "mock", matchIfMissing = true)
public class MockReceptionSkill implements ReceptionSkill {

    /**
     * @param utterance 本人が書いた文章
     * @param candidates システムと機能の候補
     * @param decided 本人が既に選んだ置き場所
     * @return 読み取れた置き場所。システムは、文章にシステムの名前が含まれていれば置く。機能は、システムが決まっていて、
     *     文章にそのシステムの機能の名前が含まれていれば置く
     * @throws AiUnavailableException {@code [判定エラー]} の場合
     */
    @Override
    public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, Placement decided) {
        if (MockScenario.has(utterance, MockScenario.TAG_RECEPTION_ERROR)) {
            throw new AiUnavailableException("モックの受付エラー", null, Usage.ofMock());
        }
        SystemOption system = decided.systemId() != null
                ? candidates.stream().filter(c -> c.id().equals(decided.systemId())).findFirst().orElse(null)
                : candidates.stream().filter(c -> utterance.contains(c.label())).findFirst().orElse(null);
        FunctionOption function = system == null
                ? null
                : system.functions().stream().filter(f -> utterance.contains(f.label())).findFirst().orElse(null);
        return new AiResult<>(
                new Reception(
                        MockScenario.classify(utterance),
                        system == null ? null : system.id(),
                        function == null ? null : function.id()),
                Usage.ofMock());
    }
}
