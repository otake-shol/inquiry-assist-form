package dev.inquiryassist.mock;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Interview;
import dev.inquiryassist.core.skill.InterviewerSkill;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 聞き手のモック。正常系は2問、対象システムが決まっていれば画面を尋ねる質問を除いた1問。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.interviewer.provider", havingValue = "mock", matchIfMissing = true)
public class MockInterviewerSkill implements InterviewerSkill {

    /**
     * @param utterance 本人が書いた元の発話
     * @param category 種別
     * @param system 決まっている対象システム
     * @param scope 投稿者の閲覧範囲
     * @return 追加質問
     */
    @Override
    public AiResult<Interview> ask(
            String utterance, InquiryCategory category, SystemOption system, AccessScope scope) {
        if (MockScenario.has(utterance, MockScenario.TAG_QUESTION_ERROR)) {
            throw new AiUnavailableException("モックの質問生成エラー", null, Usage.ofMock());
        }
        List<FollowUpQuestion> questions = new ArrayList<>();
        if (!MockScenario.has(utterance, MockScenario.TAG_NO_QUESTIONS)) {
            if (system == null) {
                questions.add(new FollowUpQuestion("q1", "どの画面での話ですか", List.of("求人管理画面", "応募者向けの一覧", "その他")));
            }
            questions.add(new FollowUpQuestion("q2", "求人の状態はどれですか", List.of("掲載中", "下書き", "掲載終了")));
        }
        return new AiResult<>(new Interview(questions), Usage.ofMock());
    }
}
