package dev.inquiryassist.mock;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.AnsweredQuestion;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryAssistant;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.TriageResult;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.ZeroAnswer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AWS に接続しないモック実装。発話のキーワードとタグでシナリオを選ぶ（契約書「モックのシナリオ」）。
 *
 * <p>種別はキーワードで決まる。振る舞いは {@code [質問なし]} などの角括弧のタグで切り替える。
 * 出典は {@code mock-knowledge/} の実在する行を指す。
 */
@Component
@Profile("mock")
public class MockInquiryAssistant implements InquiryAssistant {

    /** 追加質問を出さない。 */
    static final String TAG_NO_QUESTIONS = "[質問なし]";
    /** 0次回答が根拠なしになる。 */
    static final String TAG_NO_GROUNDS = "[根拠なし]";
    /** 実在しない出典だけを返す。 */
    static final String TAG_GHOST_CITATION = "[幽霊出典]";
    /** 進捗の各段階で待つ。 */
    static final String TAG_SLOW = "[遅延]";
    /** 0次回答で AI_UNAVAILABLE になる。 */
    static final String TAG_ERROR = "[エラー]";
    /** 0次回答で AI_REFUSED になる。 */
    static final String TAG_REFUSED = "[拒否]";
    /** 種別の判定で AI_UNAVAILABLE になる。 */
    static final String TAG_TRIAGE_ERROR = "[判定エラー]";

    private static final List<String> INCIDENT_WORDS = List.of("障害", "止まって", "落ちて", "つながらない");
    private static final List<String> WORK_REQUEST_WORDS = List.of("依頼", "してほしい", "お願いします", "してください");
    private static final List<String> BUG_WORDS = List.of("不具合", "バグ", "おかしい");

    private static final String CODE_REPOSITORY = "job-board";
    private static final String CODE_PATH = "src/main/java/com/example/jobboard/JobPostingService.java";
    private static final String FAQ_PATH = "posting-expiry.md";

    private final Duration slowStepDelay;

    /**
     * @param slowStepDelay {@code [遅延]} のときに進捗の各段階で待つ時間
     */
    public MockInquiryAssistant(@Value("${inquiry.mock.slow-step-delay:2s}") Duration slowStepDelay) {
        this.slowStepDelay = slowStepDelay;
    }

    /**
     * 発話のキーワードから種別を判定する。{@code [質問なし]} がなければ AI の質問を2問つける。
     *
     * @param utterance 本人が書いた元の発話
     * @return 種別と追加質問。作業依頼と障害は固定質問を呼び出し側が持つため質問なし
     */
    @Override
    public AiResult<TriageResult> triage(
            String utterance, List<SystemOption> candidates, SystemOption selected) {
        if (utterance.contains(TAG_TRIAGE_ERROR)) {
            throw new AiUnavailableException("モックの種別判定エラー", null);
        }
        InquiryCategory category = classify(utterance);
        boolean askable = category == InquiryCategory.SPEC_QUESTION || category == InquiryCategory.SUSPECTED_BUG;
        // 本人が選んでいれば推測しない。選んでいなければ、発話に候補の名前が含まれるものを推測とする
        SystemOption guessed = selected != null
                ? null
                : candidates.stream().filter(c -> utterance.contains(c.label())).findFirst().orElse(null);
        boolean systemKnown = selected != null || guessed != null;
        List<FollowUpQuestion> questions = new ArrayList<>();
        if (askable && !utterance.contains(TAG_NO_QUESTIONS)) {
            if (!systemKnown) {
                questions.add(new FollowUpQuestion("q1", "どの画面での話ですか", List.of("求人管理画面", "応募者向けの一覧", "その他")));
            }
            questions.add(new FollowUpQuestion("q2", "求人の状態はどれですか", List.of("掲載中", "下書き", "掲載終了")));
        }
        return new AiResult<>(
                new TriageResult(category, questions, guessed == null ? null : guessed.id()), Usage.ofMock());
    }

    /**
     * 発話をそのまま {@code goal} に入れて整形する。
     *
     * @param utterance 本人が書いた元の発話
     * @param category 本人が確認した種別
     * @param answers 追加質問と回答
     * @return 整形後の問い
     */
    @Override
    public AiResult<StructuredInquiry> structure(
            String utterance, InquiryCategory category, List<AnsweredQuestion> answers, SystemOption system) {
        return new AiResult<>(
                new StructuredInquiry(
                        summarize(utterance, category, answers),
                        category,
                        system == null ? null : system.label(),
                        utterance,
                        null,
                        null,
                        StructuredInquiry.Urgency.NORMAL),
                Usage.ofMock());
    }

    /**
     * 言い直しの文章に「補足: （一言）」を足す。{@code goal} は変えない。
     *
     * @param utterance 本人が書いた元の発話
     * @param current 直前の整形後の問い
     * @param note 本人が伝えた一言
     * @return 作り直した整形後の問い
     */
    @Override
    public AiResult<StructuredInquiry> revise(String utterance, StructuredInquiry current, String note) {
        return new AiResult<>(current.withSummary(current.summary() + " 補足: " + note.strip()), Usage.ofMock());
    }

    /** 契約書「モックの言い直し」の規則で、言い直しの文章を作る。 */
    private static String summarize(String utterance, InquiryCategory category, List<AnsweredQuestion> answers) {
        String text = stripTags(utterance);
        if (text.isEmpty()) {
            text = utterance.strip();
        }
        if (category == InquiryCategory.WORK_REQUEST) {
            String what = answerOf(answers, "wr-what");
            String when = answerOf(answers, "wr-when");
            String target = answerOf(answers, "wr-target");
            // 「何を」が分からないときは、書いてもらった内容を代わりに使う
            StringBuilder sb = new StringBuilder(what != null ? what : text).append("を");
            if (when != null) {
                sb.append(when).append("までに");
            }
            sb.append("対応してほしい。");
            if (target != null) {
                sb.append("対象は").append(target).append("。");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        if (category == InquiryCategory.SPEC_QUESTION) {
            sb.append("「").append(text).append("」について、いまの仕組みがどうなっているかを知りたい。");
        } else {
            sb.append("「").append(text).append("」という状況が起きている。これが意図した動きかどうかを確認したい。");
        }
        for (AnsweredQuestion a : answers) {
            if (!a.unknown()) {
                sb.append("（").append(a.question().text()).append(": ").append(a.answer().strip()).append("）");
            }
        }
        return sb.toString();
    }

    private static String answerOf(List<AnsweredQuestion> answers, String questionId) {
        return answers.stream()
                .filter(a -> a.question().id().equals(questionId) && !a.unknown())
                .map(a -> a.answer().strip())
                .findFirst()
                .orElse(null);
    }

    private static String stripTags(String utterance) {
        String text = utterance;
        for (String tag : List.of(
                TAG_NO_QUESTIONS, TAG_NO_GROUNDS, TAG_GHOST_CITATION, TAG_SLOW, TAG_ERROR, TAG_REFUSED, TAG_TRIAGE_ERROR)) {
            text = text.replace(tag, "");
        }
        return text.strip();
    }

    /**
     * 0次回答を作る。タグは整形後の {@code goal} から読む。
     *
     * @param inquiry 本人が確認した整形後の問い
     * @param scope 投稿者の閲覧範囲
     * @param progress 進捗の通知先
     * @return 0次回答（出典は未照合）
     * @throws IllegalArgumentException 0次回答を出さない種別を渡した場合
     * @throws AiRefusalException {@code [拒否]} の場合
     * @throws AiUnavailableException {@code [エラー]} の場合
     */
    @Override
    public AiResult<ZeroAnswer> answer(StructuredInquiry inquiry, AccessScope scope, ProgressListener progress) {
        if (!inquiry.category().receivesZeroAnswer()) {
            throw new IllegalArgumentException("0次回答を出さない種別: " + inquiry.category());
        }
        String tags = inquiry.goal();
        boolean slow = tags.contains(TAG_SLOW);

        step(slow, progress, "コードを検索しています");
        step(slow, progress, "FAQを確認しています");
        step(slow, progress, "用語対応表を照合しています");
        if (tags.contains(TAG_ERROR)) {
            throw new AiUnavailableException("モックの回答生成エラー", null);
        }
        if (tags.contains(TAG_REFUSED)) {
            throw new AiRefusalException("モックが回答を拒否しました");
        }
        step(slow, progress, "回答をまとめています");

        ZeroAnswer answer;
        if (tags.contains(TAG_NO_GROUNDS)) {
            answer = ZeroAnswer.ungrounded(new TechnicalMemo(
                    List.of("「求人」「掲載」に関する記述を検索したが、該当する仕様は確認できなかった"),
                    List.of("job-board のコード、FAQ、用語対応表に該当する記述は見つからなかった"),
                    List.of("関連する画面名と、期待する動作を本人に確認する")));
        } else if (tags.contains(TAG_GHOST_CITATION)) {
            answer = new ZeroAnswer(
                    true,
                    explanation(inquiry.category()),
                    memo(),
                    List.of(new Citation(
                            Citation.SourceType.CODE,
                            CODE_REPOSITORY,
                            "src/main/java/com/example/jobboard/GhostService.java",
                            1,
                            3,
                            "return ghost.expiresAt();",
                            "mock")));
        } else {
            answer = new ZeroAnswer(true, explanation(inquiry.category()), memo(), realCitations());
        }
        return new AiResult<>(answer, Usage.ofMock());
    }

    private static InquiryCategory classify(String utterance) {
        if (containsAny(utterance, INCIDENT_WORDS)) {
            return InquiryCategory.INCIDENT;
        }
        if (containsAny(utterance, WORK_REQUEST_WORDS)) {
            return InquiryCategory.WORK_REQUEST;
        }
        if (containsAny(utterance, BUG_WORDS)) {
            return InquiryCategory.SUSPECTED_BUG;
        }
        return InquiryCategory.SPEC_QUESTION;
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    private void step(boolean slow, ProgressListener progress, String message) {
        if (slow) {
            try {
                Thread.sleep(slowStepDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("モックの待機が中断された", e);
            }
        }
        progress.onProgress(message);
    }

    /** 不具合の疑いでは仕様の正否を断定せず、現在の動作として説明する。 */
    private static String explanation(InquiryCategory category) {
        if (category == InquiryCategory.SUSPECTED_BUG) {
            return "現在の実装では、掲載期限の時刻ちょうどから求人は期限切れとして扱われ、"
                    + "応募者向けの一覧に表示されなくなります。この動作が意図した仕様かどうかは、担当者が確認します。";
        }
        return "掲載期限の時刻を過ぎた求人は、応募者向けの一覧に表示されません。"
                + "期限切れの求人は延長できないため、新しい求人として登録し直す必要があります。";
    }

    private static TechnicalMemo memo() {
        return new TechnicalMemo(
                List.of(
                        "JobPostingService#isExpired は now が expiresAt 以降なら期限切れと判定する（期限ちょうども期限切れ）",
                        "FAQ「求人の掲載期限について」にも、期限を過ぎた求人は一覧に出ないと書かれている"),
                List.of("期限切れの求人を一覧に出す設定やフラグは、job-board のコードには見つからなかった"),
                List.of("期限の境界（ちょうどの時刻）の扱いが意図した仕様かを、仕様の持ち主に確認する"));
    }

    private static List<Citation> realCitations() {
        return List.of(
                new Citation(
                        Citation.SourceType.CODE,
                        CODE_REPOSITORY,
                        CODE_PATH,
                        10,
                        12,
                        "return !now.isBefore(posting.expiresAt());",
                        "mock"),
                new Citation(
                        Citation.SourceType.FAQ,
                        "faq",
                        FAQ_PATH,
                        5,
                        6,
                        "掲載期限を過ぎた求人は応募者向けの一覧に表示されません。",
                        "mock"));
    }
}
