package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.Explanation;
import dev.inquiryassist.core.skill.Finding;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 説明のモック。渡された事実の文をつないで作る（「（事実1）。（事実2）。」）。
 * 渡されなかった事実の内容は説明に現れない。{@code [説明エラー]} で失敗し、{@code [的外れ]} で「問いに答えていない」と判定する。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.explainer.provider", havingValue = "mock", matchIfMissing = true)
public class MockExplainerSkill implements ExplainerSkill {

    /**
     * @param inquiry 本人が確認した整形後の問い
     * @param confirmedFindings 根拠を確かめられた事実
     * @param language 本人の言語。{@code en} なら英語でつなぐ
     * @return 本人向けの説明。おかしいかもしれない（{@code LOOKS_WRONG}）では仕様の正否を断定せず、現在の動作として書く
     * @throws AiUnavailableException {@code [説明エラー]} の場合
     */
    @Override
    public AiResult<Explanation> explain(
            StructuredInquiry inquiry, List<Finding> confirmedFindings, String language) {
        if (MockScenario.has(inquiry.goal(), MockScenario.TAG_EXPLAIN_ERROR)) {
            throw new AiUnavailableException("モックの説明エラー", null, Usage.ofMock());
        }
        boolean en = MockScenario.english(language);
        StringBuilder text = new StringBuilder();
        boolean bug = inquiry.type() == InquiryType.LOOKS_WRONG;
        if (bug) {
            text.append(en ? "In the current implementation: " : "現在の実装では、");
        }
        // 英語は「（事実1）. （事実2）.」、日本語は「（事実1）。（事実2）。」
        for (int i = 0; i < confirmedFindings.size(); i++) {
            text.append(confirmedFindings.get(i).statement()).append(en ? "." : "。");
            if (en && i < confirmedFindings.size() - 1) {
                text.append(' ');
            }
        }
        if (bug) {
            text.append(en
                    ? " Whether this behavior is intended will be confirmed by the support team."
                    : "この動作が意図した仕様かどうかは、担当者が確認します。");
        }
        // [的外れ] は、確かめられた事実が問いに答えていないと説明が判定した場合を再現する
        boolean answers = !MockScenario.has(inquiry.goal(), MockScenario.TAG_OFF_TOPIC);
        return new AiResult<>(new Explanation(text.toString(), answers), Usage.ofMock());
    }
}
