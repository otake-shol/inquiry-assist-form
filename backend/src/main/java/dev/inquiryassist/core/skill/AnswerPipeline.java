package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.ZeroAnswer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 0次回答を作る流れ。調査（AI）、照合（コード）、説明（AI）の順に進める。
 *
 * <p>説明に渡すのは根拠を確かめられた事実だけで、確かめられなかった事実は渡さない。
 * 確かめられた事実が1件もなければ説明を呼ばず、根拠なしにする。
 */
public final class AnswerPipeline {

    /** 照合を始めるときの進捗の文言（日本語）。 */
    public static final String PROGRESS_VERIFYING = "根拠を確かめています";
    /** 説明を呼ぶときの進捗の文言（日本語）。 */
    public static final String PROGRESS_EXPLAINING = "答えをまとめています";
    /** 照合を始めるときの進捗の文言（英語）。 */
    public static final String PROGRESS_VERIFYING_EN = "Checking the evidence";
    /** 説明を呼ぶときの進捗の文言（英語）。 */
    public static final String PROGRESS_EXPLAINING_EN = "Putting the answer together";

    private static boolean english(String language) {
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("en");
    }

    /**
     * パイプラインの結果。
     *
     * @param answer 0次回答
     * @param investigation 照合後の調査結果（出典ごとの照合結果を含む）
     * @param usages スキルごとの利用量（呼んだ順）
     */
    public record Result(ZeroAnswer answer, VerifiedInvestigation investigation, List<SkillUsage> usages) {}

    private final InvestigatorSkill investigator;
    private final InvestigationVerifier verifier;
    private final ExplainerSkill explainer;

    /**
     * @param investigator 調査
     * @param citationVerifier 出典の照合
     * @param explainer 説明
     */
    public AnswerPipeline(
            InvestigatorSkill investigator, CitationVerifier citationVerifier, ExplainerSkill explainer) {
        this.investigator = investigator;
        this.verifier = new InvestigationVerifier(citationVerifier);
        this.explainer = explainer;
    }

    /**
     * 0次回答を作る。
     *
     * @param inquiry 本人が確認した整形後の問い
     * @param scope 投稿者の閲覧範囲
     * @param progress 進捗の通知先
     * @param language 本人の言語。調査と説明をこの言語で書かせ、照合と説明の進捗の文言もこの言語で出す
     * @return 0次回答と、照合結果・利用量
     * @throws AiRefusalException 調査でモデルが回答を拒否した場合
     * @throws AiUnavailableException 調査でモデルを呼び出せなかった場合
     * @throws ExplanationFailedException 説明が失敗した場合。調査と照合の結果を持つ
     */
    public Result run(StructuredInquiry inquiry, AccessScope scope, ProgressListener progress, String language) {
        List<SkillUsage> usages = new ArrayList<>();

        long investigationStart = System.nanoTime();
        AiResult<Investigation> investigation = investigator.investigate(inquiry, scope, progress, language);
        usages.add(new SkillUsage(Skill.INVESTIGATOR, investigation.usage(), false, millisSince(investigationStart)));

        progress.onProgress(english(language) ? PROGRESS_VERIFYING_EN : PROGRESS_VERIFYING);
        VerifiedInvestigation verified = verifier.verify(investigation.value(), scope);

        TechnicalMemo memo = memoOf(verified);
        if (verified.confirmed().isEmpty()) {
            return new Result(ZeroAnswer.ungrounded(memo), verified, usages);
        }

        progress.onProgress(english(language) ? PROGRESS_EXPLAINING_EN : PROGRESS_EXPLAINING);
        AiResult<Explanation> explanation;
        long explanationStart = System.nanoTime();
        try {
            explanation = explainer.explain(inquiry, verified.confirmed(), language);
        } catch (AiRefusalException | AiUnavailableException e) {
            List<SkillUsage> failedUsages = new ArrayList<>(usages);
            // 失敗した呼び出しも費用と時間がかかっているため、時間を記録する
            failedUsages.add(new SkillUsage(Skill.EXPLAINER, failedUsageOf(e), true, millisSince(explanationStart)));
            throw new ExplanationFailedException(e, verified, failedUsages);
        }
        usages.add(new SkillUsage(Skill.EXPLAINER, explanation.usage(), false, millisSince(explanationStart)));

        List<Citation> citations = new ArrayList<>(verified.confirmed().stream()
                .flatMap(f -> f.evidence().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return new Result(new ZeroAnswer(true, explanation.value().answersQuestion(), explanation.value().text(), memo, citations), verified, usages);
    }

    /**
     * 照合後の調査結果から技術メモを作る。確かめられた事実は {@code findings}、確かめられなかった事実は
     * {@code unconfirmed} に入れる。
     *
     * @param verified 照合後の調査結果
     * @return 技術メモ
     */
    public static TechnicalMemo memoOf(VerifiedInvestigation verified) {
        return new TechnicalMemo(
                verified.confirmed().stream().map(Finding::statement).toList(),
                verified.unconfirmed().stream().map(Finding::statement).toList(),
                verified.notFound(),
                verified.nextChecks());
    }

    /** 単調増加の時計で、開始からの経過をミリ秒で返す。壁時計は調整で戻ることがあり、負の時間を記録しないため。 */
    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 失敗が利用量を持っていればそれを使う。持っていなければ、中身のない利用量を記録する。 */
    private static Usage failedUsageOf(RuntimeException failure) {
        Usage usage = failure instanceof AiUnavailableException unavailable
                ? unavailable.usage()
                : failure instanceof AiRefusalException refusal ? refusal.usage() : null;
        return usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, false);
    }
}
