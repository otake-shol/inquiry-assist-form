package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.RestaterSkill;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 言い直しのモック。契約書「モックの言い直し」の規則で {@code summary} を作り、発話を {@code goal} にそのまま入れる。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.restater.provider", havingValue = "mock", matchIfMissing = true)
public class MockRestaterSkill implements RestaterSkill {

    /**
     * 言い直しを作る。訂正があれば、伝えられた順に「補足: （一言）」を言い直しの末尾へ足す。
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
        StringBuilder summary = new StringBuilder(summarize(utterance, category, answers));
        corrections.forEach(note -> summary.append(" 補足: ").append(note.strip()));
        return new AiResult<>(
                new StructuredInquiry(
                        summary.toString(),
                        category,
                        system == null ? null : system.label(),
                        utterance,
                        null,
                        null,
                        StructuredInquiry.Urgency.NORMAL),
                Usage.ofMock());
    }

    private static String summarize(String utterance, InquiryCategory category, List<AnsweredQuestion> answers) {
        String text = MockScenario.stripTags(utterance);
        if (text.isEmpty()) {
            text = utterance.strip();
        }
        if (category == InquiryCategory.WORK_REQUEST) {
            String what = answerOf(answers, "wr-what");
            String when = answerOf(answers, "wr-when");
            String target = answerOf(answers, "wr-target");
            // 「何を」が分からないときは、書いてもらった内容を代わりに使う
            StringBuilder sb = new StringBuilder(what != null ? what : text).append("を");
            if (when != null) {
                sb.append(when).append("までに");
            }
            sb.append("対応してほしい。");
            if (target != null) {
                sb.append("対象は").append(target).append("。");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (category == InquiryCategory.SPEC_QUESTION) {
            sb.append("「").append(text).append("」について、いまの仕組みがどうなっているかを知りたい。");
        } else {
            sb.append("「").append(text).append("」という状況が起きている。これが意図した動きかどうかを確認したい。");
        }
        for (AnsweredQuestion a : answers) {
            if (!a.unknown()) {
                sb.append("（").append(a.question().text()).append(": ").append(a.answer().strip()).append("）");
            }
        }
        return sb.toString();
    }

    private static String answerOf(List<AnsweredQuestion> answers, String questionId) {
        return answers.stream()
                .filter(a -> a.question().id().equals(questionId) && !a.unknown())
                .map(a -> a.answer().strip())
                .findFirst()
                .orElse(null);
    }
}
