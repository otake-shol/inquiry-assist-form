package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.ZeroAnswer;
import dev.inquiryassist.core.intake.AnswerKind;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakeEntries;
import dev.inquiryassist.core.intake.IntakeEntry;
import dev.inquiryassist.core.intake.IntakeNext;
import dev.inquiryassist.core.intake.IntakePlanner;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.IntakeReply;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.intake.QuestionMismatchException;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.Transition;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.ExplanationFailedException;
import dev.inquiryassist.core.skill.Interview;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.Prefill;
import dev.inquiryassist.core.skill.ProgressListener;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import dev.inquiryassist.inquiry.InquiryEntity.StoredCitation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 問い合わせの段階遷移と、種類ごとの経路を実装するアプリケーション層（契約書「段階の遷移」「入口の質問」）。
 *
 * <p>入口の質問を決める規則（次に何を聞くか、マスの設定のどれを使うか、戻ったときに何を捨てるか）は、
 * Spring に依存しない {@link IntakePlanner} に置いている。ここは、その結果に従ってスキルを呼び、保存する。
 *
 * <p>各操作の {@code lang} は、見る人の言語（リクエストの言語）。応答の名前と入口の決まった質問をこの言語で返す。
 * 本人が行う状態を変える操作では、問い合わせの本人の言語もこの言語に合わせ直す（その後に AI が書くものがこの言語になる）。
 *
 * <p>AI の呼び出しの間は DB トランザクションを持たない。保存時の競合は楽観ロックで検出し、
 * 他の操作と競合した場合は {@link InvalidStateException} にする。
 */
@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private static final int MAX_TEXT_LENGTH = 4000;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_NOTE_LENGTH = 1000;

    private final ReceptionSkill reception;
    private final InterviewerSkill interviewer;
    private final RestaterSkill restater;
    private final AnswerPipeline pipeline;
    private final InquiryRepository repository;
    private final CurrentUserProvider users;
    private final FilingNotifier notifier;
    private final TransactionTemplate transaction;
    private final SkillImplementations implementations;
    private final InquiryProperties properties;
    private final IntakeLocales locales;
    private final InquiryPresenter presenter;
    private final ApplicationEventPublisher events;
    private final Set<UUID> generating = ConcurrentHashMap.newKeySet();

    /**
     * @param reception 受付
     * @param interviewer 聞き手
     * @param restater 言い直し
     * @param pipeline 調査、照合、説明の流れ
     * @param repository 問い合わせ記録の保存先
     * @param users 現在の投稿者
     * @param notifier 担当者への通知先
     * @param transaction 保存時のトランザクション
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     * @param properties 担当者の表示名など
     * @param locales 言語ごとの、種類、システム、機能の名前とマスの設定、入口の質問を決める処理
     * @param presenter 本人向けの表現を作る
     * @param events 担当者へ送った確定後にイベントを発行する
     */
    public InquiryService(
            ReceptionSkill reception,
            InterviewerSkill interviewer,
            RestaterSkill restater,
            AnswerPipeline pipeline,
            InquiryRepository repository,
            CurrentUserProvider users,
            FilingNotifier notifier,
            TransactionTemplate transaction,
            SkillImplementations implementations,
            InquiryProperties properties,
            IntakeLocales locales,
            InquiryPresenter presenter,
            ApplicationEventPublisher events) {
        this.reception = reception;
        this.interviewer = interviewer;
        this.restater = restater;
        this.pipeline = pipeline;
        this.repository = repository;
        this.users = users;
        this.notifier = notifier;
        this.transaction = transaction;
        this.implementations = implementations;
        this.properties = properties;
        this.locales = locales;
        this.presenter = presenter;
        this.events = events;
    }

    /**
     * 問い合わせを新規に作り、入口の最初の質問を出す。文章があれば、受付が置き場所を置く。
     *
     * @param text 本人が書いた文章。書かなければ null（このとき AI は呼ばない）
     * @param urgency 緊急度。省くと {@code NORMAL}
     * @return 入口の質問に答えている（{@code INTAKE_ANSWER}）問い合わせ。残りの質問がなければ、問いを組み立てて {@code QUESTION_CONFIRMATION}
     * @throws IllegalArgumentException 文章が空白のみ、または4000文字を超える場合
     * @throws ComposeFailedException 質問がなくなり、問いの組み立てで AI が失敗した場合。答えは保存済み
     */
    public InquiryView create(String text, Urgency urgency, String lang) {
        String written = validatedText(text, true);
        AccessScope scope = users.currentScope();
        InquiryEntity inquiry =
                new InquiryEntity(scope.userId(), urgency == null ? Urgency.NORMAL : urgency, Instant.now());
        inquiry.language = lang;
        inquiry.viewerLanguage = lang;
        inquiry.apply(InquiryStateMachine.created(), Instant.now());
        if (written != null) {
            inquiry.texts.add(written);
            place(inquiry);
        }
        return settleAndCompose(inquiry, scope);
    }

    /**
     * 次の質問に答える。答えが最後の質問なら、問いを組み立てる。
     *
     * @param id 問い合わせの識別子
     * @param reply 本人の答え
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 入口の質問に答えている状態でない、または答えた質問がいま聞いている質問と違う場合
     * @throws IllegalArgumentException 選択肢にない値、答え方が不正、書けない質問への「書かずに進む」の場合
     * @throws ComposeFailedException 問いの組み立てで AI が失敗した場合（緊急度が {@code BLOCKED} の場合を除く）。答えは保存済み
     */
    public InquiryView answerIntake(UUID id, IntakeReply reply, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.INTAKE_ANSWER);
        IntakeEntry entry;
        try {
            entry = planner(inquiry).accept(inquiry.snapshot(), reply);
        } catch (QuestionMismatchException e) {
            throw new InvalidStateException(e.getMessage());
        }
        inquiry.intake.add(entry);
        return settleAndCompose(inquiry, users.currentScope());
    }

    /**
     * 答え終えた質問に戻る。指定した質問と、それより後の答えを捨て、それらで決まっていた置き場所も決まっていない状態に戻す。
     * 本人が書いた文章は消さない。
     *
     * @param id 問い合わせの識別子
     * @param questionId 戻る質問の識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 入口の質問に答えている状態でない場合
     * @throws IllegalArgumentException 答え終えた質問にない識別子の場合
     */
    public InquiryView back(UUID id, String questionId, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.INTAKE_ANSWER);
        applyBack(inquiry, questionId);
        settle(inquiry, users.currentScope());
        return presenter.toView(commit(inquiry, null));
    }

    /**
     * 文章で書く道に切り替える。まだ答えていない置き場所を、受付が文章から置く。
     *
     * @param id 問い合わせの識別子
     * @param text 本人が書いた文章
     * @return 更新後の問い合わせ。残りの質問がなければ、問いを組み立てて {@code QUESTION_CONFIRMATION}
     * @throws InvalidStateException 入口の質問に答えている状態でない場合
     * @throws IllegalArgumentException 文章が空白のみ、または4000文字を超える場合
     * @throws ComposeFailedException 質問がなくなり、問いの組み立てで AI が失敗した場合
     */
    public InquiryView writeText(UUID id, String text, String lang) {
        String written = validatedText(text, false);
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.INTAKE_ANSWER);
        inquiry.texts.add(written);
        // 新しい文章は、まだ答えていない設定の質問について、改めて読み取る
        inquiry.prefillAttempted.clear();
        place(inquiry);
        return settleAndCompose(inquiry, users.currentScope());
    }

    /**
     * 本人が緊急度を変える。入口の途中で {@code BLOCKED} にすると、まだ答えていない詳しい質問は聞かない。
     * 言い直しの確認中に {@code BLOCKED} にすると、その時点の言い直しで担当者に送る。
     *
     * @param id 問い合わせの識別子
     * @param urgency 変更後の緊急度
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code INTAKE} か 言い直しを確かめている状態でない場合
     * @throws ComposeFailedException 入口を終えて問いを組み立てる場合に AI が失敗したとき
     */
    public InquiryView changeUrgency(UUID id, Urgency urgency, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        AccessScope scope = users.currentScope();
        InquiryStateMachine.require(inquiry.state(), Awaiting.INTAKE_ANSWER, Awaiting.QUESTION_CONFIRMATION);
        if (inquiry.awaiting == Awaiting.QUESTION_CONFIRMATION) {
            inquiry.urgency = urgency;
            if (urgency == Urgency.BLOCKED) {
                return presenter.toView(commit(inquiry, handOver(inquiry, true)));
            }
            return presenter.toView(commit(inquiry, null));
        }
        // 組み立てに失敗して止まっている問い合わせは、緊急度を変えただけで組み立て直さない（compose で明示的にやり直す）。
        // ただし BLOCKED なら、言い直しを待たずに担当者へ送る
        boolean wasComplete = planner(inquiry).next(inquiry.snapshot()) instanceof IntakeNext.Complete;
        inquiry.urgency = urgency;
        IntakeNext next = settle(inquiry, scope);
        if (next instanceof IntakeNext.Complete && (!wasComplete || urgency == Urgency.BLOCKED)) {
            return compose(inquiry);
        }
        return presenter.toView(commit(inquiry, null));
    }

    /**
     * 入口を終えたのに組み立てに失敗した問い合わせで、問いの組み立てだけをやり直す。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 入口の質問に答えている状態でない、または入口が終わっていない場合
     * @throws ComposeFailedException 組み立てで AI が再び失敗した場合
     */
    public InquiryView compose(UUID id, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.INTAKE_ANSWER);
        if (!(planner(inquiry).next(inquiry.snapshot()) instanceof IntakeNext.Complete)) {
            throw new InvalidStateException("入口の質問がまだ残っています");
        }
        return compose(inquiry);
    }

    /**
     * 言い直しを確定する。「変えてほしい」（{@code CHANGE_REQUEST}）はここで担当者に送り、それ以外は0次回答の生成待ちになる。
     *
     * @param id 問い合わせの識別子
     * @param summary 本人が書き直した文章。AI の言い直しをそのまま確定する場合は null
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 言い直しを確かめている状態でない場合
     * @throws IllegalArgumentException summary が空白のみ、または2000文字を超える場合
     */
    public InquiryView confirmStructured(UUID id, String summary, String lang) {
        if (summary != null && (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH)) {
            throw new IllegalArgumentException("summary は1〜" + MAX_SUMMARY_LENGTH + "文字");
        }
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.QUESTION_CONFIRMATION);
        inquiry.structuredConfirmed =
                summary == null ? inquiry.structuredByAi : inquiry.structuredByAi.withSummary(summary);
        return presenter.toView(commit(
                inquiry, apply(inquiry, InquiryStateMachine.confirmed(inquiry.state(), inquiry.context(null)), false)));
    }

    /**
     * 本人の一言をもとに言い直しを作り直す。AI が失敗した場合は残り回数を減らさず、何も保存しない。
     *
     * @param id 問い合わせの識別子
     * @param note 本人が伝えた、違う点の一言
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 言い直しを確かめている状態でない、または残り回数が0の場合
     * @throws IllegalArgumentException note が空白のみ、または1000文字を超える場合
     */
    public InquiryView revise(UUID id, String note, String lang) {
        if (note == null || note.isBlank() || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note は1〜" + MAX_NOTE_LENGTH + "文字");
        }
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.QUESTION_CONFIRMATION);
        if (inquiry.revisionsLeft <= 0) {
            throw new InvalidStateException("言い直しを作り直せる回数を使い切りました");
        }
        // 前回の言い直しは渡さず、伝えられた一言の全件（伝えられた順）を足して最初から言い直す
        List<String> corrections = new ArrayList<>(inquiry.revisionNotes);
        corrections.add(note);
        long start = System.nanoTime();
        AiResult<StructuredInquiry> result = restate(inquiry, corrections);
        inquiry.structuredByAi = result.value();
        inquiry.revisionNotes.add(note);
        inquiry.revisionsLeft--;
        inquiry.addUsage(Skill.RESTATER, result.usage(), millisSince(start));
        return presenter.toView(commit(inquiry, null));
    }

    /**
     * 入口の質問へ戻る。整形結果は破棄し、最後に答えた質問を次の質問に戻してその答えを捨てる（{@link #back} で最後の質問を
     * 指定したのと同じ）。作り直しの残り回数は戻さない。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 言い直しを確かめている状態でない場合
     */
    public InquiryView reopen(UUID id, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        requireAwaiting(inquiry, Awaiting.QUESTION_CONFIRMATION);
        inquiry.structuredByAi = null;
        inquiry.structuredConfirmed = null;
        inquiry.apply(InquiryStateMachine.reopenedIntake(inquiry.state()), Instant.now());
        if (!inquiry.intake.isEmpty()) {
            applyBack(inquiry, inquiry.intake.get(inquiry.intake.size() - 1).questionId());
        }
        settle(inquiry, users.currentScope());
        return presenter.toView(commit(inquiry, null));
    }

    /**
     * 0次回答を生成する。調査、照合、説明の順に進め、照合できた事実だけから本人向けの説明を作る。
     * 種類ごとの経路（{@link InquiryType#filesAfterAnswer}）に従い、担当者へ送るか、本人が結果を確かめる段階に進める。
     * 同じ問い合わせの生成は同時に1つだけ走らせる。
     *
     * <p>説明だけが失敗した場合は、調査と照合の結果（技術メモ、出典と照合結果、利用量）を保存したうえで
     * 失敗を投げる。待っていることは {@code INVESTIGATION} のままで、本人向けの回答は返さない。
     *
     * @param id 問い合わせの識別子
     * @param progress 進捗の通知先
     * @return 完了後の問い合わせ
     * @throws InvalidStateException 調査を待っている状態でない、または同じ問い合わせの生成が進行中の場合
     * @throws dev.inquiryassist.core.AiRefusalException モデルが回答を拒否した場合
     * @throws dev.inquiryassist.core.AiUnavailableException モデルを呼び出せなかった場合
     */
    public InquiryView generateAnswer(UUID id, ProgressListener progress, String lang) {
        if (!generating.add(id)) {
            throw new InvalidStateException("0次回答を生成中です");
        }
        try {
            InquiryEntity inquiry = load(id, lang);
            requireAwaiting(inquiry, Awaiting.INVESTIGATION);
            AccessScope scope = users.currentScope();
            AnswerPipeline.Result result;
            long start = System.nanoTime();
            try {
                result = pipeline.run(inquiry.structured(), scope, progress, inquiry.language);
            } catch (AiUnavailableException | AiRefusalException e) {
                // 調査の失敗。分かる場合は、失敗した呼び出しの利用量を記録する
                inquiry.addUsage(new SkillUsage(
                        Skill.INVESTIGATOR, failedUsageOf(e, Skill.INVESTIGATOR), true, millisSince(start)));
                commit(inquiry, null);
                throw e;
            } catch (ExplanationFailedException e) {
                // 費用をかけた調査の結果は失わない。本人向けの回答は作らない（answerGrounded は null のまま）
                inquiry.answerGrounded = null;
                inquiry.answerAnswered = null;
                inquiry.askerExplanation = null;
                inquiry.technicalMemo = AnswerPipeline.memoOf(e.investigation());
                inquiry.citations = storedCitations(e.investigation().citationResults());
                e.usages().forEach(inquiry::addUsage);
                commit(inquiry, null);
                throw (RuntimeException) e.getCause();
            }
            ZeroAnswer answer = result.answer();
            inquiry.answerGrounded = answer.grounded();
            inquiry.answerAnswered = answer.answered();
            inquiry.askerExplanation = answer.askerExplanation();
            inquiry.technicalMemo = answer.memo();
            inquiry.citations = storedCitations(result.investigation().citationResults());
            result.usages().forEach(inquiry::addUsage);

            // 種類が決まっていない場合は HOW_IT_WORKS と同じ経路（状態の遷移が決める）
            Transition done = InquiryStateMachine.answeredByAi(
                    inquiry.state(), inquiry.context(null), answer.grounded(), answer.answered());
            return presenter.toView(commit(inquiry, apply(inquiry, done, false)));
        } finally {
            generating.remove(id);
        }
    }

    /**
     * 本人が結果（AI の答え、または担当者が伝えた結果）を確かめる。解決したなら終わり、解決していなければ担当者に渡す。
     *
     * @param id 問い合わせの識別子
     * @param resolved 解決したか
     * @param note まだ解決していない点（任意）。解決していない場合だけ指定でき、本人の追記としてメッセージに加える
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 結果を確かめている状態でない場合
     * @throws IllegalArgumentException note が空白のみ、4000文字を超える、または解決したのに指定された場合
     */
    public InquiryView resolve(UUID id, boolean resolved, String note, String lang) {
        if (note != null && resolved) {
            throw new IllegalArgumentException("note は、解決していない場合だけ指定できます");
        }
        String trimmed = note == null ? null : MessageBodies.validated(note);
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        Transition transition = InquiryStateMachine.resolved(inquiry.state(), inquiry.context(null), resolved);
        if (trimmed != null) {
            inquiry.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, properties.dev().userName(), trimmed, Instant.now());
        }
        return presenter.toView(commit(inquiry, apply(inquiry, transition, false)));
    }

    /**
     * 答えを待たずに担当者に送る。問いを確かめた後に限る（{@link InquiryStateMachine#canSendNow}）。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException いま送れない場合
     */
    public InquiryView sendNow(UUID id, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        IntakeNext next = inquiry.awaiting == Awaiting.INTAKE_ANSWER ? planner(inquiry).next(inquiry.snapshot()) : null;
        Transition transition = InquiryStateMachine.sentNow(inquiry.state(), inquiry.context(next));
        return presenter.toView(commit(inquiry, apply(inquiry, transition, true)));
    }

    /**
     * 本人が取り下げる。終わっていない問い合わせで、どの時点でも有効。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException 終わっている場合
     */
    public InquiryView withdraw(UUID id, String lang) {
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        Transition transition = InquiryStateMachine.withdrawn(inquiry.state());
        return presenter.toView(commit(inquiry, apply(inquiry, transition, false)));
    }

    /**
     * 問い合わせを1件取得する。担当者の返信が未読なら、読んだことにする。
     *
     * @param id 問い合わせの識別子
     * @return 問い合わせ
     * @throws InquiryNotFoundException 存在しない、または他人の問い合わせの場合
     */
    public InquiryView get(UUID id, String lang) {
        InquiryEntity inquiry = load(id, lang);
        if (inquiry.askerUnreadReply) {
            repository.clearUnreadReply(id);
            // 返す表現では、読んだことになっている
            inquiry.askerUnreadReply = false;
        }
        return presenter.toView(inquiry);
    }

    /**
     * 自分（現在の利用者）が作った問い合わせを、新しい順に最大50件返す。
     *
     * @return 一覧と、未読の返信がある件数
     */
    public InquiryList list(String lang) {
        String userId = users.currentScope().userId();
        return new InquiryList(
                repository.countByAskerIdAndAskerUnreadReplyTrue(userId),
                repository.findTop50ByAskerIdOrderByCreatedAtDesc(userId).stream()
                        .map(e -> new InquiryList.Item(
                                e.id,
                                e.createdAt,
                                e.language,
                                presenter.summaryOf(e, lang),
                                e.urgency,
                                e.phase,
                                e.turn,
                                e.awaiting,
                                e.ending,
                                e.handedOverAt,
                                e.askerUnreadReply,
                                e.messages.size()))
                        .toList());
    }

    /**
     * 担当者に渡した後の問い合わせに、本人が追記する。追記したときの状態で、その後が決まる
     * （担当者の番なら番は変わらず、聞き返しへの返事なら担当者の番に、結果への追記なら担当者の番に、終わった件なら開き直す）。
     * 担当者に渡したことがなく回答済みで終わった件（AI の答えに返事がなかった件）への追記は、開き直して担当者に初めて渡す。
     * 担当者の側は未読に戻る。
     *
     * @param id 問い合わせの識別子
     * @param body 本文。前後の空白を除いて1〜4000文字
     * @return 追記後の問い合わせ
     * @throws IllegalArgumentException 本文が空、または長すぎる場合
     * @throws InquiryNotFoundException 存在しない、または他人の問い合わせの場合
     * @throws InvalidStateException 担当者に渡したことがなく回答済みでもない、または取り下げた場合
     */
    public InquiryView addMessage(UUID id, String body, String lang) {
        String trimmed = MessageBodies.validated(body);
        InquiryEntity inquiry = load(id, lang);
        inquiry.language = lang;
        Transition transition = InquiryStateMachine.noted(inquiry.state(), inquiry.context(null));
        inquiry.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, properties.dev().userName(), trimmed, Instant.now());
        // 担当者の番の間の追記は番が変わらないので、遷移では未読に戻らない。どの場合も、担当者が見落とさないよう戻す
        inquiry.readAt = null;
        return presenter.toView(commit(inquiry, apply(inquiry, transition, false)));
    }

    /**
     * 入口の質問を進める。聞き手が必要なら呼び、次にすることを返す。聞き手は、質問を作ったら（0問でも、失敗でも）
     * 結果を保存するので、同じ質問のために何度も呼ばない。
     */
    private IntakeNext settle(InquiryEntity inquiry, AccessScope scope) {
        IntakeNext next = planner(inquiry).next(inquiry.snapshot());
        if (next instanceof IntakeNext.NeedsPrefill prefill) {
            // 読み取りは質問ごとに1回で、結果にかかわらず済みにする。読み取った後は、設定の質問か補足に進む
            prefill(inquiry, prefill.questions());
            next = planner(inquiry).next(inquiry.snapshot());
        }
        if (next instanceof IntakeNext.NeedsInterviewer) {
            inquiry.detailQuestions = askInterviewer(inquiry, scope);
            next = planner(inquiry).next(inquiry.snapshot());
        }
        return next;
    }

    /** 入口を進め、終わっていれば問いを組み立てる。終わっていなければ、次の質問を保存して返す。 */
    private InquiryView settleAndCompose(InquiryEntity inquiry, AccessScope scope) {
        if (settle(inquiry, scope) instanceof IntakeNext.Complete) {
            return compose(inquiry);
        }
        return presenter.toView(commit(inquiry, null));
    }

    /**
     * 入口の答えから、言い直しのスキルで問いを組み立てる。
     *
     * <p>緊急度が {@code BLOCKED} なら、組み立てた問いで（組み立てに失敗しても組み立てなしで）担当者へ送る。
     * 業務が止まっている本人を、AI の失敗で止めないため。それ以外で失敗した場合は、答えを保存したうえで
     * {@link ComposeFailedException} を投げる。
     */
    private InquiryView compose(InquiryEntity inquiry) {
        long start = System.nanoTime();
        AiResult<StructuredInquiry> result;
        try {
            result = restate(inquiry, List.copyOf(inquiry.revisionNotes));
        } catch (AiRefusalException | AiUnavailableException e) {
            log.warn("言い直しが失敗しました id={}", inquiry.id, e);
            inquiry.addUsage(new SkillUsage(Skill.RESTATER, failedUsageOf(e, Skill.RESTATER), true, millisSince(start)));
            if (inquiry.urgency == Urgency.BLOCKED) {
                return presenter.toView(commit(inquiry, handOver(inquiry, true)));
            }
            commit(inquiry, null);
            throw new ComposeFailedException(inquiry.id, e);
        }
        inquiry.structuredByAi = result.value();
        inquiry.addUsage(Skill.RESTATER, result.usage(), millisSince(start));
        if (inquiry.urgency == Urgency.BLOCKED) {
            return presenter.toView(commit(inquiry, handOver(inquiry, true)));
        }
        inquiry.apply(InquiryStateMachine.composed(inquiry.state()), Instant.now());
        return presenter.toView(commit(inquiry, null));
    }

    private AiResult<StructuredInquiry> restate(InquiryEntity inquiry, List<String> corrections) {
        Placement placement = inquiry.placement();
        return restater.restate(
                inquiry.utterance(),
                placement.type(),
                systemOf(inquiry, placement),
                functionOf(inquiry, placement),
                answeredQuestions(inquiry),
                corrections,
                inquiry.language);
    }

    /**
     * 本人が書いた文章から、受付に置き場所を置かせる。決まっていない項目が残っているときだけ呼ぶ。
     * 受付が失敗しても、エラーにしない（何も置かず、質問として聞く）。失敗はログと利用量に残す。
     */
    private void place(InquiryEntity inquiry) {
        if (!planner(inquiry).needsPlacement(inquiry.intake)) {
            return;
        }
        long start = System.nanoTime();
        try {
            AiResult<Reception> result = reception.receive(
                    inquiry.writtenText(), catalog(inquiry).candidates(), IntakeEntries.chosen(inquiry.intake));
            inquiry.addUsage(Skill.RECEPTION, result.usage(), millisSince(start));
            IntakePlanner.Placed placed = planner(inquiry).place(inquiry.intake, result.value());
            inquiry.intake = new ArrayList<>(placed.entries());
            if (placed.detailsReset()) {
                inquiry.detailQuestions = null;
                inquiry.prefillAttempted.clear();
            }
        } catch (AiRefusalException | AiUnavailableException e) {
            log.warn("受付が失敗したため、置き場所を質問として聞きます id={}", inquiry.id, e);
            inquiry.addUsage(new SkillUsage(Skill.RECEPTION, failedUsageOf(e, Skill.RECEPTION), true, millisSince(start)));
        }
    }

    private void applyBack(InquiryEntity inquiry, String questionId) {
        IntakePlanner.Back back = planner(inquiry).back(inquiry.intake, questionId);
        List<IntakeEntry> removed = inquiry.intake.subList(back.entries().size(), inquiry.intake.size());
        inquiry.intake = new ArrayList<>(back.entries());
        if (back.detailsReset()) {
            // 置き場所が変わりうるので、前の置き場所のために作った質問を残さない。読み取りも、置き場所が決まってからやり直す
            inquiry.detailQuestions = null;
            inquiry.prefillAttempted.clear();
        } else {
            // 戻った質問は本人が答えるまで聞く。同じ文章から読み取り直して消さないよう、読み取り済みにしておく
            removed.stream()
                    .filter(e -> e.kind() == QuestionKind.DETAIL)
                    .map(IntakeEntry::questionId)
                    .filter(id -> !inquiry.prefillAttempted.contains(id))
                    .forEach(inquiry.prefillAttempted::add);
        }
    }

    /**
     * 聞き手に、設定の質問の答えが本人の文章に書かれているかを読み取らせ、確かめて採れたものを答え終えた質問にする。
     * 失敗しても本人を止めず、設定の質問をそのまま聞く。失敗はログと利用量（失敗した呼び出しとして）に残す。
     */
    private void prefill(InquiryEntity inquiry, List<IntakeQuestion> questions) {
        long start = System.nanoTime();
        try {
            AiResult<Prefill> result = interviewer.prefill(inquiry.writtenText(), questions);
            inquiry.addUsage(Skill.INTERVIEWER, result.usage(), millisSince(start));
            inquiry.intake.addAll(planner(inquiry).acceptPrefill(inquiry.intake, questions, inquiry.writtenText(), result.value()));
        } catch (AiRefusalException | AiUnavailableException e) {
            log.warn("答えの読み取りが失敗したため、設定の質問をすべて聞きます id={}", inquiry.id, e);
            inquiry.addUsage(new SkillUsage(Skill.INTERVIEWER, failedUsageOf(e, Skill.INTERVIEWER), true, millisSince(start)));
        }
        questions.stream().map(IntakeQuestion::id).forEach(inquiry.prefillAttempted::add);
    }

    /**
     * 聞き手に詳しい質問を作らせる。失敗しても本人を止めず、質問なしで続行する。
     * 失敗はログと利用量（失敗した呼び出しとして）に残す。
     */
    private List<IntakeQuestion> askInterviewer(InquiryEntity inquiry, AccessScope scope) {
        long start = System.nanoTime();
        Placement placement = inquiry.placement();
        try {
            AiResult<Interview> result = interviewer.ask(
                    inquiry.writtenText(),
                    placement.type(),
                    systemOf(inquiry, placement),
                    functionOf(inquiry, placement),
                    scope,
                    inquiry.language);
            inquiry.addUsage(Skill.INTERVIEWER, result.usage(), millisSince(start));
            return new ArrayList<>(
                    result.value().questions().stream().map(IntakeQuestion::detailOf).toList());
        } catch (AiRefusalException | AiUnavailableException e) {
            log.warn("聞き手が失敗したため、詳しい質問なしで続行します id={}", inquiry.id, e);
            inquiry.addUsage(new SkillUsage(Skill.INTERVIEWER, failedUsageOf(e, Skill.INTERVIEWER), true, millisSince(start)));
            return new ArrayList<>();
        }
    }

    /** スキルに渡すシステムは、本人の言語の名前にする。言い直しの {@code targetSystem} にもこの名前が入る。 */
    private SystemOption systemOf(InquiryEntity inquiry, Placement placement) {
        return placement.systemId() == null
                ? null
                : new SystemOption(placement.systemId(), catalog(inquiry).systemLabel(placement.systemId()));
    }

    private FunctionOption functionOf(InquiryEntity inquiry, Placement placement) {
        return placement.functionId() == null
                ? null
                : new FunctionOption(
                        placement.functionId(),
                        catalog(inquiry).functionLabel(placement.systemId(), placement.functionId()));
    }

    private IntakePlanner planner(InquiryEntity inquiry) {
        return locales.planner(inquiry.language);
    }

    private IntakeCatalog catalog(InquiryEntity inquiry) {
        return locales.catalog(inquiry.language);
    }

    /** 担当者への通知（Slack など）は、既定の言語で書く。 */
    private IntakeCatalog defaultCatalog() {
        return locales.catalog(locales.defaultLanguage());
    }

    /**
     * 文章を検証して、前後の空白を除いて返す。
     *
     * @param optional true なら、文章がないこと（null）を許す
     */
    private static String validatedText(String text, boolean optional) {
        if (text == null && optional) {
            return null;
        }
        if (text == null || text.isBlank() || text.strip().length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("文章は1〜" + MAX_TEXT_LENGTH + "文字");
        }
        return text.strip();
    }

    /** 単調増加の時計で、開始からの経過をミリ秒で返す。壁時計は調整で戻ることがあり、負の時間を記録しないため。 */
    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 失敗が利用量を持っていればそれを使う。なければ、モデルが分からない利用量を、そのスキルの実装に合わせて作る。 */
    private Usage failedUsageOf(RuntimeException failure, Skill skill) {
        Usage usage = failure instanceof AiUnavailableException unavailable
                ? unavailable.usage()
                : failure instanceof AiRefusalException refusal ? refusal.usage() : null;
        return usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, implementations.isMock(skill));
    }

    private static List<StoredCitation> storedCitations(List<CitationVerifier.Result> results) {
        return new ArrayList<>(results.stream().map(InquiryService::toStored).toList());
    }

    /** 詳しい質問と答えを、言い直しに渡す形にする。「わからない」は答えを null にする。 */
    private static List<AnsweredQuestion> answeredQuestions(InquiryEntity inquiry) {
        return inquiry.intake.stream()
                .filter(e -> e.kind() == QuestionKind.DETAIL)
                .map(e -> new AnsweredQuestion(
                        new FollowUpQuestion(e.questionId(), e.text(), List.of()), answerText(e)))
                .toList();
    }

    private static String answerText(IntakeEntry entry) {
        return switch (entry.answer().kind()) {
            case OPTION -> entry.answer().label();
            case INFERRED -> entry.answer().label() != null ? entry.answer().label() : entry.answer().text();
            case TEXT -> entry.answer().text();
            case UNKNOWN, SKIPPED -> null;
        };
    }

    /** 業務が止まっている、または答えを待たずに送る場合に、担当者に渡す（入口の途中か、言い直しの確認中から）。 */
    private FilingNotifier.FilingNotice handOver(InquiryEntity inquiry, boolean immediately) {
        Transition transition = InquiryStateMachine.handedOverBecauseBlocked(inquiry.state(), inquiry.context(null));
        return apply(inquiry, transition, immediately);
    }

    /**
     * 遷移を反映する。担当者に初めて渡す遷移なら、通知の内容を返す（通知は保存と同じトランザクションで {@link #commit} が出す）。
     * それ以外は null。
     */
    private FilingNotifier.FilingNotice apply(InquiryEntity inquiry, Transition transition, boolean immediately) {
        boolean first = inquiry.handedOverAt == null && transition.handsOver();
        inquiry.apply(transition, Instant.now());
        if (!first) {
            return null;
        }
        inquiry.sentImmediately = immediately;
        Placement placement = inquiry.placement();
        return new FilingNotifier.FilingNotice(
                inquiry.id,
                placement.type(),
                inquiry.urgency,
                inquiry.utterance(),
                placement.systemId() == null ? null : defaultCatalog().systemLabel(placement.systemId()),
                placement.functionId() == null
                        ? null
                        : defaultCatalog().functionLabel(placement.systemId(), placement.functionId()),
                inquiry.structured(),
                inquiry.technicalMemo,
                immediately);
    }

    /**
     * 保存する。担当者に初めて渡す場合は保存の確定前に通知し、通知が失敗したら保存も取り消す。
     * すでに担当者に渡したことのある問い合わせの変更は、オペレータの画面へ更新を知らせる。
     * 他の操作との競合は {@link InvalidStateException} にする。
     */
    private InquiryEntity commit(InquiryEntity inquiry, FilingNotifier.FilingNotice notice) {
        inquiry.updatedAt = Instant.now();
        InquiryEntity saved;
        try {
            saved = transaction.execute(status -> {
                InquiryEntity s = repository.saveAndFlush(inquiry);
                if (notice != null) {
                    notifier.notifyFiled(notice);
                }
                return s;
            });
        } catch (OptimisticLockingFailureException e) {
            throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
        }
        // 保存し直した記録は別のインスタンスになりうるため、見る人の言語を引き継ぐ
        saved.viewerLanguage = inquiry.viewerLanguage;
        if (notice != null) {
            // 保存の確定後に配る。配信側の失敗で、確定済みの更新を失敗にしない
            try {
                events.publishEvent(presenter.filedEvent(saved));
            } catch (RuntimeException e) {
                log.error("担当者への通知イベントの配信に失敗 id={}", saved.id, e);
            }
        } else if (saved.handedOverAt != null) {
            publishUpdated(saved);
        }
        return saved;
    }

    /** 保存の確定後に、オペレータの画面へ更新を知らせる。配信側の失敗で、確定済みの更新を失敗にしない。 */
    private void publishUpdated(InquiryEntity saved) {
        try {
            events.publishEvent(presenter.updatedEvent(saved));
        } catch (RuntimeException e) {
            log.error("更新の通知イベントの配信に失敗 id={}", saved.id, e);
        }
    }

    private InquiryEntity load(UUID id, String lang) {
        String userId = users.currentScope().userId();
        InquiryEntity inquiry = repository
                .findById(id)
                .filter(i -> i.askerId.equals(userId))
                .orElseThrow(() -> new InquiryNotFoundException(id));
        inquiry.viewerLanguage = lang;
        return inquiry;
    }

    private static void requireAwaiting(InquiryEntity inquiry, Awaiting expected) {
        InquiryStateMachine.require(inquiry.state(), expected);
    }

    private static StoredCitation toStored(CitationVerifier.Result result) {
        Citation c = result.citation();
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
