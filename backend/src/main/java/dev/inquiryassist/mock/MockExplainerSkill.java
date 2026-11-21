package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.Finding;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 説明のモック。渡された事実の文をつないで作る（「（事実1）。（事実2）。」）。
 * 渡されなかった事実の内容は説明に現れない。{@code [説明エラー]} で失敗する。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.explainer.provider", havingValue = "mock", matchIfMissing = true)
public class MockExplainerSkill implements ExplainerSkill {

    /**
     * @param inquiry 本人が確認した整形後の問い
     * @param confirmedFindings 根拠を確かめられた事実
     * @return 本人向けの説明。不具合の疑いでは仕様の正否を断定せず、現在の動作として書く
     * @throws AiUnavailableException {@code [説明エラー]} の場合
     */
    @Override
    public AiResult<String> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings) {
        if (MockScenario.has(inquiry.goal(), MockScenario.TAG_EXPLAIN_ERROR)) {
            throw new AiUnavailableException("モックの説明エラー", null, Usage.ofMock());
        }
        StringBuilder text = new StringBuilder();
        boolean bug = inquiry.category() == InquiryCategory.SUSPECTED_BUG;
        if (bug) {
            text.append("現在の実装では、");
        }
        confirmedFindings.forEach(f -> text.append(f.statement()).append("。"));
        if (bug) {
            text.append("この動作が意図した仕様かどうかは、担当者が確認します。");
        }
        return new AiResult<>(text.toString(), Usage.ofMock());
    }
}
