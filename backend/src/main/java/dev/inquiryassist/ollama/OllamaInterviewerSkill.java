package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.skill.Interview;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 聞き手を、手元のモデル（Ollama）で動かす実装。この実装では用語対応表とFAQの検索は使わない。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.interviewer.provider", havingValue = "ollama")
public class OllamaInterviewerSkill implements InterviewerSkill {

    private static final Set<String> SCREEN_PROVIDED = Set.of("わからない", "その他");
    private static final int MAX_OPTIONS = 4;

    private final JsonSkillCall call;
    private final String system;

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaInterviewerSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        InquiryProperties.SkillSettings settings = properties.skill(Skill.INTERVIEWER);
        this.system = Prompts.systemOf(Skill.INTERVIEWER, settings.promptVersion());
        this.call = new JsonSkillCall(client, json, settings, settings.promptVersion());
    }

    /**
     * 詳しい質問を作る。出力は信用せず、空の質問文を捨て、3個以上なら先頭の2個にする。
     * 選択肢の「わからない」「その他」は取り除き、5個以上なら先頭の4個にする。質問の id は実装が振る。
     *
     * @param utterance 本人が書いた文章。書いていなければ null
     * @param type 種類。決まっていなければ null
     * @param system 決まっているシステム
     * @param function 決まっている機能
     * @param scope 投稿者の閲覧範囲
     * @return 詳しい質問
     */
    @Override
    public AiResult<Interview> ask(
            String utterance, InquiryType type, SystemOption system, FunctionOption function, AccessScope scope) {
        String user = "## 種類\n" + (type == null ? "（未定）" : type.name())
                + "\n\n## システム\n" + (system == null ? "なし" : system.label())
                + "\n\n## 機能\n" + (function == null ? "なし" : function.label())
                + "\n\n## 本人が書いた文章\n" + (utterance == null || utterance.isBlank() ? "（なし）" : utterance);
        return call.call(this.system, user, OllamaInterviewerSkill::toInterview);
    }

    private static Interview toInterview(JsonNode node) {
        JsonNode questions = node.path("questions");
        if (!questions.isArray()) {
            throw new IllegalArgumentException("questions がない");
        }
        List<FollowUpQuestion> result = new ArrayList<>();
        for (JsonNode q : questions) {
            JsonNode text = q.path("text");
            if (!text.isString() || text.asString().isBlank()) {
                continue;
            }
            List<String> options = new ArrayList<>();
            for (JsonNode option : q.path("options")) {
                if (option.isString() && !option.asString().isBlank() && !SCREEN_PROVIDED.contains(option.asString().strip())) {
                    options.add(option.asString().strip());
                }
            }
            if (options.size() > MAX_OPTIONS) {
                options = options.subList(0, MAX_OPTIONS);
            }
            if (result.size() < Interview.MAX_QUESTIONS) {
                result.add(new FollowUpQuestion("q" + (result.size() + 1), text.asString().strip(), options));
            }
        }
        return new Interview(result);
    }
}
