package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 受付のモック。キーワードで種別を判定し、発話に候補の名前が含まれれば対象システムを推測する。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.reception.provider", havingValue = "mock", matchIfMissing = true)
public class MockReceptionSkill implements ReceptionSkill {

    /**
     * @param utterance 本人が書いた元の発話
     * @param candidates 対象システムの候補
     * @param selected 本人が選んだ対象システム
     * @return 種別と推測した対象システム。本人が選んでいれば推測しない
     */
    @Override
    public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, SystemOption selected) {
        if (MockScenario.has(utterance, MockScenario.TAG_TRIAGE_ERROR)) {
            throw new AiUnavailableException("モックの種別判定エラー", null, Usage.ofMock());
        }
        SystemOption guessed = selected != null
                ? null
                : candidates.stream().filter(c -> utterance.contains(c.label())).findFirst().orElse(null);
        return new AiResult<>(
                new Reception(MockScenario.classify(utterance), guessed == null ? null : guessed.id()),
                Usage.ofMock());
    }
}
