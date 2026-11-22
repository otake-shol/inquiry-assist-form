package dev.inquiryassist.mock;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Investigation;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.ProgressListener;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 調査のモック。事実2件（コードとFAQ）を、{@code mock-knowledge/} の実在する行を根拠にして返す。
 *
 * <p>{@code [根拠なし]}、{@code [幽霊出典]}、{@code [一部幽霊]}、{@code [遅延]}、{@code [エラー]}、
 * {@code [拒否]} のタグを整形後の {@code goal} から読む。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.investigator.provider", havingValue = "mock", matchIfMissing = true)
public class MockInvestigatorSkill implements InvestigatorSkill {

    private static final String CODE_PATH = "src/main/java/com/example/jobboard/JobPostingService.java";

    private static final String CODE_STATEMENT = "求人は、掲載期限の時刻ちょうどから期限切れとして扱われる";
    private static final String FAQ_STATEMENT = "期限切れの求人は、応募者向けの一覧に表示されない";
    private static final String GHOST_STATEMENT_A = "期限切れの求人は、管理画面の再掲載ボタンから元に戻せる";
    private static final String GHOST_STATEMENT_B = "掲載期限は、夜間のバッチで自動的に延長される";
    private static final String CODE_STATEMENT_EN =
            "A job posting is treated as expired from the exact time of its posting deadline";
    private static final String FAQ_STATEMENT_EN = "Expired job postings are not shown in the list for applicants";
    private static final String GHOST_STATEMENT_A_EN =
            "An expired job posting can be restored with the repost button on the admin screen";
    private static final String GHOST_STATEMENT_B_EN = "The posting deadline is automatically extended by a nightly batch";

    private final Duration slowStepDelay;

    /**
     * @param slowStepDelay {@code [遅延]} のときに進捗の各段階で待つ時間
     */
    public MockInvestigatorSkill(@Value("${inquiry.mock.slow-step-delay:2s}") Duration slowStepDelay) {
        this.slowStepDelay = slowStepDelay;
    }

    /**
     * @param inquiry 本人が確認した整形後の問い
     * @param scope 投稿者の閲覧範囲
     * @param progress 進捗の通知先
     * @param language 本人の言語。{@code en} なら事実、次に確認すること、進捗を英語で返す（根拠の引用は資料の原文のまま）
     * @return 調査の結果（出典は未照合）
     * @throws IllegalArgumentException 調べない種類を渡した場合
     * @throws AiRefusalException {@code [拒否]} の場合
     * @throws AiUnavailableException {@code [エラー]} の場合
     */
    @Override
    public AiResult<Investigation> investigate(
            StructuredInquiry inquiry, AccessScope scope, ProgressListener progress, String language) {
        if (inquiry.type() != null && !inquiry.type().investigates()) {
            throw new IllegalArgumentException("調べない種類: " + inquiry.type());
        }
        String tags = inquiry.goal();
        boolean slow = MockScenario.has(tags, MockScenario.TAG_SLOW);

        boolean en = MockScenario.english(language);
        step(slow, progress, en ? "Searching the code" : "コードを検索しています");
        step(slow, progress, en ? "Checking the FAQ" : "FAQを確認しています");
        step(slow, progress, en ? "Matching the glossary" : "用語対応表を照合しています");
        if (MockScenario.has(tags, MockScenario.TAG_ERROR)) {
            throw new AiUnavailableException("モックの調査エラー", null, Usage.ofMock());
        }
        if (MockScenario.has(tags, MockScenario.TAG_REFUSED)) {
            throw new AiRefusalException("モックが調査を拒否しました", Usage.ofMock());
        }

        String codeStatement = en ? CODE_STATEMENT_EN : CODE_STATEMENT;
        String faqStatement = en ? FAQ_STATEMENT_EN : FAQ_STATEMENT;
        String ghostA = en ? GHOST_STATEMENT_A_EN : GHOST_STATEMENT_A;
        String ghostB = en ? GHOST_STATEMENT_B_EN : GHOST_STATEMENT_B;
        List<String> notFound = List.of(en
                ? "No setting or flag that shows expired job postings in the list was found in the job-board code"
                : "期限切れの求人を一覧に出す設定やフラグは、job-board のコードには見つからなかった");
        List<String> nextChecks = List.of(en
                ? "Confirm with the owner of the specification whether the handling of the exact deadline time is intended"
                : "期限の境界（ちょうどの時刻）の扱いが意図した仕様かを、仕様の持ち主に確認する");
        Investigation result;
        if (MockScenario.has(tags, MockScenario.TAG_NO_GROUNDS)) {
            result = new Investigation(
                    List.of(),
                    List.of(en
                            ? "No matching description was found in the job-board code, the FAQ, or the glossary"
                            : "job-board のコード、FAQ、用語対応表に該当する記述は見つからなかった"),
                    List.of(en
                            ? "Ask the user which screen is involved and what behavior they expect"
                            : "関連する画面名と、期待する動作を本人に確認する"));
        } else if (MockScenario.has(tags, MockScenario.TAG_GHOST_CITATION)) {
            result = new Investigation(
                    List.of(
                            new Finding(ghostA, List.of(ghost("GhostService.java", "return ghost.expiresAt();"))),
                            new Finding(ghostB, List.of(ghost("GhostBatch.java", "extendAll(expired);")))),
                    notFound,
                    nextChecks);
        } else if (MockScenario.has(tags, MockScenario.TAG_PARTIAL_GHOST)) {
            result = new Investigation(
                    List.of(
                            new Finding(codeStatement, List.of(codeCitation())),
                            new Finding(ghostA, List.of(ghost("GhostService.java", "return ghost.expiresAt();")))),
                    notFound,
                    nextChecks);
        } else {
            result = new Investigation(
                    List.of(
                            new Finding(codeStatement, List.of(codeCitation())),
                            new Finding(faqStatement, List.of(faqCitation()))),
                    notFound,
                    nextChecks);
        }
        return new AiResult<>(result, Usage.ofMock());
    }

    private void step(boolean slow, ProgressListener progress, String message) {
        if (slow) {
            try {
                Thread.sleep(slowStepDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("モックの待機が中断された", e, Usage.ofMock());
            }
        }
        progress.onProgress(message);
    }

    private static Citation codeCitation() {
        return new Citation(
                Citation.SourceType.CODE, "job-board", CODE_PATH, 10, 12, "return !now.isBefore(posting.expiresAt());", "mock");
    }

    private static Citation faqCitation() {
        return new Citation(
                Citation.SourceType.FAQ,
                "faq",
                "posting-expiry.md",
                5,
                6,
                "掲載期限を過ぎた求人は応募者向けの一覧に表示されません。",
                "mock");
    }

    /** 実在しないファイルを指す出典。照合で落ちる。 */
    private static Citation ghost(String file, String quote) {
        return new Citation(
                Citation.SourceType.CODE, "job-board", "src/main/java/com/example/jobboard/" + file, 1, 3, quote, "mock");
    }
}
