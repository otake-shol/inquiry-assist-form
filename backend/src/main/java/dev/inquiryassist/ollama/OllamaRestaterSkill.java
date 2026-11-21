package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 言い直しを、手元のモデル（Ollama）で動かす実装。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.restater.provider", havingValue = "ollama")
public class OllamaRestaterSkill implements RestaterSkill {

    private final JsonSkillCall call;
    private final String system = Prompts.systemOf(Skill.RESTATER);

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaRestaterSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        this.call = new JsonSkillCall(client, json, properties.skill(Skill.RESTATER), Prompts.VERSION);
    }

    /**
     * 言い直しを作る。出力は信用せず、言い直しの文章が空でないこと、緊急度が3つのどれか（なければ NORMAL）を確かめ、
     * 対象システムが決まっていればその名前を使う。前回の言い直しは渡さず、訂正を足して最初から言い直す。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 本人が確認した種別
     * @param answers 追加質問と回答
     * @param system 確定した対象システム
     * @param corrections 本人が伝えた違う点の一言（伝えられた順）
     * @return 整形後の問い
     */
    @Override
    public AiResult<StructuredInquiry> restate(
            String utterance,
            InquiryCategory category,
            List<AnsweredQuestion> answers,
            SystemOption system,
            List<String> corrections) {
        StringBuilder user = new StringBuilder("## 問い合わせ\n").append(utterance).append("\n\n## 種別\n").append(category.name());
        user.append("\n\n## 対象のシステム\n").append(system == null ? "なし" : system.label());
        user.append("\n\n## 追加の質問と回答\n");
        if (answers.isEmpty()) {
            user.append("（追加の質問なし）\n");
        }
        for (AnsweredQuestion a : answers) {
            user.append("- ")
                    .append(a.question().text())
                    .append(": ")
                    .append(a.unknown() ? "（わからない）" : a.answer().strip())
                    .append('\n');
        }
        if (!corrections.isEmpty()) {
            user.append("\n## 本人が後から直したこと\n");
            corrections.forEach(c -> user.append("- ").append(c.strip()).append('\n'));
        }
        return call.call(this.system, user.toString().stripTrailing(), node -> toStructured(node, category, system));
    }

    private static StructuredInquiry toStructured(JsonNode node, InquiryCategory category, SystemOption system) {
        String summary = text(node, "summary");
        if (summary == null) {
            throw new IllegalArgumentException("summary が空");
        }
        String goal = text(node, "goal");
        return new StructuredInquiry(
                summary,
                category,
                system != null ? system.label() : text(node, "targetSystem"),
                // goal が書かれていない場合は、言い直しの文章で代える
                goal != null ? goal : summary,
                text(node, "whatHappened"),
                text(node, "expectedResult"),
                urgency(node));
    }

    private static StructuredInquiry.Urgency urgency(JsonNode node) {
        String value = text(node, "urgency");
        if (value != null) {
            try {
                return StructuredInquiry.Urgency.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 3つのどれでもなければ既定値
            }
        }
        return StructuredInquiry.Urgency.NORMAL;
    }

    /** 文字列で空でなければその値、そうでなければ null。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }
}
