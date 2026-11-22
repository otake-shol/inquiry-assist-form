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
     * @param language 本人の言語。{@code en} なら英語で組み立てる
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
            List<String> corrections,
            String language) {
        if (MockScenario.has(utterance, MockScenario.TAG_RESTATE_ERROR)) {
            throw new AiUnavailableException("モックの言い直しエラー", null, Usage.ofMock());
        }
        boolean en = MockScenario.english(language);
        String typeSentence = en ? sentenceOfEn(type) : sentenceOf(type);
        List<String> parts = new ArrayList<>();
        parts.add((en ? prefixOfEn(system, function) : prefixOf(system, function)) + typeSentence);
        String text = utterance == null ? "" : MockScenario.stripTags(utterance);
        if (!text.isEmpty()) {
            parts.add((en ? "What was written: " : "書かれた内容: ") + text);
        }
        for (AnsweredQuestion a : answers) {
            if (!a.unknown()) {
                String body = a.question().text() + ": " + a.answer().strip();
                parts.add(en ? "(" + body + ")" : "（" + body + "）");
            }
        }
        corrections.forEach(note -> parts.add((en ? "Note: " : "補足: ") + note.strip()));
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

    private static String prefixOfEn(SystemOption system, FunctionOption function) {
        if (system == null) {
            return "";
        }
        return function == null
                ? "About " + system.label() + ": "
                : "About " + function.label() + " in " + system.label() + ": ";
    }

    private static String sentenceOfEn(InquiryType type) {
        if (type == null) {
            return "I have something to ask about.";
        }
        return switch (type) {
            case HOW_TO -> "I want to know how to use it.";
            case HOW_IT_WORKS -> "I want to know how it currently works.";
            case LOOKS_WRONG ->
                "Something may be behaving incorrectly. I want to check whether this is the intended behavior.";
            case DO_FOR_ME -> "I would like a task to be done.";
            case CHANGE_REQUEST -> "I would like something to be changed.";
        };
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
