package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 説明を、手元のモデル（Ollama）で動かす実装。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.explainer.provider", havingValue = "ollama")
public class OllamaExplainerSkill implements ExplainerSkill {

    private final JsonSkillCall call;
    private final String system = Prompts.systemOf(Skill.EXPLAINER);

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaExplainerSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        this.call = new JsonSkillCall(client, json, properties.skill(Skill.EXPLAINER), Prompts.VERSION);
    }

    /**
     * 確かめられた事実だけから説明を書く。出力は信用せず、説明が空でないことを確かめる。
     *
     * @param inquiry 本人が確認した整形後の問い
     * @param confirmedFindings 根拠を確かめられた事実
     * @return 本人向けの説明
     */
    @Override
    public AiResult<String> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings) {
        StringBuilder user = new StringBuilder("## 本人の問い\n")
                .append(inquiry.summary())
                .append("\n\n## 種別\n")
                .append(inquiry.category().name())
                .append("\n\n## 確かめられた事実\n");
        confirmedFindings.forEach(f -> user.append("- ").append(f.statement()).append('\n'));
        return call.call(system, user.toString().stripTrailing(), OllamaExplainerSkill::toExplanation);
    }

    private static String toExplanation(JsonNode node) {
        JsonNode explanation = node.path("explanation");
        if (!explanation.isString() || explanation.asString().isBlank()) {
            throw new IllegalArgumentException("explanation が空");
        }
        return explanation.asString().strip();
    }
}
