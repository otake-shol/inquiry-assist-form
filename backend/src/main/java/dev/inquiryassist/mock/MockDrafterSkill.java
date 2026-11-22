package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import dev.inquiryassist.core.skill.Draft;
import dev.inquiryassist.core.skill.DraftRequest;
import dev.inquiryassist.core.skill.DrafterSkill;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 下書きのモック（契約書「助言と下書き」）。種類（{@code kind}）と本人の言語で、決まった文章を返す。{@code note} があれば、
 * 文章の中にそのまま入れる。{@code [下書きエラー]} で失敗する（タグは、言い直した問いの {@code goal} から読む）。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.drafter.provider", havingValue = "mock", matchIfMissing = true)
public class MockDrafterSkill implements DrafterSkill {

    /**
     * @param request 依頼
     * @return 下書き。聞き返しは「確認させてください。（note、なければ決まった質問）」、結果は「お待たせしました。確認した結果を
     *     お伝えします。（note、なければ決定の内容、なければ判断の案、それもなければ1件目の事実）」（{@code en} は同じ組み立ての英語）
     * @throws AiUnavailableException {@code [下書きエラー]} の場合
     */
    @Override
    public AiResult<Draft> draft(DraftRequest request) {
        if (request.inquiry() != null && MockScenario.has(request.inquiry().goal(), MockScenario.TAG_DRAFT_ERROR)) {
            throw new AiUnavailableException("モックの下書きエラー", null, Usage.ofMock());
        }
        boolean en = MockScenario.english(request.language());
        String note = request.note();
        String text;
        if (request.kind() == ReplyKind.ASK) {
            String body = note != null
                    ? note
                    : (en
                            ? "Could you tell us a little more about the situation in which this happens?"
                            : "起きている場面を、もう少し詳しく教えていただけますか。");
            text = (en ? "Let me check one thing. " : "確認させてください。") + body;
        } else {
            // note → 決定 → 判断の案 → 事実の順
            String body = note;
            if (body == null) {
                body = request.decision();
            }
            if (body == null) {
                body = request.judgment();
            }
            if (body == null && !request.confirmedFindings().isEmpty()) {
                body = request.confirmedFindings().get(0).statement();
            }
            String lead = en ? "Thank you for waiting. Here is what we found." : "お待たせしました。確認した結果をお伝えします。";
            text = body == null ? lead : lead + (en ? " " : "") + body;
        }
        return new AiResult<>(new Draft(text), Usage.ofMock());
    }
}
