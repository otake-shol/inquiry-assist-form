package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Translation;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 訳のモック（契約書「訳」）。訳さず、原文の前に、訳の見本であることを示す印を付けて返す（訳す先が {@code ja} なら
 * 「【訳の見本】」、{@code en} なら "[Sample translation] "）。{@code [訳エラー]} は、渡された文章のどれかにタグがあれば失敗する。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.translator.provider", havingValue = "mock", matchIfMissing = true)
public class MockTranslatorSkill implements TranslatorSkill {

    /**
     * @param texts 訳す文章
     * @param from 原文の言語
     * @param to 訳す先の言語。{@code en} で始まれば英語の印、そうでなければ日本語の印
     * @return 原文の前に印を付けた文章（渡した順）
     * @throws AiUnavailableException {@code [訳エラー]} の場合
     */
    @Override
    public AiResult<Translation> translate(List<String> texts, String from, String to) {
        if (texts.stream().anyMatch(t -> MockScenario.has(t, MockScenario.TAG_TRANSLATE_ERROR))) {
            throw new AiUnavailableException("モックの訳エラー", null, Usage.ofMock());
        }
        String mark = MockScenario.english(to) ? "[Sample translation] " : "【訳の見本】";
        return new AiResult<>(new Translation(texts.stream().map(t -> mark + t).toList()), Usage.ofMock());
    }
}
