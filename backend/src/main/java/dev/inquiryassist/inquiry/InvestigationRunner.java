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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * {@link AnswerPipeline}（調査、照合、説明）を実行して、結果を保存する役（契約書「調査の状況と、裏での調査」）。本人が待つ調査も、
 * 担当者に渡した後の裏での調査も、ここを通す。本人が待つ調査は、問いを確かめた時点で API が始める。
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
    private final AnswerStreamHub streams;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param pipeline 調査、照合、説明の流れ
     * @param users 投稿者の閲覧範囲を引く
     * @param committer 遷移の反映と保存
     * @param properties 時間切れの設定
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     * @param streams 進み具合を見ている画面（進捗ストリーム）
     */
    InvestigationRunner(
            InquiryRepository repository,
            AnswerPipeline pipeline,
            CurrentUserProvider users,
            InquiryCommitter committer,
            InquiryProperties properties,
            SkillImplementations implementations,
            AnswerStreamHub streams) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.users = users;
        this.committer = committer;
        this.properties = properties;
        this.implementations = implementations;
        this.streams = streams;
    }

    /**
     * 本人が待つ調査（{@code awaiting: INVESTIGATION}）を始める。問いを確かめた時点で API が始める（画面の進捗ストリームへの
     * つなぎを待たない）。調査の状況を {@code RUNNING} にして保存するまでを呼び出し元のスレッドで行い、パイプラインの実行と結果の
     * 保存は別のスレッドで行う（呼び出し元を待たせない）。あわせて、{@code inquiry.rules.answer-timeout} 後の処理を仕掛ける
     * （その時点でまだ {@code INVESTIGATION} なら、担当者に渡す。調査が走っていても、失敗して止まっていても）。
     * 進捗と、終わったこと・失敗したことは、見ている画面（{@link AnswerStreamHub}）へ配る。
     *
     * @param id 問い合わせの識別子
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 調査を待つ状態でない場合
     * @throws InvalidStateException 同じ問い合わせの調査が進行中の場合
     */
    void startForAsker(UUID id) {
        InquiryEntity started = start(id, true);
        AccessScope scope = users.scopeOf(started.askerId);
        StructuredInquiry structured = started.structured();
        String language = started.language;
        Thread.ofVirtual().name("investigation-" + id).start(() -> {
            try {
                Outcome outcome = execute(id, structured, scope, language, message -> streams.progress(id, message));
                if (outcome.failure() != null) {
                    streams.failed(id, outcome.failure());
                } else {
                    streams.done(id);
                }
            } catch (RuntimeException e) {
                // 結果を保存できなかった。RUNNING のまま残さない
                log.error("調査の結果を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                markFailedQuietly(id);
                streams.failed(id, new AiUnavailableException("内部エラーが発生しました", null));
            }
        });
        Duration timeout = properties.rules().answerTimeout();
        Thread.ofVirtual().name("investigation-timeout-" + id).start(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (timeoutHandOver(id)) {
                    streams.done(id);
                }
            } catch (RuntimeException e) {
                log.error("調査の時間切れの引き継ぎに失敗しました id={} cause={}", id, e.getClass().getSimpleName());
            }
        });
    }

    /**
     * {@link #startForAsker} を、失敗しても例外にせずに呼ぶ（問いを確かめた応答は、すでに確定している。始められなかった場合は、
     * 進捗ストリームへのつなぎ、時間切れ、起動時の処理が、引き継ぐ）。
     *
     * @param id 問い合わせの識別子
     */
    void startForAskerQuietly(UUID id) {
        try {
            startForAsker(id);
        } catch (RuntimeException e) {
            log.warn("調査を始められませんでした id={} cause={}", id, e.getClass().getSimpleName());
        }
    }

    /**
     * 調査の進み具合を見る（進捗ストリームのつなぎ）。つないだ時点で、次のように動く。
     * <ul>
     *   <li>調査が走っている: その後の進捗と、終わったときの {@code done}／{@code failed} が届く。
     *   <li>調査を待つ状態が、すでに終わっている: すぐに {@code done}。
     *   <li>{@code INVESTIGATION} で、調査が走っていない（失敗した後）: 調査を始めて、同じように届く（再試行）。
     * </ul>
     * 聞き手を先に登録してから状態を読むので、調査が終わる・失敗する・時間切れになるのと重なっても、取りこぼさない
     * （終わった後なら、状態の読み直しで {@code done} になり、終わる前なら、登録した聞き手に届く）。
     *
     * @param id 問い合わせの識別子
     * @param listener 聞き手
     * @return 聞き手の登録を外す処理
     * @throws InquiryNotFoundException 存在しない場合
     */
    Runnable watch(UUID id, AnswerStreamHub.Listener listener) {
        Runnable cancel = streams.subscribe(id, listener);
        InquiryEntity inquiry = repository.findById(id).orElse(null);
        if (inquiry == null) {
            cancel.run();
            throw new InquiryNotFoundException(id);
        }
        if (inquiry.awaiting != Awaiting.INVESTIGATION) {
            cancel.run();
            listener.done();
            return () -> {};
        }
        if (inquiry.investigationStatus != InvestigationStatus.RUNNING) {
            try {
                startForAsker(id);
            } catch (InvalidStateException e) {
                // 同時にほかの画面がつないで、調査を始めた。その続きを見る
            } catch (RuntimeException e) {
                // 待つ状態でなくなっていた（本人が先に送った、時間切れになった）。いまの状態を見せる
                cancel.run();
                listener.done();
                return () -> {};
            }
        }
        return cancel;
    }

    /**
     * 本人が待つ状態が、調査の外で終わった（本人が答えを待たずに送った）ので、進捗を見ている画面に {@code done} を伝える。
     * 走っている調査は止めない（終わった時点の状態で、裏の結果として保存される）。
     *
     * @param id 問い合わせの識別子
     */
    void endWatching(UUID id) {
        streams.done(id);
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
        StructuredInquiry structured = started.structured();
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
            e.usages().forEach(u -> inquiry.addUsage(u, implementations));
            failureForAsker = (RuntimeException) e.getCause();
            resultStored = true;
        } else if (failure instanceof AiUnavailableException || failure instanceof AiRefusalException) {
            // 調査の失敗。分かる場合は、失敗した呼び出しの利用量を記録する
            inquiry.addUsage(new SkillUsage(
                    Skill.INVESTIGATOR, failedUsageOf(failure), true, (System.nanoTime() - startNanos) / 1_000_000L),
                    implementations);
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

    /**
     * 調査の結果が置き換わったので、前の結果への案を消す（担当者の番なら、新しい結果への案が作り直される）。
     * 置き換わるのは、説明が失敗して残った結果を、答えを待たずに送った後の裏での調査が置き換える場合。
     */
    private static void discardAdvice(InquiryEntity inquiry) {
        inquiry.adviceStatus = AdviceStatus.NOT_RUN;
        inquiry.advice = null;
    }

    private void store(InquiryEntity inquiry, AnswerPipeline.Result result) {
        discardAdvice(inquiry);
        inquiry.answerGrounded = result.answer().grounded();
        inquiry.answerAnswered = result.answer().answered();
        inquiry.askerExplanation = result.answer().askerExplanation();
        inquiry.answerSteps = new ArrayList<>(result.answer().steps());
        inquiry.technicalMemo = result.answer().memo();
        inquiry.citations = storedCitations(result.investigation().citationResults());
        result.usages().forEach(u -> inquiry.addUsage(u, implementations));
    }

    // ---- 時間切れ ----

    /**
     * 本人が待つ調査が時間切れになったので、担当者に渡す（{@code TIMEOUT}。履歴の起こした人は時間。初めて渡すので、通知が働く）。
     * 調査が走っているか、失敗して止まっているかは問わない。走っている調査は止めない（終わったら、担当者だけに見せる結果になる）。
     * すでに待つ状態でなければ（調査が終わった、本人が先に送った）、何もしない。競合したら、読み直してやり直す。
     * 起動時の処理（API の再起動で調査が失われた件）も、これを使う。
     *
     * @param id 問い合わせの識別子
     * @return 担当者に渡した場合は true
     */
    boolean timeoutHandOver(UUID id) {
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
                return saved != null;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
                }
            }
        }
    }

    /**
     * 起動時に、{@code INVESTIGATION} のまま残っている件を、担当者に渡す（API を止めると、走っていた調査は失われる。本人が戻らなくても、
     * 担当者に見えるようにするため）。
     *
     * @return 担当者に渡した件数
     */
    int handOverWaiting() {
        int handedOver = 0;
        for (UUID id : repository.findIdsByAwaitingIn(List.of(Awaiting.INVESTIGATION))) {
            try {
                if (timeoutHandOver(id)) {
                    handedOver++;
                }
            } catch (RuntimeException e) {
                log.error("起動時の引き継ぎに失敗しました id={} cause={}", id, e.getClass().getSimpleName());
            }
        }
        return handedOver;
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
