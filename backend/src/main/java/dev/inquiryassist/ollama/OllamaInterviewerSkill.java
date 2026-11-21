package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
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
    private final String system = Prompts.systemOf(Skill.INTERVIEWER);

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     */
    public OllamaInterviewerSkill(OllamaClient client, JsonMapper json, InquiryProperties properties) {
        this.call = new JsonSkillCall(client, json, properties.skill(Skill.INTERVIEWER), Prompts.VERSION);
    }

    /**
     * 追加質問を作る。出力は信用せず、空の質問文を捨て、4個以上なら先頭の3個にする。
     * 選択肢の「わからない」「その他」は取り除き、5個以上なら先頭の4個にする。質問の id は実装が振る。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 種別
     * @param system 決まっている対象システム
     * @param scope 投稿者の閲覧範囲
     * @return 追加質問
     */
    @Override
    public AiResult<Interview> ask(String utterance, InquiryCategory category, SystemOption system, AccessScope scope) {
        String user = "## 問い合わせ\n" + utterance + "\n\n## 種別\n" + category.name() + "\n\n## 対象のシステム\n"
                + (system == null ? "なし" : system.label());
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
