package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AdviceVerifier;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.skill.Advice;
import dev.inquiryassist.core.skill.AdvisorSkill;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.PrecedentNote;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 助言（{@link AdvisorSkill}）を呼んで、案を保存する役（契約書「AI の案（advice）」）。{@link InvestigationRunner} と同じ型で、
 * (1) 始める（案の状況を {@code RUNNING} にする）、(2) スキルを実行する（トランザクションの外。記録を持ち越さない）、
 * (3) 記録を読み直して保存する、の順に進め、競合したら読み直してやり直す。
 *
 * <p>呼ぶきっかけは、記録が「担当者の番で、確かめられた事実か同じ置き場所の先例が1つ以上あり、案がまだない」状態で保存されること
 * （{@link #claim}。保存と同じトランザクションで、案の状況を {@code RUNNING} にする）。調査の結果を保存した時点と、調査の結果を持つ問い合わせが担当者の番になった時点の両方を、
 * 同じ判定で拾う。本人が待っている間は、番が担当者でないので呼ばない。調査の結果が置き換わると、前の案を消す
 * （調査を保存する側が、案を {@code NOT_RUN} に戻す）ので、同じ判定で作り直される。
 *
 * <p>失敗の内容（モデルの出力、問い合わせの文章）は、ログのメッセージに入れない。
 */
@Component
class AdviceRunner {

    private static final Logger log = LoggerFactory.getLogger(AdviceRunner.class);
    private static final int MAX_ATTEMPTS = 5;

    /** 保存する結果の種類。 */
    private enum Mode {
        DONE,
        FAILED,
        NOT_RUN
    }

    private final InquiryRepository repository;
    private final AdvisorSkill advisor;
    private final InquiryCommitter committer;
    private final IntakeLocales locales;
    private final SkillImplementations implementations;
    private final PrecedentFinder precedentFinder;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param advisor 助言
     * @param committer 記録の保存と、確定後の通知
     * @param locales 既定の言語（案を書く言語）
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     * @param precedentFinder 同じ置き場所の先例を引く
     */
    AdviceRunner(
            InquiryRepository repository,
            AdvisorSkill advisor,
            InquiryCommitter committer,
            IntakeLocales locales,
            SkillImplementations implementations,
            PrecedentFinder precedentFinder) {
        this.repository = repository;
        this.advisor = advisor;
        this.committer = committer;
        this.locales = locales;
        this.implementations = implementations;
        this.precedentFinder = precedentFinder;
    }

    /**
     * 保存しようとしている記録が、助言を呼ぶべき状態（担当者の番で、確かめられた事実か同じ置き場所の先例が1つ以上あり、案がまだない）なら、案の状況を
     * {@code RUNNING} にする。記録の保存と同じトランザクションで確定させるために、保存の直前に呼ぶ（調査の結果の保存と、案を作り
     * 始める印が、同時に確定する）。すでに作っている・できている・失敗した記録では、何もしない（失敗した案は、作り直さない）。
     * 本人が待っている間は、番が担当者でないので呼ばない。
     *
     * @param inquiry 保存しようとしている記録
     * @return 印を付けた場合は true。保存の確定後に {@link #launch} を呼ぶこと
     */
    boolean claim(InquiryEntity inquiry) {
        // 安い条件（担当者の番で、案が未作成で、言い直しがある）を先に見る。先例を引くのは、事実がなく、必要なときだけ
        if (inquiry.awaiting != Awaiting.HANDLING
                || inquiry.adviceStatus != AdviceStatus.NOT_RUN
                || inquiry.structured() == null) {
            return false;
        }
        if (confirmedFindings(inquiry).isEmpty() && precedentFinder.find(inquiry).isEmpty()) {
            return false;
        }
        inquiry.adviceStatus = AdviceStatus.RUNNING;
        inquiry.advice = null;
        return true;
    }

    /**
     * 案を別のスレッドで作り始める。{@link #claim} で印を付けた記録の保存が確定した後に呼ぶ。
     *
     * @param id 問い合わせの識別子
     */
    void launch(UUID id) {
        Thread.ofVirtual().name("advice-" + id).start(() -> {
            try {
                execute(id);
            } catch (RuntimeException e) {
                log.error("案を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                markFailedQuietly(id);
            }
        });
    }

    /**
     * 起動時に、作っている最中のまま残っている案を失敗にする（案は API のプロセスの中で作るので、止まった後に残る）。
     *
     * @return 失敗にした件数
     */
    int failRunning() {
        return repository.failRunningAdvice();
    }

    /**
     * 記録が持つ、確かめられた事実を、助言に渡す形にする。事実ごとの出典は保存していないので、保存している、照合を通った出典を
     * すべて添える。確かめられた事実の文は技術メモの {@code findings}（調査の結果の並び）で、番号はこの並びの1から始まる番号。
     *
     * @param inquiry 記録
     * @return 確かめられた事実。なければ空
     */
    static List<Finding> confirmedFindings(InquiryEntity inquiry) {
        if (inquiry.technicalMemo == null || inquiry.technicalMemo.findings().isEmpty()) {
            return List.of();
        }
        List<Citation> evidence = inquiry.citations.stream()
                .filter(InquiryEntity.StoredCitation::verified)
                .map(c -> new Citation(
                        c.sourceType(), c.repository(), c.path(), c.startLine(), c.endLine(), c.quote(), c.revision()))
                .toList();
        if (evidence.isEmpty()) {
            return List.of();
        }
        return inquiry.technicalMemo.findings().stream().map(s -> new Finding(s, evidence)).toList();
    }

    // ---- 実行して保存する ----

    private void execute(UUID id) {
        InquiryEntity inquiry = repository.findById(id).orElse(null);
        if (inquiry == null) {
            return;
        }
        StructuredInquiry structured = inquiry.structured();
        List<Finding> findings = confirmedFindings(inquiry);
        // 同じ置き場所の先例（機能が同じものが先、新しい順、最大5件）。案を作った後に新しい先例ができても、作り直さない
        List<PrecedentEntity> precedents = precedentFinder.find(inquiry);
        if (structured == null || (findings.isEmpty() && precedents.isEmpty())) {
            // 呼べる材料がない。作っていない状態に戻す
            save(id, findings, Mode.NOT_RUN, null, null, null, 0);
            return;
        }
        AiResult<Advice> result = null;
        RuntimeException failure = null;
        long start = System.nanoTime();
        try {
            result = advisor.advise(
                    structured,
                    inquiry.type(),
                    findings,
                    precedents.stream()
                            .map(p -> new PrecedentNote(p.situation, p.decisionKind.name(), p.decision, p.reason))
                            .toList(),
                    locales.defaultLanguage());
        } catch (RuntimeException e) {
            failure = e;
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        if (failure == null) {
            Advice verified = AdviceVerifier.verify(result.value(), findings.size(), precedents.size());
            save(id, findings, Mode.DONE, toContent(verified, findings, precedents), result.usage(), null, millis);
        } else {
            log.warn("案の作成が失敗しました id={} cause={}", id, failure.getClass().getSimpleName());
            save(id, findings, Mode.FAILED, null, null, failure, millis);
        }
    }

    /**
     * 記録を読み直して、案（または失敗）を保存する。調査の結果が置き換わっていたら（渡した事実と、いまの事実が違えば）、
     * 古い結果への案なので捨てる（新しい結果への案は、別に作られる）。競合したら、読み直してやり直す。
     */
    private void save(
            UUID id,
            List<Finding> usedFindings,
            Mode mode,
            AdviceContent content,
            Usage usage,
            RuntimeException failure,
            long millis) {
        for (int attempt = 1; ; attempt++) {
            try {
                committer.update(id, inquiry -> {
                    List<String> current = confirmedFindings(inquiry).stream().map(Finding::statement).toList();
                    List<String> used = usedFindings.stream().map(Finding::statement).toList();
                    if (mode != Mode.NOT_RUN && !current.equals(used)) {
                        return null;
                    }
                    switch (mode) {
                        case DONE -> {
                            inquiry.addUsage(Skill.ADVISOR, usage, millis, implementations);
                            inquiry.adviceStatus = AdviceStatus.DONE;
                            inquiry.advice = content;
                        }
                        case FAILED -> {
                            inquiry.addUsage(new SkillUsage(Skill.ADVISOR, failedUsageOf(failure), true, millis), implementations);
                            inquiry.adviceStatus = AdviceStatus.FAILED;
                            inquiry.advice = null;
                        }
                        case NOT_RUN -> {
                            inquiry.adviceStatus = AdviceStatus.NOT_RUN;
                            inquiry.advice = null;
                        }
                    }
                    // 未読には戻さない（未読に戻すのは、調査の結果が届いたとき）
                    return new InquiryCommitter.Update(null, InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** 確かめた案を、基づく事実の番号を文に置き換えて、保存する形にする。 */
    private static AdviceContent toContent(Advice advice, List<Finding> findings, List<PrecedentEntity> precedents) {
        AdviceContent.Judgment judgment = null;
        if (advice.judgment() != null) {
            Advice.Judgment j = advice.judgment();
            judgment = new AdviceContent.Judgment(
                    j.text(),
                    j.needsConsultation(),
                    j.consultationReason(),
                    sentences(j.basedOn(), findings),
                    refs(j.basedOnPrecedents(), precedents));
        }
        AdviceContent.Procedure procedure = null;
        if (advice.procedure() != null) {
            Advice.Procedure p = advice.procedure();
            procedure = new AdviceContent.Procedure(
                    p.steps().stream()
                            .map(s -> new AdviceContent.Step(s.target(), s.action(), s.check()))
                            .toList(),
                    p.rollback(),
                    sentences(p.basedOn(), findings),
                    refs(p.basedOnPrecedents(), precedents));
        }
        return new AdviceContent(judgment, procedure);
    }

    private static List<AdviceContent.PrecedentRef> refs(List<Integer> numbers, List<PrecedentEntity> precedents) {
        return numbers.stream()
                .map(n -> precedents.get(n - 1))
                .map(p -> new AdviceContent.PrecedentRef(p.id, p.decision, p.decidedBy, p.decidedAt))
                .toList();
    }

    private static List<String> sentences(List<Integer> numbers, List<Finding> findings) {
        return numbers.stream().map(n -> findings.get(n - 1).statement()).toList();
    }

    private void markFailedQuietly(UUID id) {
        try {
            committer.update(id, inquiry -> {
                if (inquiry.adviceStatus != AdviceStatus.RUNNING) {
                    return null;
                }
                inquiry.adviceStatus = AdviceStatus.FAILED;
                return new InquiryCommitter.Update(null, InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
            });
        } catch (RuntimeException e) {
            log.error("案の状況を失敗にできませんでした id={} cause={}", id, e.getClass().getSimpleName());
        }
    }

    /** 失敗が利用量を持っていればそれを使う。なければ、モデルが分からない利用量を、助言の実装に合わせて作る。 */
    private Usage failedUsageOf(RuntimeException failure) {
        Usage usage = failure instanceof AiUnavailableException unavailable
                ? unavailable.usage()
                : failure instanceof AiRefusalException refusal ? refusal.usage() : null;
        return usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, implementations.isMock(Skill.ADVISOR));
    }
}
