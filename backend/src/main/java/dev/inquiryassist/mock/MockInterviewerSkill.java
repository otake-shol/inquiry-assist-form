package dev.inquiryassist.mock;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.skill.Interview;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.Prefill;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 聞き手のモック。質問を1問返し、設定の質問の答えを、選択肢の名前が文章に含まれているかで読み取る（契約書「聞き手」）。 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.interviewer.provider", havingValue = "mock", matchIfMissing = true)
public class MockInterviewerSkill implements InterviewerSkill {

    /**
     * @param utterance 本人が書いた文章
     * @param type 種類
     * @param system システム
     * @param function 機能
     * @param scope 投稿者の閲覧範囲
     * @return 詳しい質問。{@code [質問なし]} なら0問
     * @throws AiUnavailableException {@code [質問エラー]} の場合
     */
    @Override
    public AiResult<Interview> ask(
            String utterance, InquiryType type, SystemOption system, FunctionOption function, AccessScope scope) {
        if (MockScenario.has(utterance, MockScenario.TAG_QUESTION_ERROR)) {
            throw new AiUnavailableException("モックの質問生成エラー", null, Usage.ofMock());
        }
        if (MockScenario.has(utterance, MockScenario.TAG_NO_QUESTIONS)) {
            return new AiResult<>(Interview.none(), Usage.ofMock());
        }
        return new AiResult<>(
                new Interview(List.of(
                        new FollowUpQuestion("q1", "どの場面での話ですか", List.of("画面を開いたとき", "保存したとき", "一覧を見たとき")))),
                Usage.ofMock());
    }

    /**
     * 設定の質問のうち、選択肢の名前が文章にそのまま含まれているものを、その選択肢で読み取る。複数の選択肢が
     * 含まれていれば、設定の順で先のもの。文章で答える質問は読み取らない。
     *
     * @param utterance 本人が書いた文章
     * @param questions まだ答えていない設定の質問
     * @return 読み取った答え
     * @throws AiUnavailableException {@code [質問エラー]} の場合
     */
    @Override
    public AiResult<Prefill> prefill(String utterance, List<IntakeQuestion> questions) {
        if (MockScenario.has(utterance, MockScenario.TAG_QUESTION_ERROR)) {
            throw new AiUnavailableException("モックの質問生成エラー", null, Usage.ofMock());
        }
        List<Prefill.Answer> answers = new ArrayList<>();
        for (IntakeQuestion question : questions) {
            for (Choice option : question.options()) {
                if (utterance != null && utterance.contains(option.label())) {
                    answers.add(new Prefill.Answer(question.id(), option.id(), null));
                    break;
                }
            }
        }
        return new AiResult<>(new Prefill(answers), Usage.ofMock());
    }
}
