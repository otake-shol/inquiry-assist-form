package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 言い直しを、手元のモデル（Ollama）で動かす実装。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.restater.provider", havingValue = "ollama")
public class OllamaRestaterSkill implements RestaterSkill {

    private final JsonSkillCall call;
    private final String system;

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaRestaterSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        InquiryProperties.SkillSettings settings = properties.skill(Skill.RESTATER);
        this.system = Prompts.systemOf(Skill.RESTATER, settings.promptVersion());
        this.call = new JsonSkillCall(client, json, settings, settings.promptVersion());
    }

    /**
     * 言い直しを作る。出力は信用せず、言い直しの文章が空でないことを確かめ、システムが決まっていればその名前を使う。
     * 緊急度は読まない（本人の申告を、AI が書き換えないため）。前回の言い直しは渡さず、訂正を足して最初から言い直す。
     *
     * @param utterance 本人が書いた文章。書いていなければ null
     * @param type 種類。決まっていなければ null
     * @param system 決まっているシステム
     * @param function 決まっている機能
     * @param answers 詳しい質問と答え
     * @param corrections 本人が伝えた違う点の一言（伝えられた順）
     * @return 整形後の問い
     */
    @Override
    public AiResult<StructuredInquiry> restate(
            String utterance,
            InquiryType type,
            SystemOption system,
            FunctionOption function,
            List<AnsweredQuestion> answers,
            List<String> corrections) {
        StringBuilder user = new StringBuilder("## 種類\n").append(type == null ? "（未定）" : type.name());
        user.append("\n\n## システム\n").append(system == null ? "なし" : system.label());
        user.append("\n\n## 機能\n").append(function == null ? "なし" : function.label());
        user.append("\n\n## 質問と答え\n");
        if (answers.isEmpty()) {
            user.append("（なし）\n");
        }
        for (AnsweredQuestion a : answers) {
            user.append("- ")
                    .append(a.question().text())
                    .append(": ")
                    .append(a.unknown() ? "（わからない）" : a.answer().strip())
                    .append('\n');
        }
        user.append("\n## 本人が書いた文章\n")
                .append(utterance == null || utterance.isBlank() ? "（なし）" : utterance)
                .append('\n');
        if (!corrections.isEmpty()) {
            user.append("\n## 本人が後から直したこと\n");
            corrections.forEach(c -> user.append("- ").append(c.strip()).append('\n'));
        }
        return call.call(this.system, user.toString().stripTrailing(), node -> toStructured(node, type, system));
    }

    private static StructuredInquiry toStructured(JsonNode node, InquiryType type, SystemOption system) {
        String summary = text(node, "summary");
        if (summary == null) {
            throw new IllegalArgumentException("summary が空");
        }
        String goal = text(node, "goal");
        return new StructuredInquiry(
                summary,
                type,
                system != null ? system.label() : text(node, "targetSystem"),
                // goal が書かれていない場合は、言い直しの文章で代える
                goal != null ? goal : summary,
                text(node, "whatHappened"),
                text(node, "expectedResult"));
    }

    /** 文字列で空でなければその値、そうでなければ null。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }
}
