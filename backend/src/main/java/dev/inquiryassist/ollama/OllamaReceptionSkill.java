package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 受付を、手元のモデル（Ollama）で動かす実装。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.reception.provider", havingValue = "ollama")
public class OllamaReceptionSkill implements ReceptionSkill {

    private final JsonSkillCall call;
    private final String system = Prompts.systemOf(Skill.RECEPTION);

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaReceptionSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        this.call = new JsonSkillCall(client, json, properties.skill(Skill.RECEPTION), Prompts.VERSION);
    }

    /**
     * 種別を判定し、対象システムを推測する。出力は信用せず、種別が4つのどれかであること、
     * 対象システムが候補の id であること（なければ null）を確かめる。本人が選んだシステムがあればそれを使う。
     *
     * @param utterance 本人が書いた元の発話
     * @param candidates 対象システムの候補
     * @param selected 本人が選んだ対象システム
     * @return 種別と対象システム
     */
    @Override
    public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, SystemOption selected) {
        StringBuilder user = new StringBuilder("## 問い合わせ\n").append(utterance).append("\n\n## システムの候補\n");
        if (candidates.isEmpty()) {
            user.append("なし\n");
        }
        candidates.forEach(c -> user.append("- ").append(c.id()).append(": ").append(c.label()).append('\n'));
        user.append("\n## 本人が選んだシステム\n").append(selected == null ? "なし" : selected.id());
        return call.call(system, user.toString(), node -> toReception(node, candidates, selected));
    }

    private static Reception toReception(JsonNode node, List<SystemOption> candidates, SystemOption selected) {
        JsonNode category = node.path("category");
        if (!category.isString()) {
            throw new IllegalArgumentException("category がない");
        }
        InquiryCategory value = InquiryCategory.valueOf(category.asString().strip().toUpperCase(Locale.ROOT));
        if (selected != null) {
            return new Reception(value, selected.id());
        }
        JsonNode system = node.path("systemId");
        String guessed = system.isString() ? system.asString().strip() : null;
        boolean known = guessed != null && candidates.stream().anyMatch(c -> c.id().equals(guessed));
        return new Reception(value, known ? guessed : null);
    }
}
