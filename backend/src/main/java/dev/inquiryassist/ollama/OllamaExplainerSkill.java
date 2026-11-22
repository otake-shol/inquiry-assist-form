package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.Explanation;
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
    private final String system;

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaExplainerSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        InquiryProperties.SkillSettings settings = properties.skill(Skill.EXPLAINER);
        this.system = Prompts.systemOf(Skill.EXPLAINER, settings.promptVersion());
        this.call = new JsonSkillCall(client, json, settings, settings.promptVersion());
    }

    /**
     * 確かめられた事実だけから説明を書く。出力は信用せず、説明が空でないことを確かめ、
     * {@code answersQuestion} が欠けている・真偽値でない場合は false にする。
     *
     * @param inquiry 本人が確認した整形後の問い
     * @param confirmedFindings 根拠を確かめられた事実
     * @param language 本人の言語。日本語以外のときだけ、指示文の末尾に書く言語の指示を足す
     * @return 本人向けの説明と、問いに答えているかの判定
     */
    @Override
    public AiResult<Explanation> explain(
            StructuredInquiry inquiry, List<Finding> confirmedFindings, String language) {
        StringBuilder user = new StringBuilder("## 本人の問い\n")
                .append(inquiry.summary())
                .append("\n\n## 種類\n")
                .append(inquiry.type() == null ? "（未定）" : inquiry.type().name())
                .append("\n\n## 確かめられた事実\n");
        confirmedFindings.forEach(f -> user.append("- ").append(f.statement()).append('\n'));
        return call.call(
                Prompts.withOutputLanguage(system, language),
                user.toString().stripTrailing(),
                OllamaExplainerSkill::toExplanation);
    }

    private static Explanation toExplanation(JsonNode node) {
        JsonNode explanation = node.path("explanation");
        if (!explanation.isString() || explanation.asString().isBlank()) {
            throw new IllegalArgumentException("explanation が空");
        }
        // 迷ったら「答えとして見せない」側に倒す。欠けている、または真偽値でない場合は false
        JsonNode answers = node.path("answersQuestion");
        return new Explanation(explanation.asString().strip(), answers.isBoolean() && answers.asBoolean());
    }
}
