package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.RestaterSkill;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 言い直しのモック。契約書「言い直し（問いの組み立て）」の規則で {@code summary} を作り、本人が書いた文章を
 * {@code goal} にそのまま入れる（文章がなければ種類ごとの文）。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.restater.provider", havingValue = "mock", matchIfMissing = true)
public class MockRestaterSkill implements RestaterSkill {

    /**
     * 言い直しを作る。置き場所の前置き、種類ごとの文、書かれた内容、詳しい質問への答え、訂正の順につなぐ。
     *
     * @param utterance 本人が書いた文章
     * @param type 種類
     * @param system 決まっているシステム
     * @param function 決まっている機能
     * @param answers 詳しい質問と答え
     * @param corrections 本人が伝えた違う点の一言（伝えられた順）
     * @return 整形後の問い
     * @throws AiUnavailableException {@code [言い直しエラー]} の場合
     */
    @Override
    public AiResult<StructuredInquiry> restate(
            String utterance,
            InquiryType type,
            SystemOption system,
            FunctionOption function,
            List<AnsweredQuestion> answers,
            List<String> corrections) {
        if (MockScenario.has(utterance, MockScenario.TAG_RESTATE_ERROR)) {
            throw new AiUnavailableException("モックの言い直しエラー", null, Usage.ofMock());
        }
        String typeSentence = sentenceOf(type);
        List<String> parts = new ArrayList<>();
        parts.add(prefixOf(system, function) + typeSentence);
        String text = utterance == null ? "" : MockScenario.stripTags(utterance);
        if (!text.isEmpty()) {
            parts.add("書かれた内容: " + text);
        }
        for (AnsweredQuestion a : answers) {
            if (!a.unknown()) {
                parts.add("（" + a.question().text() + ": " + a.answer().strip() + "）");
            }
        }
        corrections.forEach(note -> parts.add("補足: " + note.strip()));
        boolean written = utterance != null && !utterance.isBlank();
        return new AiResult<>(
                new StructuredInquiry(
                        String.join(" ", parts),
                        type,
                        system == null ? null : system.label(),
                        written ? utterance : typeSentence,
                        null,
                        null),
                Usage.ofMock());
    }

    private static String prefixOf(SystemOption system, FunctionOption function) {
        if (system == null) {
            return "";
        }
        return function == null
                ? system.label() + "について、"
                : system.label() + "の" + function.label() + "について、";
    }

    private static String sentenceOf(InquiryType type) {
        if (type == null) {
            return "相談したいことがある。";
        }
        return switch (type) {
            case HOW_TO -> "使い方を知りたい。";
            case HOW_IT_WORKS -> "いまの仕組みがどうなっているかを知りたい。";
            case LOOKS_WRONG -> "動きがおかしいかもしれない。意図した動きかどうかを確認したい。";
            case DO_FOR_ME -> "作業をしてほしい。";
            case CHANGE_REQUEST -> "変えてほしい点がある。";
        };
    }
}
