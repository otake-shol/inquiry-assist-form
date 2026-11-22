package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.Transition;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.ExplanationFailedException;
import dev.inquiryassist.core.skill.ProgressListener;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import dev.inquiryassist.inquiry.InquiryEntity.StoredCitation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * {@link AnswerPipeline}（調査、照合、説明）を実行して、結果を保存する役（契約書「調査の状況と、裏での調査」）。本人が待つ調査も、
 * 担当者に渡した後の裏での調査も、ここを通す。
 *
 * <p>流れは、(1) 始める（調査の状況を {@code RUNNING} にして保存する）、(2) パイプラインを実行する（トランザクションの外。
 * 記録を持ち越さない）、(3) 終わったら記録を<strong>読み直して</strong>、その時点の状態で反映する、の3つ。
 * 読み直した時点で {@code awaiting} が {@code INVESTIGATION} なら、従来どおり本人向けの答えにする（{@code audience: ASKER}）。
 * そうでなければ（担当者に渡した後）、裏の結果として保存する（{@code audience: OPERATOR}。状態は変えない）。
 * 保存で競合したら、読み直して(3)をやり直す（本人の「待たずに送る」や時間切れの引き継ぎと重なりうるため）。
 *
 * <p>同じ問い合わせの調査は、同時に1つだけ走らせる（{@code RUNNING} のとき、始めない）。
 * 失敗の内容（モデルの出力、問い合わせの文章）は、ログのメッセージに入れない。
 */
@Component
class InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRunner.class);
    private static final int MAX_ATTEMPTS = 5;

    /** 調査の結果。{@code failure} は、本人が待つ調査として終えた場合に呼び出し元へ伝える失敗。 */
    private record Outcome(InquiryEntity saved, RuntimeException failure) {}

    private final InquiryRepository repository;
    private final AnswerPipeline pipeline;
    private final CurrentUserProvider users;
    private final InquiryCommitter committer;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param pipeline 調査、照合、説明の流れ
     * @param users 投稿者の閲覧範囲を引く
     * @param committer 遷移の反映と保存
     * @param properties 時間切れの設定
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     */
    InvestigationRunner(
            InquiryRepository repository,
            AnswerPipeline pipeline,
            CurrentUserProvider users,
            InquiryCommitter committer,
            InquiryProperties properties,
            SkillImplementations implementations) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.users = users;
        this.committer = committer;
        this.properties = properties;
        this.implementations = implementations;
    }

    /**
     * 本人が待つ調査を実行する。調査が {@code inquiry.rules.answer-timeout} を超えたら、担当者に渡して返す（調査は止めず、
     * 裏で続ける）。
     *
     * @param id 問い合わせの識別子
     * @param progress 進捗の通知先
     * @return 完了後（または時間切れで担当者に渡した後）の記録
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 調査を待っている状態でない場合
     * @throws InvalidStateException 同じ問い合わせの調査が進行中の場合
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    InquiryEntity runForAsker(UUID id, ProgressListener progress) {
        InquiryEntity started = start(id, true);
        AccessScope scope = users.scopeOf(started.askerId);
        StructuredInquiry structured = inputOf(started);
        String language = started.language;
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        Thread.ofVirtual().name("investigation-" + id).start(() -> {
            try {
                future.complete(execute(id, structured, scope, language, progress));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            Outcome outcome = future.get(properties.rules().answerTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (outcome.failure() != null) {
                throw outcome.failure();
            }
            return outcome.saved();
        } catch (TimeoutException e) {
            return handOverOnTimeout(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("調査の待ちが中断されました");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("調査の保存に失敗しました", cause);
        }
    }

    /**
     * 裏での調査を始める。調査の状況を {@code RUNNING} にして保存するまでを呼び出し元のスレッドで行い、パイプラインの実行と
     * 結果の保存は、別のスレッドで行う（呼び出し元を待たせない）。呼び出し元のトランザクションが確定した後に呼ぶこと。
     *
     * @param id 問い合わせの識別子
     * @return 始めた場合は true。すでに走っている、終わっている、言い直しがない場合は false
     */
    boolean startBackground(UUID id) {
        InquiryEntity started;
        try {
            started = start(id, false);
        } catch (InvalidStateException e) {
            return false;
        }
        AccessScope scope = users.scopeOf(started.askerId);
        StructuredInquiry structured = inputOf(started);
        String language = started.language;
        Thread.ofVirtual().name("investigation-" + id).start(() -> {
            try {
                execute(id, structured, scope, language, message -> {});
            } catch (RuntimeException e) {
                // 結果を保存できなかった。RUNNING のまま残さない
                log.error("裏での調査の結果を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                markFailedQuietly(id);
            }
        });
        return true;
    }

    /**
     * 起動時に、{@code RUNNING} のまま残っている調査を {@code FAILED} にする（裏での調査は API のプロセスの中で走るので、
     * 止まった後に残る）。
     *
     * @param now 終わった時刻として記録する時刻（起動時刻）
     * @return 失敗にした件数
     */
    int failRunning(Instant now) {
        return repository.failRunningInvestigations(now);
    }

    // ---- 始める ----

    /**
     * 調査の状況を {@code RUNNING} にする。始めるかどうかの判定と状況の更新は、1つの更新文で行う（同時に始めようとした一方だけが通る）。
     * 返す記録は、パイプラインの入力（言い直し、言語、本人）を読むためのもの。
     *
     * @param requireInvestigating true なら、本人が待っている（{@code INVESTIGATION}）ことを求める
     */
    private InquiryEntity start(UUID id, boolean requireInvestigating) {
        InquiryEntity inquiry = repository.findById(id).orElseThrow(() -> new InquiryNotFoundException(id));
        if (requireInvestigating) {
            InquiryStateMachine.require(inquiry.state(), Awaiting.INVESTIGATION);
        } else if (inquiry.state().closed() || inquiry.structured() == null) {
            throw new InvalidStateException("調べられる状態ではありません");
        }
        // 本人が確かめていない言い直しで調べる場合は、その印を付ける
        int started = repository.startInvestigation(id, Instant.now(), inquiry.structuredConfirmed == null);
        if (started == 0) {
            throw new InvalidStateException("0次回答を生成中です");
        }
        return inquiry;
    }

    /**
     * 調査に渡す問い。調べない種類（{@code CHANGE_REQUEST}）は、担当者が頼んだときだけ調べるので、種類が決まっていない問いとして渡す
     * （スキルは、調べない種類の問いを受け付けない）。
     */
    private static StructuredInquiry inputOf(InquiryEntity inquiry) {
        StructuredInquiry structured = inquiry.structured();
        if (structured.type() == null || structured.type().investigates()) {
            return structured;
        }
        return new StructuredInquiry(
                structured.summary(),
                null,
                structured.targetSystem(),
                structured.goal(),
                structured.whatHappened(),
                structured.expectedResult());
    }

    // ---- 実行して反映する ----

    private Outcome execute(
            UUID id, StructuredInquiry structured, AccessScope scope, String language, ProgressListener progress) {
        AnswerPipeline.Result result = null;
        RuntimeException failure = null;
        long start = System.nanoTime();
        try {
            result = pipeline.run(structured, scope, progress, language);
        } catch (RuntimeException e) {
            failure = e;
        }
        return finish(id, result, failure, start);
    }

    /**
     * 記録を<strong>トランザクションの中で読み直して</strong>、その時点の状態で結果を反映する。競合したら、読み直してやり直す。
     */
    private Outcome finish(UUID id, AnswerPipeline.Result result, RuntimeException failure, long startNanos) {
        for (int attempt = 1; ; attempt++) {
            RuntimeException[] toThrow = new RuntimeException[1];
            try {
                InquiryEntity saved = committer.update(id, inquiry -> reflect(inquiry, result, failure, startNanos, toThrow));
                return new Outcome(saved, toThrow[0]);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** 読み込んだ記録に、調査の結果（または失敗）を反映する。本人が待つ調査として終えた失敗は、{@code toThrow} に入れる。 */
    private InquiryCommitter.Update reflect(
            InquiryEntity inquiry,
            AnswerPipeline.Result result,
            RuntimeException failure,
            long startNanos,
            RuntimeException[] toThrow) {
        boolean forAsker = inquiry.awaiting == Awaiting.INVESTIGATION;
        inquiry.investigationFinishedAt = Instant.now();
        if (failure == null) {
            store(inquiry, result);
            inquiry.investigationStatus = InvestigationStatus.DONE;
            if (forAsker) {
                inquiry.investigationAudience = InvestigationAudience.ASKER;
                Transition done = InquiryStateMachine.answeredByAi(
                        inquiry.state(), inquiry.context(null), result.answer().grounded(), result.answer().answered());
                return new InquiryCommitter.Update(
                        committer.apply(inquiry, done, false), InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
            }
            inquiry.investigationAudience = InvestigationAudience.OPERATOR;
            return forOperator(inquiry, true);
        }

        RuntimeException failureForAsker = failure;
        boolean resultStored = false;
        if (failure instanceof ExplanationFailedException e) {
            // 費用をかけた調査の結果は失わない。本人向けの回答は作らない（answerGrounded は null のまま）
            inquiry.answerGrounded = null;
            inquiry.answerAnswered = null;
            inquiry.askerExplanation = null;
            inquiry.answerSteps = new ArrayList<>();
            inquiry.technicalMemo = AnswerPipeline.memoOf(e.investigation());
            inquiry.citations = storedCitations(e.investigation().citationResults());
            discardAdvice(inquiry);
            e.usages().forEach(inquiry::addUsage);
            failureForAsker = (RuntimeException) e.getCause();
            resultStored = true;
        } else if (failure instanceof AiUnavailableException || failure instanceof AiRefusalException) {
            // 調査の失敗。分かる場合は、失敗した呼び出しの利用量を記録する
            inquiry.addUsage(new SkillUsage(
                    Skill.INVESTIGATOR, failedUsageOf(failure), true, (System.nanoTime() - startNanos) / 1_000_000L));
        }
        if (forAsker) {
            inquiry.investigationStatus = InvestigationStatus.FAILED;
            toThrow[0] = failureForAsker;
            return new InquiryCommitter.Update(null, InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
        }
        if (resultStored) {
            // 裏での調査で説明だけが失敗した場合は、調査の結果を担当者に渡す（grounded は false、askerExplanation は null）
            inquiry.investigationStatus = InvestigationStatus.DONE;
            inquiry.investigationAudience = InvestigationAudience.OPERATOR;
            return forOperator(inquiry, true);
        }
        inquiry.investigationStatus = InvestigationStatus.FAILED;
        log.warn("裏での調査が失敗しました id={} cause={}", inquiry.id, failure.getClass().getSimpleName());
        // 失敗は担当者のストリームに流すが、未読には戻さない。本人には何も見せない
        return forOperator(inquiry, false);
    }

    /**
     * 裏の結果（または失敗）の保存の仕方。終わっている件は、保存だけ（未読にせず、ストリームにも流さない）。
     * 終わっていない件は、担当者のストリームに更新を流し、結果なら担当者の側を未読に戻す。
     */
    private static InquiryCommitter.Update forOperator(InquiryEntity inquiry, boolean markUnread) {
        if (inquiry.state().closed()) {
            return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
        }
        if (markUnread) {
            inquiry.readAt = null;
        }
        return new InquiryCommitter.Update(null, InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
    }

    /** 調査の結果が置き換わったので、前の結果への案を消す（担当者の番なら、新しい結果への案が作り直される）。 */
    private static void discardAdvice(InquiryEntity inquiry) {
        inquiry.adviceStatus = AdviceStatus.NOT_RUN;
        inquiry.advice = null;
    }

    private static void store(InquiryEntity inquiry, AnswerPipeline.Result result) {
        discardAdvice(inquiry);
        inquiry.answerGrounded = result.answer().grounded();
        inquiry.answerAnswered = result.answer().answered();
        inquiry.askerExplanation = result.answer().askerExplanation();
        inquiry.answerSteps = new ArrayList<>(result.answer().steps());
        inquiry.technicalMemo = result.answer().memo();
        inquiry.citations = storedCitations(result.investigation().citationResults());
        result.usages().forEach(inquiry::addUsage);
    }

    // ---- 時間切れ ----

    /**
     * 本人が待つ調査が時間切れになったので、担当者に渡す。調査は止めない。すでに待つ状態でなければ（調査が終わった、本人が先に送った）、
     * 何もせず、いまの記録を返す。競合したら、読み直してやり直す。
     */
    private InquiryEntity handOverOnTimeout(UUID id) {
        for (int attempt = 1; ; attempt++) {
            try {
                InquiryEntity saved = committer.update(id, inquiry -> {
                    if (inquiry.awaiting != Awaiting.INVESTIGATION) {
                        return null;
                    }
                    Transition timedOut =
                            InquiryStateMachine.investigationTimedOut(inquiry.state(), inquiry.context(null));
                    return new InquiryCommitter.Update(
                            committer.apply(inquiry, timedOut, false), InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
                });
                // 何も変えなかった（待つ状態でなくなっていた）場合は、いまの記録を返す
                return saved != null
                        ? saved
                        : repository.findById(id).orElseThrow(() -> new InquiryNotFoundException(id));
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
                }
            }
        }
    }

    private void markFailedQuietly(UUID id) {
        try {
            committer.update(id, inquiry -> {
                if (inquiry.investigationStatus != InvestigationStatus.RUNNING) {
                    return null;
                }
                inquiry.investigationStatus = InvestigationStatus.FAILED;
                inquiry.investigationFinishedAt = Instant.now();
                return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
            });
        } catch (RuntimeException e) {
            log.error("調査の状況を失敗にできませんでした id={} cause={}", id, e.getClass().getSimpleName());
        }
    }

    /** 失敗が利用量を持っていればそれを使う。なければ、モデルが分からない利用量を、調査の実装に合わせて作る。 */
    private Usage failedUsageOf(RuntimeException failure) {
        Usage usage = failure instanceof AiUnavailableException unavailable
                ? unavailable.usage()
                : failure instanceof AiRefusalException refusal ? refusal.usage() : null;
        return usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, implementations.isMock(Skill.INVESTIGATOR));
    }

    private static List<StoredCitation> storedCitations(List<CitationVerifier.Result> results) {
        return new ArrayList<>(results.stream().map(InvestigationRunner::toStored).toList());
    }

    private static StoredCitation toStored(CitationVerifier.Result result) {
        dev.inquiryassist.core.Citation c = result.citation();
        return new StoredCitation(
                c.sourceType(),
                c.repository(),
                c.path(),
                c.startLine(),
                c.endLine(),
                c.quote(),
                c.revision(),
                result.verified());
    }
}
