package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 受付を、手元のモデル（Ollama）で動かす実装。入力の見出しは {@code docs/skill-architecture.md} の「入力の見出し」に従う。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.reception.provider", havingValue = "ollama")
public class OllamaReceptionSkill implements ReceptionSkill {

    private final JsonSkillCall call;
    private final String system;

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaReceptionSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        InquiryProperties.SkillSettings settings = properties.skill(Skill.RECEPTION);
        this.system = Prompts.systemOf(Skill.RECEPTION, settings.promptVersion());
        this.call = new JsonSkillCall(client, json, settings, settings.promptVersion());
    }

    /**
     * 文章から置き場所を読み取る。出力は信用せず、種類が5つのどれかであること、システムと機能が候補の id であること
     * （なければ null）を確かめる。本人が既に選んだ項目は、出力にかかわらず選んだ値を使う。
     *
     * @param utterance 本人が書いた文章
     * @param candidates システムと機能の候補
     * @param decided 本人が既に選んだ置き場所
     * @return 読み取れた置き場所
     */
    @Override
    public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, Placement decided) {
        StringBuilder user = new StringBuilder("## 文章\n").append(utterance).append("\n\n## システムの候補\n");
        if (candidates.isEmpty()) {
            user.append("なし\n");
        }
        for (SystemOption c : candidates) {
            user.append("- ").append(c.id()).append(": ").append(c.label()).append('\n');
            c.functions().forEach(f -> user.append("  - ").append(f.id()).append(": ").append(f.label()).append('\n'));
        }
        user.append("\n## 既に決まっていること\n");
        List<String> fixed = new ArrayList<>();
        if (decided.type() != null) {
            fixed.add("- 種類: " + decided.type().name());
        }
        if (decided.systemId() != null) {
            fixed.add("- システム: " + decided.systemId());
        }
        if (decided.functionId() != null) {
            fixed.add("- 機能: " + decided.functionId());
        }
        user.append(fixed.isEmpty() ? "なし" : String.join("\n", fixed));
        return call.call(system, user.toString(), node -> toReception(node, candidates, decided));
    }

    private static Reception toReception(JsonNode node, List<SystemOption> candidates, Placement decided) {
        Reception raw = new Reception(typeOf(node.path("type")), idOf(node.path("systemId")), idOf(node.path("functionId")));
        return Reception.normalize(raw, candidates, decided);
    }

    /** 5つの種類のどれかなら、その種類。読み取れない値（null、未知の語）は null にする。 */
    private static InquiryType typeOf(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return InquiryType.valueOf(node.asString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String idOf(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString().strip() : null;
    }
}
