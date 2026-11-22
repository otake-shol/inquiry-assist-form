package dev.inquiryassist.core.intake;

import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.skill.Prefill;
import dev.inquiryassist.core.skill.Reception;
import java.util.ArrayList;
import java.util.List;

/**
 * 入口の質問を決める処理。次に何を聞くか、マスの設定のどれを使うか、答えをどう検証して受け取るか、
 * 戻ったときに何を捨てるか、受付が置いた値をどう取り込むかを担う。
 *
 * <p>状態は持たず、Spring にも HTTP にも依存しない。置き場所（種類、システム、機能）は保存せず、
 * 答え終えた質問（{@link IntakeEntry}）から導く。そうすると、戻ったときに「その答えで決まっていた置き場所」を
 * 別に消す必要がなく、答えと置き場所がずれない。
 *
 * <p>聞く順は、種類、システム、機能、詳しい質問、補足。機能は、システムが決まっていて、機能の設定があるときだけ聞く。
 */
public final class IntakePlanner {

    /** 詳しい質問の上限。マスの設定に3問を超えて書かれていても、超えた分は捨てる。 */
    public static final int MAX_DETAIL_QUESTIONS = 3;

    /** 文章で答える詳しい質問の文字数の上限。 */
    public static final int MAX_ANSWER_LENGTH = 1000;

    /** 補足の文字数の上限。 */
    public static final int MAX_NOTE_LENGTH = 4000;

    static final String TYPE_TEXT = "何をしたいですか";
    static final String SYSTEM_TEXT = "どのシステムですか";
    static final String FUNCTION_TEXT = "どの機能ですか";
    static final String NOTE_TEXT = "補足があれば書いてください";
    static final String NOTE_REQUIRED_TEXT = "状況を一言で教えてください";

    private final IntakeCatalog catalog;

    /**
     * @param catalog 種類、システム、機能、マスの設定
     */
    public IntakePlanner(IntakeCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 戻った結果。
     *
     * @param entries 残す答え
     * @param detailsReset 置き場所の答えを捨てたため、聞き手が作った詳しい質問も捨てるか
     */
    public record Back(List<IntakeEntry> entries, boolean detailsReset) {}

    /**
     * 受付が置いた値を取り込んだ結果。
     *
     * @param entries 取り込み後の答え
     * @param changed 置き場所が変わったか
     * @param detailsReset 置き場所が変わったため、詳しい質問の答えと聞き手の質問を捨てたか
     */
    public record Placed(List<IntakeEntry> entries, boolean changed, boolean detailsReset) {}

    /**
     * 次にすることを決める。
     *
     * <p>種類、システム、機能の順に、答えがなければ聞く。詳しい質問は、緊急度が {@code BLOCKED} なら聞かない。
     * マスの設定に質問があればそれを使い、なければ聞き手に作らせる。補足は、本人が文章を書いていれば聞かない。
     *
     * @param snapshot 入口の状態
     * @return 次の質問、聞き手を呼ぶこと、または入口を終えたこと
     */
    public IntakeNext next(IntakeSnapshot snapshot) {
        List<IntakeEntry> entries = snapshot.entries();
        if (IntakeEntries.find(entries, QuestionKind.TYPE) == null) {
            return ask(typeQuestion());
        }
        if (!catalog.systems().isEmpty() && IntakeEntries.find(entries, QuestionKind.SYSTEM) == null) {
            return ask(systemQuestion());
        }
        IntakeCatalog.SystemInfo system = catalog.system(IntakeEntries.placement(entries).systemId());
        if (system != null
                && !system.functions().isEmpty()
                && IntakeEntries.find(entries, QuestionKind.FUNCTION) == null) {
            return ask(functionQuestion(system));
        }
        boolean noteAnswered = IntakeEntries.find(entries, QuestionKind.NOTE) != null;
        if (!noteAnswered && snapshot.urgency() != Urgency.BLOCKED) {
            List<IntakeQuestion> configured = detailQuestions(IntakeEntries.placement(entries));
            if (snapshot.hasText()) {
                // 文章に答えが書かれていれば聞かない。読み取りは、質問ごとに1回だけ行う
                List<IntakeQuestion> pending = configured.stream()
                        .filter(q -> !hasDetailEntry(entries, q.id()) && !snapshot.prefillAttempted().contains(q.id()))
                        .toList();
                if (!pending.isEmpty()) {
                    return new IntakeNext.NeedsPrefill(pending);
                }
            }
            List<IntakeQuestion> plan = configured;
            if (plan.isEmpty()) {
                if (snapshot.interviewed() == null) {
                    return new IntakeNext.NeedsInterviewer();
                }
                plan = snapshot.interviewed();
            }
            for (IntakeQuestion question : plan) {
                boolean answered = entries.stream()
                        .anyMatch(e -> e.kind() == QuestionKind.DETAIL && e.questionId().equals(question.id()));
                if (!answered) {
                    return ask(question);
                }
            }
        }
        if (noteAnswered || snapshot.hasText()) {
            return new IntakeNext.Complete();
        }
        return ask(noteQuestion(canSkipNote(snapshot)));
    }

    private static boolean hasDetailEntry(List<IntakeEntry> entries, String questionId) {
        return entries.stream().anyMatch(e -> e.kind() == QuestionKind.DETAIL && e.questionId().equals(questionId));
    }

    /**
     * 聞き手が読み取った答えを確かめて、答え終えた質問にする。モデルの出力は信用しない。モックでも実際のモデルでも
     * 同じ確かめを通すため、聞き手の実装ではここで行う。
     *
     * <p>選択肢のある質問は、返された選択肢がその質問の選択肢にあれば採る。文章で答える質問は、返された答えが
     * 本人の文章の中にそのまま含まれていれば採る（空白の違いは無視する。AI が文章にない答えを作るのを防ぐため）。
     * 読み取る質問にない識別子の答えは捨てる。同じ質問への答えが複数あれば、先のものだけを採る。
     * 採れなかった質問は、通常どおり本人に聞く。
     *
     * @param entries 答え終えた質問
     * @param asked 読み取りを頼んだ質問
     * @param utterance 本人が書いた文章
     * @param prefill 聞き手の出力
     * @return 採れた答え（読み取りを頼んだ質問の順）。呼び出し側が、答えの一覧の末尾に足す
     */
    public List<IntakeEntry> acceptPrefill(
            List<IntakeEntry> entries, List<IntakeQuestion> asked, String utterance, Prefill prefill) {
        String squeezed = squeeze(utterance);
        List<IntakeEntry> result = new ArrayList<>();
        for (IntakeQuestion question : asked) {
            if (hasDetailEntry(entries, question.id())) {
                continue;
            }
            for (Prefill.Answer answer : prefill.answers()) {
                if (!question.id().equals(answer.questionId())) {
                    continue;
                }
                IntakeAnswer taken = takeAnswer(question, answer, squeezed);
                if (taken != null) {
                    result.add(new IntakeEntry(question.id(), QuestionKind.DETAIL, question.text(), taken));
                }
                // 同じ質問への答えが複数あっても、先のものだけを見る
                break;
            }
        }
        return result;
    }

    private static IntakeAnswer takeAnswer(
            IntakeQuestion question, Prefill.Answer answer, String squeezedUtterance) {
        if (!question.options().isEmpty()) {
            return question.options().stream()
                    .filter(o -> o.id().equals(answer.optionId()))
                    .findFirst()
                    .map(o -> IntakeAnswer.inferred(o.id(), o.label()))
                    .orElse(null);
        }
        if (answer.text() == null || answer.text().isBlank()) {
            return null;
        }
        String squeezedAnswer = squeeze(answer.text());
        return !squeezedUtterance.isEmpty() && squeezedUtterance.contains(squeezedAnswer)
                ? IntakeAnswer.inferredText(answer.text().strip())
                : null;
    }

    /** 空白をすべて除く。空白の違いを無視して、文章に含まれるかを比べるため。 */
    private static String squeeze(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u3000]+", "");
    }

    /**
     * 補足を書かずに進めるかを返す。手がかりが何もないまま担当者に渡さないため、次の場合は書かずには進めない。
     * ここまでのすべての質問に「わからない」と答えた場合と、緊急度が {@code BLOCKED} の場合（文章がないことは前提）。
     *
     * @param snapshot 入口の状態。文章を書いていない場合を前提にする
     * @return 書かずに進める場合は true
     */
    private static boolean canSkipNote(IntakeSnapshot snapshot) {
        boolean allUnknown = !snapshot.entries().isEmpty()
                && snapshot.entries().stream().allMatch(e -> e.answer().kind() == AnswerKind.UNKNOWN);
        return !allUnknown && snapshot.urgency() != Urgency.BLOCKED;
    }

    /**
     * 本人の答えを検証して、答え終えた質問にする。いま聞いている質問（{@link #next} が返す質問）への答えだけを受け付ける。
     *
     * @param snapshot 入口の状態
     * @param reply 本人の答え
     * @return 答え終えた質問。呼び出し側が、答えの一覧の末尾に足す
     * @throws QuestionMismatchException いま聞いている質問がない、または別の質問への答えの場合
     * @throws IllegalArgumentException 選択肢にない値、答え方が不正、書けない質問への「書かずに進む」、
     *     長さの超過の場合
     */
    public IntakeEntry accept(IntakeSnapshot snapshot, IntakeReply reply) {
        if (!(next(snapshot) instanceof IntakeNext.Ask ask)) {
            throw new QuestionMismatchException("いま答える質問はありません");
        }
        IntakeQuestion question = ask.question();
        if (!question.id().equals(reply.questionId())) {
            throw new QuestionMismatchException("いま答える質問は " + question.id() + " です");
        }
        int ways = (reply.optionId() != null ? 1 : 0)
                + (reply.text() != null ? 1 : 0)
                + (reply.unknown() ? 1 : 0)
                + (reply.skipped() ? 1 : 0);
        if (ways != 1) {
            throw new IllegalArgumentException("答え方は、選択肢、文章、わからない、書かずに進むのどれか1つ");
        }
        IntakeAnswer answer;
        if (reply.unknown()) {
            if (question.kind() == QuestionKind.NOTE) {
                throw new IllegalArgumentException("補足には「わからない」と答えられません");
            }
            answer = IntakeAnswer.unknown();
        } else if (reply.skipped()) {
            if (!question.canSkip()) {
                throw new IllegalArgumentException("この質問は書かずに進めません");
            }
            answer = IntakeAnswer.skipped();
        } else if (reply.optionId() != null) {
            Choice choice = question.options().stream()
                    .filter(o -> o.id().equals(reply.optionId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("選択肢にない値です: " + reply.optionId()));
            answer = IntakeAnswer.option(choice.id(), choice.label());
        } else {
            if (!question.allowsText()) {
                throw new IllegalArgumentException("この質問は選択肢から選びます");
            }
            String text = reply.text().strip();
            int max = question.kind() == QuestionKind.NOTE ? MAX_NOTE_LENGTH : MAX_ANSWER_LENGTH;
            if (text.isEmpty() || text.length() > max) {
                throw new IllegalArgumentException("文章は1〜" + max + "文字");
            }
            answer = IntakeAnswer.text(text);
        }
        return new IntakeEntry(question.id(), question.kind(), question.text(), answer);
    }

    /**
     * 答え終えた質問に戻る。指定した質問と、それより後の答えを捨てる。
     *
     * <p>置き場所の答えを捨てた場合は、その置き場所で決まっていた詳しい質問も変わりうるため、
     * 聞き手が作った質問も捨てるよう伝える。
     *
     * @param entries 答え終えた質問
     * @param questionId 戻る質問の識別子
     * @return 残す答え
     * @throws IllegalArgumentException 答え終えた質問にない識別子の場合
     */
    public Back back(List<IntakeEntry> entries, String questionId) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).questionId().equals(questionId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("答え終えていない質問です: " + questionId);
        }
        boolean detailsReset = entries.subList(index, entries.size()).stream()
                .anyMatch(e -> e.kind().isPlacement());
        return new Back(List.copyOf(entries.subList(0, index)), detailsReset);
    }

    /**
     * 受付が文章から置いた値を取り込む。
     *
     * <p>本人が選んだ項目は変えない。決まっていない項目（まだ答えていない、「わからない」と答えた）は、
     * 受付が置けたものを {@code INFERRED} として入れる。置けなかった項目は、そのまま残す（質問として聞く）。
     * 以前の受付が置いた項目も変えない（最初の読み取りを残し、直したいときは本人が戻って選ぶ）。
     * 置き場所が変わった場合は、詳しい質問の答えと補足を捨てる（別の置き場所の質問への答えになるため）。
     *
     * @param entries 答え終えた質問
     * @param inferred 受付の出力
     * @return 取り込み後の答え
     */
    public Placed place(List<IntakeEntry> entries, Reception inferred) {
        Placement before = IntakeEntries.placement(entries);
        Reception normalized = Reception.normalize(inferred, catalog.candidates(), IntakeEntries.chosen(entries));

        IntakeEntry type = merged(entries, QuestionKind.TYPE, normalized.type() == null ? null : normalized.type().name(),
                TYPE_TEXT, normalized.type() == null ? null : catalog.typeLabel(normalized.type()));
        IntakeEntry system = merged(entries, QuestionKind.SYSTEM, normalized.systemId(), SYSTEM_TEXT,
                normalized.systemId() == null ? null : catalog.systemLabel(normalized.systemId()));
        String systemId = system != null && system.answer().decides() ? system.answer().optionId() : null;
        IntakeEntry function = merged(entries, QuestionKind.FUNCTION, normalized.functionId(), FUNCTION_TEXT,
                normalized.functionId() == null || systemId == null
                        ? null
                        : catalog.functionLabel(systemId, normalized.functionId()));

        List<IntakeEntry> result = new ArrayList<>();
        for (IntakeEntry placement : new IntakeEntry[] {type, system, function}) {
            if (placement != null) {
                result.add(placement);
            }
        }
        List<IntakeEntry> rest = entries.stream().filter(e -> !e.kind().isPlacement()).toList();

        boolean changed = !before.equals(IntakeEntries.placement(result));
        // 置き場所が変わると、詳しい質問が別のものになりうる。前の置き場所への答えを残さない
        boolean detailsReset = changed && !rest.isEmpty();
        if (!detailsReset) {
            result.addAll(rest);
        }
        return new Placed(List.copyOf(result), changed, detailsReset);
    }

    /**
     * 受付を呼ぶ意味があるかを返す。決まっていない置き場所（種類、システム、機能）が残っているときだけ呼ぶ。
     * システムが決まっていなければ、機能は（システムが置かれれば）置けるため、残っているものとして数える。
     *
     * @param entries 答え終えた質問
     * @return 受付が置ける項目が残っていれば true
     */
    public boolean needsPlacement(List<IntakeEntry> entries) {
        Placement placed = IntakeEntries.placement(entries);
        if (placed.type() == null) {
            return true;
        }
        if (!catalog.systems().isEmpty() && placed.systemId() == null) {
            return true;
        }
        IntakeCatalog.SystemInfo system = catalog.system(placed.systemId());
        return system != null && !system.functions().isEmpty() && placed.functionId() == null;
    }

    /**
     * 置き場所に当てはまるマスの設定を1つ選ぶ。指定した項目が最も多いものを優先し、同数なら設定の順で先のもの。
     * 決まっていない項目（{@code null}）は、その項目を指定した設定には当てはまらない。
     *
     * @param placement 置き場所
     * @return マスの設定。当てはまるものがなければ {@code null}
     */
    public IntakeCatalog.Cell cellFor(Placement placement) {
        IntakeCatalog.Cell best = null;
        for (IntakeCatalog.Cell cell : catalog.cells()) {
            if (!applies(cell, placement)) {
                continue;
            }
            if (best == null || cell.specificity() > best.specificity()) {
                best = cell;
            }
        }
        return best;
    }

    /**
     * マスの設定にある詳しい質問を返す（3問まで。超える分は捨てる）。
     *
     * @param placement 置き場所
     * @return 詳しい質問。設定に質問がなければ空（聞き手が作る）
     */
    public List<IntakeQuestion> detailQuestions(Placement placement) {
        IntakeCatalog.Cell cell = cellFor(placement);
        if (cell == null) {
            return List.of();
        }
        return cell.questions().stream().limit(MAX_DETAIL_QUESTIONS).toList();
    }

    private static boolean applies(IntakeCatalog.Cell cell, Placement placement) {
        return matches(cell.system(), placement.systemId())
                && matches(cell.function(), placement.functionId())
                && (cell.type() == null || cell.type() == placement.type());
    }

    /** 設定が項目を指定していなければ当てはまる。指定していれば、置き場所が同じ値のときだけ当てはまる。 */
    private static boolean matches(String configured, String actual) {
        return configured == null || configured.equals(actual);
    }

    /**
     * 置き場所1項目の答えを、受付が置いた値で更新する。決まっている答え（本人が選んだもの、以前の受付が置いたもの）は残す。
     * 受付が置けなかった場合は、元の答え（「わからない」など）を残す。
     *
     * @return 更新後の答え。答えがなく、置けもしなかった場合は {@code null}
     */
    private IntakeEntry merged(List<IntakeEntry> entries, QuestionKind kind, String id, String text, String label) {
        IntakeEntry existing = IntakeEntries.find(entries, kind);
        if (existing != null && existing.answer().decides()) {
            return existing;
        }
        if (id == null) {
            return existing;
        }
        return new IntakeEntry(questionIdOf(kind), kind, text, IntakeAnswer.inferred(id, label));
    }

    private static String questionIdOf(QuestionKind kind) {
        return switch (kind) {
            case TYPE -> IntakeQuestion.TYPE_ID;
            case SYSTEM -> IntakeQuestion.SYSTEM_ID;
            case FUNCTION -> IntakeQuestion.FUNCTION_ID;
            case NOTE -> IntakeQuestion.NOTE_ID;
            case DETAIL -> throw new IllegalArgumentException("詳しい質問の識別子は決まっていない");
        };
    }

    private static IntakeNext ask(IntakeQuestion question) {
        return new IntakeNext.Ask(question);
    }

    private IntakeQuestion typeQuestion() {
        return new IntakeQuestion(
                IntakeQuestion.TYPE_ID,
                QuestionKind.TYPE,
                TYPE_TEXT,
                catalog.types().stream()
                        .map(t -> new Choice(t.type().name(), t.label()))
                        .toList(),
                false,
                false);
    }

    private IntakeQuestion systemQuestion() {
        return new IntakeQuestion(
                IntakeQuestion.SYSTEM_ID,
                QuestionKind.SYSTEM,
                SYSTEM_TEXT,
                catalog.systems().stream().map(s -> new Choice(s.id(), s.label())).toList(),
                false,
                false);
    }

    private IntakeQuestion functionQuestion(IntakeCatalog.SystemInfo system) {
        return new IntakeQuestion(
                IntakeQuestion.FUNCTION_ID, QuestionKind.FUNCTION, FUNCTION_TEXT, system.functions(), false, false);
    }

    private IntakeQuestion noteQuestion(boolean canSkip) {
        return new IntakeQuestion(
                IntakeQuestion.NOTE_ID,
                QuestionKind.NOTE,
                canSkip ? NOTE_TEXT : NOTE_REQUIRED_TEXT,
                List.of(),
                true,
                canSkip);
    }
}
