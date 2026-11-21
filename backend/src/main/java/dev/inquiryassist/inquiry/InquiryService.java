package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.ZeroAnswer;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.ExplanationFailedException;
import dev.inquiryassist.core.skill.Interview;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.ProgressListener;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import dev.inquiryassist.inquiry.InquiryEntity.StoredCitation;
import dev.inquiryassist.inquiry.InquiryEntity.StoredQuestion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 問い合わせの段階遷移と、種別ごとの経路を実装するアプリケーション層（契約書「段階の遷移」）。
 *
 * <p>AI の呼び出しの間は DB トランザクションを持たない。保存時の競合は楽観ロックで検出し、
 * {@link InvalidStateException} として返す。
 */
@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    /** 作業依頼の固定質問の識別子。 */
    public static final String WR_WHAT = "wr-what";

    public static final String WR_WHEN = "wr-when";
    public static final String WR_TARGET = "wr-target";
    /** 障害の固定質問の識別子。 */
    public static final String INCIDENT_CHECK = "incident-check";

    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_NOTE_LENGTH = 1000;
    private static final String INCIDENT_NOT_STOPPED = "いいえ";

    private static final List<StoredQuestion> WORK_REQUEST_QUESTIONS = List.of(
            new StoredQuestion(WR_WHAT, "何をしてほしいですか", List.of(), null),
            new StoredQuestion(WR_WHEN, "いつまでに必要ですか", List.of(), null),
            new StoredQuestion(WR_TARGET, "対象は何ですか", List.of(), null));
    private static final List<StoredQuestion> INCIDENT_QUESTIONS =
            List.of(new StoredQuestion(INCIDENT_CHECK, "いま業務やサービスが止まっていますか", List.of("はい", "いいえ"), null));

    /**
     * 追加質問への回答1件。
     *
     * @param questionId 質問の識別子
     * @param answer 回答。「わからない」は null
     */
    public record AnswerInput(String questionId, String answer) {}

    private final ReceptionSkill reception;
    private final InterviewerSkill interviewer;
    private final RestaterSkill restater;
    private final AnswerPipeline pipeline;
    private final InquiryRepository repository;
    private final CurrentUserProvider users;
    private final FilingNotifier notifier;
    private final TransactionTemplate transaction;
    private final String incidentGuidance;
    private final SkillImplementations implementations;
    private final InquiryProperties properties;
    private final ApplicationEventPublisher events;
    private final Set<UUID> generating = ConcurrentHashMap.newKeySet();

    /**
     * @param reception 受付
     * @param interviewer 聞き手
     * @param restater 言い直し
     * @param pipeline 調査、照合、説明の流れ
     * @param repository 問い合わせ記録の保存先
     * @param users 現在の投稿者
     * @param notifier 起票の通知先
     * @param transaction 保存時のトランザクション
     * @param incidentGuidance 障害の案内文
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     * @param properties 対象システムの一覧など
     * @param events 起票の確定後にイベントを発行する
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
            @Value("${inquiry.incident-guidance}") String incidentGuidance,
            SkillImplementations implementations,
            InquiryProperties properties,
            ApplicationEventPublisher events) {
        this.reception = reception;
        this.interviewer = interviewer;
        this.restater = restater;
        this.pipeline = pipeline;
        this.repository = repository;
        this.users = users;
        this.notifier = notifier;
        this.transaction = transaction;
        this.incidentGuidance = incidentGuidance;
        this.implementations = implementations;
        this.properties = properties;
        this.events = events;
    }

    /**
     * 種別を判定して追加質問を作り、問い合わせを新規に保存する。
     *
     * @param utterance 本人が書いた元の発話
     * @param systemId 本人が選んだ対象システムの識別子。選ばなければ null（AI が推測する）
     * @return {@code TRIAGED} の問い合わせ
     * @throws IllegalArgumentException 一覧にない systemId の場合
     * @throws TriageFailedException 種別の判定でAIが失敗した場合。元の発話は種別なしで保存済み
     */
    public InquiryView create(String utterance, String systemId) {
        AccessScope scope = users.currentScope();
        InquiryProperties.SystemEntry selected = systemId == null ? null : findSystem(systemId);
        InquiryEntity inquiry = new InquiryEntity(scope.userId(), utterance, Instant.now());
        if (selected != null) {
            inquiry.systemId = selected.id();
            inquiry.systemLabel = selected.label();
        }
        AiResult<Reception> received;
        try {
            received = reception.receive(utterance, candidates(), selected == null ? null : option(selected));
        } catch (AiRefusalException | AiUnavailableException e) {
            // 失敗しても発話は失わない。種別なし・質問なしで保存し、「今すぐ送る」だけを可能にする
            // 失敗した呼び出しの利用量も記録する。分からなければ、受付がモックかどうかだけを記録に残す
            inquiry.mock = implementations.isMock(Skill.RECEPTION);
            inquiry.addUsage(new SkillUsage(Skill.RECEPTION, failedUsageOf(e, Skill.RECEPTION), true));
            commit(inquiry, null);
            throw new TriageFailedException(inquiry.id, e);
        }
        InquiryCategory category = received.value().category();
        if (selected == null && received.value().guessedSystemId() != null) {
            // 一覧にない id を返された場合は推測なしとして扱う
            properties.systems().stream()
                    .filter(s -> s.id().equals(received.value().guessedSystemId()))
                    .findFirst()
                    .ifPresent(s -> {
                        inquiry.systemId = s.id();
                        inquiry.systemLabel = s.label();
                        inquiry.systemGuessed = true;
                    });
        }
        inquiry.categoryByAi = category;
        inquiry.categoryConfirmed = category;
        inquiry.addUsage(Skill.RECEPTION, received.usage());
        if (category.receivesZeroAnswer()) {
            inquiry.aiQuestions = askInterviewer(inquiry, category, scope);
        }
        inquiry.followUps = questionsFor(inquiry, category);
        return toView(commit(inquiry, null));
    }

    /**
     * 本人が対象システムを直す。AI の推測は上書きされ、推測ではなくなる。追加質問は作り直さない。
     *
     * @param id 問い合わせの識別子
     * @param systemId 対象システムの識別子。null で「わからない」に戻す
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code TRIAGED} でない、または種別の判定に失敗した問い合わせの場合
     * @throws IllegalArgumentException 一覧にない systemId の場合
     */
    public InquiryView changeSystem(UUID id, String systemId) {
        InquiryProperties.SystemEntry system = systemId == null ? null : findSystem(systemId);
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.TRIAGED);
        requireTriaged(inquiry);
        inquiry.systemId = system == null ? null : system.id();
        inquiry.systemLabel = system == null ? null : system.label();
        inquiry.systemGuessed = false;
        return toView(commit(inquiry, null));
    }

    /**
     * 本人が種別を変更する。追加質問は変更後の種別に合わせて差し替える。
     * 仕様確認・不具合の疑いに変更し、聞き手の質問がまだなければ、この時点で聞き手を呼ぶ。
     *
     * @param id 問い合わせの識別子
     * @param category 変更後の種別
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code TRIAGED} でない場合
     */
    public InquiryView changeCategory(UUID id, InquiryCategory category) {
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.TRIAGED);
        requireTriaged(inquiry);
        inquiry.categoryConfirmed = category;
        // 作業依頼や障害と判定されて聞き手が呼ばれていない（または失敗した）場合は、この時点で呼ぶ
        if (category.receivesZeroAnswer() && !interviewerAsked(inquiry)) {
            inquiry.aiQuestions = askInterviewer(inquiry, category, users.currentScope());
        }
        inquiry.followUps = questionsFor(inquiry, category);
        return toView(commit(inquiry, null));
    }

    /**
     * 追加質問への回答を受け取り、整形する。障害は固定質問の回答で案内か続行かが決まる。
     *
     * @param id 問い合わせの識別子
     * @param answers 回答。回答のない質問は「わからない」として扱う
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code TRIAGED} でない場合
     * @throws IllegalArgumentException 存在しない質問への回答を含む場合
     */
    public InquiryView answerQuestions(UUID id, List<AnswerInput> answers) {
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.TRIAGED);
        requireTriaged(inquiry);
        Map<String, String> byId = new HashMap<>();
        for (AnswerInput input : answers) {
            if (inquiry.followUps.stream().noneMatch(q -> q.id().equals(input.questionId()))) {
                throw new IllegalArgumentException("存在しない質問です: " + input.questionId());
            }
            byId.put(input.questionId(), input.answer());
        }
        inquiry.followUps = new ArrayList<>(inquiry.followUps.stream()
                .map(q -> new StoredQuestion(q.id(), q.text(), q.options(), byId.get(q.id())))
                .toList());
        inquiry.answersSubmitted = true;

        if (inquiry.category() == InquiryCategory.INCIDENT) {
            String check = byId.get(INCIDENT_CHECK);
            if (check == null || !INCIDENT_NOT_STOPPED.equals(check.trim())) {
                inquiry.step = InquiryStep.CLOSED;
                inquiry.outcome = InquiryOutcome.INCIDENT_REDIRECTED;
                return toView(commit(inquiry, null));
            }
            inquiry.categoryConfirmed = InquiryCategory.SUSPECTED_BUG;
        }
        structure(inquiry);
        return toView(commit(inquiry, null));
    }

    /**
     * 言い直しを確定する。作業依頼はここで起票し、それ以外は0次回答の生成待ちになる。
     *
     * @param id 問い合わせの識別子
     * @param summary 本人が書き直した文章。AI の言い直しをそのまま確定する場合は null
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code STRUCTURED} でない場合
     * @throws IllegalArgumentException summary が空白のみ、または2000文字を超える場合
     */
    public InquiryView confirmStructured(UUID id, String summary) {
        if (summary != null && (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH)) {
            throw new IllegalArgumentException("summary は1〜" + MAX_SUMMARY_LENGTH + "文字");
        }
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.STRUCTURED);
        inquiry.structuredConfirmed =
                summary == null ? inquiry.structuredByAi : inquiry.structuredByAi.withSummary(summary);
        if (inquiry.category() == InquiryCategory.WORK_REQUEST) {
            return toView(commit(inquiry, file(inquiry, false)));
        }
        inquiry.step = InquiryStep.ANSWERING;
        return toView(commit(inquiry, null));
    }

    /**
     * 本人の一言をもとに言い直しを作り直す。AI が失敗した場合は残り回数を減らさず、何も保存しない。
     *
     * @param id 問い合わせの識別子
     * @param note 本人が伝えた、違う点の一言
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code STRUCTURED} でない、または残り回数が0の場合
     * @throws IllegalArgumentException note が空白のみ、または1000文字を超える場合
     */
    public InquiryView revise(UUID id, String note) {
        if (note == null || note.isBlank() || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note は1〜" + MAX_NOTE_LENGTH + "文字");
        }
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.STRUCTURED);
        if (inquiry.revisionsLeft <= 0) {
            throw new InvalidStateException("言い直しを作り直せる回数を使い切りました");
        }
        // 前回の言い直しは渡さず、伝えられた一言の全件（伝えられた順）を足して最初から言い直す
        List<String> corrections = new ArrayList<>(inquiry.revisionNotes);
        corrections.add(note);
        AiResult<StructuredInquiry> result = restater.restate(
                inquiry.utterance, inquiry.category(), answeredQuestions(inquiry), systemOf(inquiry), corrections);
        inquiry.structuredByAi = result.value();
        inquiry.revisionNotes.add(note);
        inquiry.revisionsLeft--;
        inquiry.addUsage(Skill.RESTATER, result.usage());
        return toView(commit(inquiry, null));
    }

    /**
     * 追加質問へ戻る。整形結果は破棄し、質問は同じものを返す。作り直しの残り回数は戻さない。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code STRUCTURED} でない場合
     */
    public InquiryView reopen(UUID id) {
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.STRUCTURED);
        inquiry.structuredByAi = null;
        inquiry.structuredConfirmed = null;
        // 戻った後に答えずに送られた場合、未回答として区別できるようにする
        inquiry.answersSubmitted = false;
        inquiry.followUps = new ArrayList<>(inquiry.followUps.stream()
                .map(q -> new StoredQuestion(q.id(), q.text(), q.options(), null))
                .toList());
        inquiry.step = InquiryStep.TRIAGED;
        return toView(commit(inquiry, null));
    }

    /**
     * 0次回答を生成する。調査、照合、説明の順に進め、照合できた事実だけから本人向けの説明を作る。
     * 不具合の疑いは完了と同時に起票する。同じ問い合わせの生成は同時に1つだけ走らせる。
     *
     * <p>説明だけが失敗した場合は、調査と照合の結果（技術メモ、出典と照合結果、利用量）を保存したうえで
     * 失敗を投げる。段階は {@code ANSWERING} のままで、本人向けの回答は返さない。
     *
     * @param id 問い合わせの識別子
     * @param progress 進捗の通知先
     * @return 完了後の問い合わせ
     * @throws InvalidStateException {@code ANSWERING} でない、または同じ問い合わせの生成が進行中の場合
     * @throws dev.inquiryassist.core.AiRefusalException モデルが回答を拒否した場合
     * @throws dev.inquiryassist.core.AiUnavailableException モデルを呼び出せなかった場合
     */
    public InquiryView generateAnswer(UUID id, ProgressListener progress) {
        if (!generating.add(id)) {
            throw new InvalidStateException("0次回答を生成中です");
        }
        try {
            InquiryEntity inquiry = load(id);
            requireStep(inquiry, InquiryStep.ANSWERING);
            AccessScope scope = users.currentScope();
            AnswerPipeline.Result result;
            try {
                result = pipeline.run(inquiry.structured(), scope, progress);
            } catch (AiUnavailableException | AiRefusalException e) {
                // 調査の失敗。分かる場合は、失敗した呼び出しの利用量を記録する
                inquiry.addUsage(new SkillUsage(Skill.INVESTIGATOR, failedUsageOf(e, Skill.INVESTIGATOR), true));
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

            if (inquiry.category().alwaysFiled()) {
                return toView(commit(inquiry, file(inquiry, false)));
            }
            inquiry.step = InquiryStep.ANSWERED;
            return toView(commit(inquiry, null));
        } finally {
            generating.remove(id);
        }
    }

    /**
     * 本人の解決判定で問い合わせを終える。
     *
     * @param id 問い合わせの識別子
     * @param resolved 解決したか。false なら起票する
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code ANSWERED} でない場合
     */
    public InquiryView resolve(UUID id, boolean resolved) {
        InquiryEntity inquiry = load(id);
        requireStep(inquiry, InquiryStep.ANSWERED);
        if (resolved) {
            inquiry.step = InquiryStep.CLOSED;
            inquiry.outcome = InquiryOutcome.SELF_RESOLVED;
            return toView(commit(inquiry, null));
        }
        return toView(commit(inquiry, file(inquiry, false)));
    }

    /**
     * その時点の内容で、今すぐ担当者に送る。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     * @throws InvalidStateException {@code CLOSED} の場合
     */
    public InquiryView sendNow(UUID id) {
        InquiryEntity inquiry = load(id);
        if (inquiry.step == InquiryStep.CLOSED) {
            throw new InvalidStateException("終了した問い合わせは送れません");
        }
        return toView(commit(inquiry, file(inquiry, true)));
    }

    /**
     * 問い合わせを1件取得する。
     *
     * @param id 問い合わせの識別子
     * @return 問い合わせ
     * @throws InquiryNotFoundException 存在しない場合
     */
    public InquiryView get(UUID id) {
        return toView(load(id));
    }

    private InquiryProperties.SystemEntry findSystem(String systemId) {
        return properties.systems().stream()
                .filter(s -> s.id().equals(systemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("一覧にない対象システムです: " + systemId));
    }

    private List<SystemOption> candidates() {
        return properties.systems().stream().map(InquiryService::option).toList();
    }

    private static SystemOption option(InquiryProperties.SystemEntry entry) {
        return new SystemOption(entry.id(), entry.label());
    }

    private SystemOption systemOf(InquiryEntity inquiry) {
        return inquiry.systemId == null ? null : new SystemOption(inquiry.systemId, inquiry.systemLabel);
    }

    /** 聞き手を呼んだことがあり、成功しているか。 */
    private static boolean interviewerAsked(InquiryEntity inquiry) {
        return inquiry.usages.stream()
                .anyMatch(u -> u.step().equals(Skill.INTERVIEWER.id()) && !u.failed());
    }

    /**
     * 聞き手に追加質問を作らせる。失敗しても本人を止めず、質問なしで続行する。
     * 失敗はログと利用量（失敗した呼び出しとして）に残す。
     */
    private List<StoredQuestion> askInterviewer(InquiryEntity inquiry, InquiryCategory category, AccessScope scope) {
        try {
            AiResult<Interview> result =
                    interviewer.ask(inquiry.utterance, category, systemOf(inquiry), scope);
            inquiry.addUsage(Skill.INTERVIEWER, result.usage());
            return new ArrayList<>(result.value().questions().stream()
                    .map(q -> new StoredQuestion(q.id(), q.text(), q.options(), null))
                    .toList());
        } catch (AiRefusalException | AiUnavailableException e) {
            log.warn("聞き手が失敗したため、質問なしで続行します id={}", inquiry.id, e);
            inquiry.addUsage(new SkillUsage(Skill.INTERVIEWER, failedUsageOf(e, Skill.INTERVIEWER), true));
            return new ArrayList<>();
        }
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

    private static List<AnsweredQuestion> answeredQuestions(InquiryEntity inquiry) {
        return inquiry.followUps.stream()
                .map(q -> new AnsweredQuestion(new FollowUpQuestion(q.id(), q.text(), q.options()), q.answer()))
                .toList();
    }

    private void structure(InquiryEntity inquiry) {
        AiResult<StructuredInquiry> result = restater.restate(
                inquiry.utterance,
                inquiry.category(),
                answeredQuestions(inquiry),
                systemOf(inquiry),
                List.copyOf(inquiry.revisionNotes));
        inquiry.structuredByAi = result.value();
        inquiry.addUsage(Skill.RESTATER, result.usage());
        inquiry.step = InquiryStep.STRUCTURED;
    }

    /** 起票の状態にし、通知の内容を返す。通知は保存と同じトランザクションで {@link #commit} が出す。 */
    private FilingNotifier.FilingNotice file(InquiryEntity inquiry, boolean immediately) {
        inquiry.step = InquiryStep.CLOSED;
        inquiry.outcome = InquiryOutcome.FILED;
        inquiry.sentImmediately = immediately;
        inquiry.filedAt = Instant.now();
        return new FilingNotifier.FilingNotice(
                inquiry.id,
                inquiry.category(),
                inquiry.utterance,
                inquiry.systemLabel,
                inquiry.structured(),
                inquiry.technicalMemo,
                immediately);
    }

    /**
     * 保存する。起票があれば保存の確定前に通知し、通知が失敗したら保存も取り消す。
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
        if (notice != null) {
            // 保存の確定後に配る。配信側の失敗で、確定済みの起票を失敗にしない
            try {
                events.publishEvent(new InquiryFiledEvent(OperatorService.toItem(saved)));
            } catch (RuntimeException e) {
                log.error("起票の通知イベントの配信に失敗 id={}", saved.id, e);
            }
        }
        return saved;
    }

    private InquiryEntity load(UUID id) {
        String userId = users.currentScope().userId();
        return repository
                .findById(id)
                .filter(i -> i.askerId.equals(userId))
                .orElseThrow(() -> new InquiryNotFoundException(id));
    }

    private static void requireStep(InquiryEntity inquiry, InquiryStep expected) {
        if (inquiry.step != expected) {
            throw new InvalidStateException(
                    "この操作は " + expected + " でのみ実行できます（現在: " + inquiry.step + "）");
        }
    }

    /** 種別の判定に失敗した問い合わせ（種別なし）は、種別の変更も回答も受け付けない。 */
    private static void requireTriaged(InquiryEntity inquiry) {
        if (inquiry.categoryConfirmed == null) {
            throw new InvalidStateException("種別の判定に失敗した問い合わせです。今すぐ担当者に送ってください");
        }
    }

    private static List<StoredQuestion> questionsFor(InquiryEntity inquiry, InquiryCategory category) {
        return new ArrayList<>(switch (category) {
            case WORK_REQUEST -> WORK_REQUEST_QUESTIONS;
            case INCIDENT -> INCIDENT_QUESTIONS;
            // AI の質問は AI の判定が仕様確認か不具合のときだけ保存している。それ以外は空になる
            case SPEC_QUESTION, SUSPECTED_BUG -> inquiry.aiQuestions;
        });
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

    /**
     * 本人向けの技術メモから、根拠を確かめられなかったこと（unconfirmed）を落とす。これは担当者にだけ見せる内容で、
     * 画面で隠すのではなく、本人向けの応答に API が含めない。保存は変えず、オペレータ向けの詳細には残る。
     * 本人向けの応答（InquiryView と進捗ストリームの done）はすべてこの view から作るため、ここ1か所で落とす。
     */
    private static TechnicalMemo forAsker(TechnicalMemo memo) {
        return memo == null ? null : new TechnicalMemo(memo.findings(), List.of(), memo.notFound(), memo.nextChecks());
    }

    private InquiryView toView(InquiryEntity e) {
        StructuredInquiry structured = e.structured();
        InquiryView.AnswerView answer = e.answerGrounded == null
                ? null
                : new InquiryView.AnswerView(
                        e.answerGrounded,
                        Boolean.TRUE.equals(e.answerAnswered),
                        e.askerExplanation,
                        forAsker(e.technicalMemo),
                        e.citations.stream()
                                .filter(StoredCitation::verified)
                                .map(c -> new Citation(
                                        c.sourceType(),
                                        c.repository(),
                                        c.path(),
                                        c.startLine(),
                                        c.endLine(),
                                        c.quote(),
                                        c.revision()))
                                .toList());
        return new InquiryView(
                e.id,
                e.step,
                e.outcome,
                e.utterance,
                e.categoryByAi,
                e.categoryConfirmed,
                e.followUps.stream()
                        .map(q -> new InquiryView.QuestionView(q.id(), q.text(), q.options()))
                        .toList(),
                e.revisionsLeft,
                e.systemId == null ? null : new InquiryView.SystemView(e.systemId, e.systemLabel),
                e.systemGuessed,
                structured == null
                        ? null
                        : new InquiryView.StructuredView(
                                structured.summary(),
                                structured.targetSystem(),
                                structured.goal(),
                                structured.whatHappened(),
                                structured.expectedResult(),
                                structured.urgency()),
                answer,
                e.outcome == InquiryOutcome.INCIDENT_REDIRECTED ? incidentGuidance : null,
                e.sentImmediately,
                e.mock);
    }
}
