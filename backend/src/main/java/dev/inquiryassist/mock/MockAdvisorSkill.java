package dev.inquiryassist.mock;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Advice;
import dev.inquiryassist.core.skill.AdvisorSkill;
import dev.inquiryassist.core.skill.Finding;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 助言のモック（契約書「助言と下書き」）。判断の案を1つ返す（1件目の事実に基づく）。種類が {@code DO_FOR_ME} か
 * {@code CHANGE_REQUEST} のとき（または {@code [手順]}）は、手順の案も返す（最後の事実に基づく）。
 * {@code [相談]} で相談が要るとし、{@code [根拠なしの案]} で存在しない番号だけに基づく案を返し（コードが捨てる）、
 * {@code [案エラー]} で失敗する。タグは、言い直した問いの {@code goal}（モックの言い直しは本人の文章をそのまま入れる）から読む。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.advisor.provider", havingValue = "mock", matchIfMissing = true)
public class MockAdvisorSkill implements AdvisorSkill {

    /** 存在しない事実の番号（{@code [根拠なしの案]}）。 */
    private static final int MISSING_FINDING = 999;

    /**
     * @param inquiry 言い直した問い
     * @param type 種類
     * @param confirmedFindings 確かめられた事実
     * @param language 案を書く言語。{@code en} なら英語で書く
     * @return 判断の案と、手順の案（種類による）
     * @throws AiUnavailableException {@code [案エラー]} の場合
     */
    @Override
    public AiResult<Advice> advise(
            StructuredInquiry inquiry, InquiryType type, List<Finding> confirmedFindings, String language) {
        String goal = inquiry.goal();
        if (MockScenario.has(goal, MockScenario.TAG_ADVICE_ERROR)) {
            throw new AiUnavailableException("モックの助言エラー", null, Usage.ofMock());
        }
        boolean en = MockScenario.english(language);
        boolean consult = MockScenario.has(goal, MockScenario.TAG_CONSULT);
        boolean ungrounded = MockScenario.has(goal, MockScenario.TAG_UNGROUNDED_ADVICE);

        Advice.Judgment judgment = new Advice.Judgment(
                en
                        ? "This looks like the behavior described in the documents. An explanation of the specification"
                                + " is probably enough; it does not look like a defect."
                        : "資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。",
                consult,
                consult
                        ? (en
                                ? "Please confirm with the owner of the specification whether this behavior is intended."
                                : "この動きが意図した仕様かどうかを、仕様の持ち主に確認する必要がありそうです。")
                        : null,
                ungrounded ? List.of(MISSING_FINDING) : List.of(1));

        Advice.Procedure procedure = null;
        boolean wantsProcedure = MockScenario.has(goal, MockScenario.TAG_PROCEDURE)
                || type == InquiryType.DO_FOR_ME
                || type == InquiryType.CHANGE_REQUEST;
        if (wantsProcedure) {
            procedure = new Advice.Procedure(
                    en
                            ? List.of(
                                    new Advice.Step(
                                            "Editing a job posting in the job admin screen",
                                            "Change the posting deadline to the next day",
                                            "The posting appears in the list for applicants"),
                                    new Advice.Step(
                                            "The list for applicants",
                                            "Reload the list",
                                            "The posting is shown in the list"))
                            : List.of(
                                    new Advice.Step(
                                            "求人管理画面の求人の編集",
                                            "掲載期限を、翌日の日付に変える",
                                            "応募者向けの一覧に、求人が表示されること"),
                                    new Advice.Step("応募者向けの一覧", "一覧を再読み込みする", "求人が一覧に出ていること")),
                    en ? "Change the posting deadline back to the original date" : "掲載期限を、元の日付に戻す",
                    List.of(Math.max(1, confirmedFindings.size())));
        }
        return new AiResult<>(new Advice(judgment, procedure), Usage.ofMock());
    }
}
