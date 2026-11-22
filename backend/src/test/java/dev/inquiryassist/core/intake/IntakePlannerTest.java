package dev.inquiryassist.core.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.skill.Prefill;
import dev.inquiryassist.core.skill.Reception;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 入口の質問を決める処理。Spring なしで、聞く順、マスの設定の選び方、答えの検証、戻る、受付の取り込みを確かめる。 */
class IntakePlannerTest {

    private static final IntakeQuestion SINCE = detail("since", "いつから", "today", "今日から", "earlier", "もっと前から");
    private static final IntakeQuestion PARTNER =
            new IntakeQuestion("partner", QuestionKind.DETAIL, "取引先", List.of(), false, false);

    private static IntakeQuestion detail(String id, String text, String... idAndLabel) {
        List<Choice> options = new ArrayList<>();
        for (int i = 0; i < idAndLabel.length; i += 2) {
            options.add(new Choice(idAndLabel[i], idAndLabel[i + 1]));
        }
        return new IntakeQuestion(id, QuestionKind.DETAIL, text, options, false, false);
    }

    private static IntakeCatalog catalog(IntakeCatalog.Cell... cells) {
        return new IntakeCatalog(
                List.of(
                        new IntakeCatalog.TypeInfo(InquiryType.HOW_TO, "使い方", ""),
                        new IntakeCatalog.TypeInfo(InquiryType.HOW_IT_WORKS, "仕組み", ""),
                        new IntakeCatalog.TypeInfo(InquiryType.LOOKS_WRONG, "おかしい", ""),
                        new IntakeCatalog.TypeInfo(InquiryType.DO_FOR_ME, "やってほしい", ""),
                        new IntakeCatalog.TypeInfo(InquiryType.CHANGE_REQUEST, "変えてほしい", "")),
                List.of(
                        new IntakeCatalog.UrgencyInfo(Urgency.NORMAL, "通常"),
                        new IntakeCatalog.UrgencyInfo(Urgency.HURRY, "急ぎ"),
                        new IntakeCatalog.UrgencyInfo(Urgency.BLOCKED, "止まっている")),
                List.of(
                        new IntakeCatalog.SystemInfo(
                                "job-admin",
                                "求人管理画面",
                                List.of(new Choice("posting", "求人の掲載"), new Choice("applicants", "応募者の一覧"))),
                        new IntakeCatalog.SystemInfo("billing", "請求管理", List.of(new Choice("invoice", "請求書"))),
                        new IntakeCatalog.SystemInfo("reports", "レポート", List.of())),
                List.of(cells));
    }

    private static IntakeCatalog.Cell cell(
            String system, String function, InquiryType type, IntakeQuestion... questions) {
        return new IntakeCatalog.Cell(system, function, type, List.of(questions));
    }

    private final IntakePlanner planner = new IntakePlanner(catalog(
            cell("job-admin", null, InquiryType.LOOKS_WRONG, SINCE),
            cell("billing", "invoice", InquiryType.DO_FOR_ME, PARTNER)));

    // ---- 答えの組み立て ----

    private static IntakeEntry type(InquiryType t) {
        return new IntakeEntry("type", QuestionKind.TYPE, "何をしたいですか", IntakeAnswer.option(t.name(), t.name()));
    }

    private static IntakeEntry system(String id) {
        return new IntakeEntry("system", QuestionKind.SYSTEM, "どのシステムですか", IntakeAnswer.option(id, id));
    }

    private static IntakeEntry function(String id) {
        return new IntakeEntry("function", QuestionKind.FUNCTION, "どの機能ですか", IntakeAnswer.option(id, id));
    }

    private static IntakeEntry unknown(String questionId, QuestionKind kind) {
        return new IntakeEntry(questionId, kind, "q", IntakeAnswer.unknown());
    }

    private static IntakeEntry answered(IntakeQuestion q, String optionId) {
        return new IntakeEntry(q.id(), QuestionKind.DETAIL, q.text(), IntakeAnswer.option(optionId, optionId));
    }

    private static IntakeSnapshot snapshot(List<IntakeEntry> entries) {
        return new IntakeSnapshot(entries, null, false, Urgency.NORMAL);
    }

    private static IntakeSnapshot snapshot(
            List<IntakeEntry> entries, List<IntakeQuestion> interviewed, boolean hasText, Urgency urgency) {
        return new IntakeSnapshot(entries, interviewed, hasText, urgency);
    }

    private IntakeQuestion asked(IntakeSnapshot s) {
        IntakeNext next = planner.next(s);
        assertThat(next).isInstanceOf(IntakeNext.Ask.class);
        return ((IntakeNext.Ask) next).question();
    }

    // ---- 聞く順 ----

    @Test
    void 種類_システム_機能の順に聞く() {
        IntakeQuestion first = asked(snapshot(List.of()));
        assertThat(first.id()).isEqualTo("type");
        assertThat(first.kind()).isEqualTo(QuestionKind.TYPE);
        assertThat(first.options())
                .extracting(Choice::id)
                .containsExactly("HOW_TO", "HOW_IT_WORKS", "LOOKS_WRONG", "DO_FOR_ME", "CHANGE_REQUEST");
        assertThat(first.canSkip()).isFalse();
        assertThat(first.allowsText()).isFalse();

        IntakeQuestion second = asked(snapshot(List.of(type(InquiryType.HOW_TO))));
        assertThat(second.kind()).isEqualTo(QuestionKind.SYSTEM);
        assertThat(second.options()).extracting(Choice::id).containsExactly("job-admin", "billing", "reports");

        IntakeQuestion third = asked(snapshot(List.of(type(InquiryType.HOW_TO), system("job-admin"))));
        assertThat(third.kind()).isEqualTo(QuestionKind.FUNCTION);
        assertThat(third.options()).extracting(Choice::id).containsExactly("posting", "applicants");
    }

    @Test
    void システムが決まっていなければ機能は聞かない() {
        IntakeNext next =
                planner.next(snapshot(List.of(type(InquiryType.HOW_TO), unknown("system", QuestionKind.SYSTEM))));

        // 機能に進まず、詳しい質問（聞き手）に進む
        assertThat(next).isInstanceOf(IntakeNext.NeedsInterviewer.class);
    }

    @Test
    void 機能の設定がないシステムでは機能を聞かない() {
        IntakeNext next = planner.next(snapshot(List.of(type(InquiryType.HOW_TO), system("reports"))));

        assertThat(next).isInstanceOf(IntakeNext.NeedsInterviewer.class);
    }

    @Test
    void 機能を聞いた後に詳しい質問に進む() {
        IntakeNext next = planner.next(snapshot(List.of(
                type(InquiryType.HOW_TO), system("job-admin"), unknown("function", QuestionKind.FUNCTION))));

        assertThat(next).isInstanceOf(IntakeNext.NeedsInterviewer.class);
    }

    // ---- マスの設定の選び方 ----

    @Test
    void 指定の多い設定を優先する() {
        IntakeQuestion general = detail("general", "全体", "a", "A");
        IntakeQuestion bySystem = detail("by-system", "システム", "a", "A");
        IntakeQuestion bySystemAndType = detail("by-both", "システムと種類", "a", "A");
        IntakePlanner p = new IntakePlanner(catalog(
                cell(null, null, null, general),
                cell("job-admin", null, null, bySystem),
                cell("job-admin", null, InquiryType.LOOKS_WRONG, bySystemAndType)));

        assertThat(p.detailQuestions(new Placement(InquiryType.LOOKS_WRONG, "job-admin", null)))
                .containsExactly(bySystemAndType);
        assertThat(p.detailQuestions(new Placement(InquiryType.HOW_TO, "job-admin", null)))
                .containsExactly(bySystem);
        assertThat(p.detailQuestions(new Placement(InquiryType.HOW_TO, "billing", null)))
                .containsExactly(general);
    }

    @Test
    void 指定の数が同じなら設定の順で先のものを使う() {
        IntakeQuestion first = detail("first", "先", "a", "A");
        IntakeQuestion second = detail("second", "後", "a", "A");
        IntakePlanner p = new IntakePlanner(
                catalog(cell("job-admin", null, null, first), cell(null, null, InquiryType.LOOKS_WRONG, second)));

        assertThat(p.detailQuestions(new Placement(InquiryType.LOOKS_WRONG, "job-admin", null)))
                .containsExactly(first);
    }

    @Test
    void 決まっていない項目を指定した設定には当てはまらない() {
        IntakePlanner p = new IntakePlanner(catalog(
                cell("job-admin", null, InquiryType.LOOKS_WRONG, SINCE),
                cell(null, null, InquiryType.HOW_TO, PARTNER)));

        // システムが決まっていない（null）ので、システムを指定した設定には当てはまらない
        assertThat(p.cellFor(new Placement(InquiryType.LOOKS_WRONG, null, null))).isNull();
        // 種類が決まっていないので、種類を指定した設定には当てはまらない
        assertThat(p.cellFor(new Placement(null, "job-admin", null))).isNull();
        // 機能を指定した設定は、機能が決まっていなければ当てはまらない
        IntakePlanner byFunction = new IntakePlanner(catalog(cell("billing", "invoice", null, PARTNER)));
        assertThat(byFunction.cellFor(new Placement(InquiryType.HOW_TO, "billing", null))).isNull();
        assertThat(byFunction.cellFor(new Placement(InquiryType.HOW_TO, "billing", "invoice")))
                .isNotNull();
    }

    @Test
    void 当てはまる設定がなければ設定の質問はない() {
        assertThat(planner.detailQuestions(new Placement(InquiryType.HOW_TO, "billing", "invoice"))).isEmpty();
    }

    @Test
    void 詳しい質問は3問までで超える分は捨てる() {
        IntakePlanner p = new IntakePlanner(catalog(cell(
                null,
                null,
                null,
                detail("q1", "1", "a", "A"),
                detail("q2", "2", "a", "A"),
                detail("q3", "3", "a", "A"),
                detail("q4", "4", "a", "A"))));

        assertThat(p.detailQuestions(Placement.NONE))
                .extracting(IntakeQuestion::id)
                .containsExactly("q1", "q2", "q3");
    }

    // ---- 詳しい質問 ----

    @Test
    void 設定の質問を設定の順に聞く() {
        List<IntakeEntry> entries = new ArrayList<>(List.of(
                type(InquiryType.LOOKS_WRONG), system("job-admin"), unknown("function", QuestionKind.FUNCTION)));

        assertThat(asked(snapshot(entries)).id()).isEqualTo("since");
        assertThat(asked(snapshot(entries)).options()).extracting(Choice::id).containsExactly("today", "earlier");

        entries.add(answered(SINCE, "today"));
        // 設定に質問があれば聞き手は呼ばず、詳しい質問が終われば補足に進む
        assertThat(asked(snapshot(entries)).kind()).isEqualTo(QuestionKind.NOTE);
    }

    @Test
    void 文章で答える設定の質問は選択肢がなく文章で答えられる() {
        List<IntakeEntry> entries = List.of(type(InquiryType.DO_FOR_ME), system("billing"), function("invoice"));

        IntakeQuestion question = asked(snapshot(entries));

        assertThat(question.id()).isEqualTo("partner");
        assertThat(question.options()).isEmpty();
        assertThat(question.allowsText()).isTrue();
    }

    @Test
    void 設定に質問がなければ聞き手を呼び_作った質問を聞く() {
        List<IntakeEntry> entries = List.of(type(InquiryType.HOW_TO), system("reports"));
        IntakeQuestion q1 = detail("q1", "どの場面ですか", "o1", "開いたとき");

        assertThat(planner.next(snapshot(entries))).isInstanceOf(IntakeNext.NeedsInterviewer.class);
        assertThat(asked(snapshot(entries, List.of(q1), false, Urgency.NORMAL))).isEqualTo(q1);
    }

    @Test
    void 聞き手が質問を作らなければ詳しい質問なしで補足に進む() {
        List<IntakeEntry> entries = List.of(type(InquiryType.HOW_TO), system("reports"));

        // 聞き手が失敗した場合も、保存する結果は空の一覧（呼んだが質問なし）
        assertThat(asked(snapshot(entries, List.of(), false, Urgency.NORMAL)).kind())
                .isEqualTo(QuestionKind.NOTE);
    }

    @Test
    void 設定に質問があるときは聞き手の質問を使わない() {
        List<IntakeEntry> entries = List.of(
                type(InquiryType.LOOKS_WRONG), system("job-admin"), unknown("function", QuestionKind.FUNCTION));
        IntakeQuestion fromInterviewer = detail("q1", "別", "o1", "A");

        assertThat(asked(snapshot(entries, List.of(fromInterviewer), false, Urgency.NORMAL)).id())
                .isEqualTo("since");
    }

    @Test
    void 聞き手が作った質問を順に聞く() {
        IntakeQuestion q1 = detail("q1", "1", "o1", "A");
        IntakeQuestion q2 = detail("q2", "2", "o1", "A");
        List<IntakeEntry> entries = new ArrayList<>(List.of(type(InquiryType.HOW_TO), system("reports")));

        assertThat(asked(snapshot(entries, List.of(q1, q2), false, Urgency.NORMAL)).id())
                .isEqualTo("q1");
        entries.add(answered(q1, "o1"));
        assertThat(asked(snapshot(entries, List.of(q1, q2), false, Urgency.NORMAL)).id())
                .isEqualTo("q2");
        entries.add(answered(q2, "o1"));
        assertThat(asked(snapshot(entries, List.of(q1, q2), false, Urgency.NORMAL)).kind())
                .isEqualTo(QuestionKind.NOTE);
    }

    // ---- 補足 ----

    private static List<IntakeEntry> placed() {
        return List.of(type(InquiryType.HOW_TO), system("reports"));
    }

    @Test
    void 補足は書かずに進める() {
        IntakeQuestion note = asked(snapshot(placed(), List.of(), false, Urgency.NORMAL));

        assertThat(note.id()).isEqualTo("note");
        assertThat(note.kind()).isEqualTo(QuestionKind.NOTE);
        assertThat(note.text()).isEqualTo("補足があれば書いてください");
        assertThat(note.canSkip()).isTrue();
        assertThat(note.options()).isEmpty();
    }

    @Test
    void すべての質問にわからないと答えて文章もなければ補足は省けない() {
        List<IntakeEntry> entries =
                List.of(unknown("type", QuestionKind.TYPE), unknown("system", QuestionKind.SYSTEM));

        IntakeQuestion note = asked(snapshot(entries, List.of(), false, Urgency.NORMAL));

        assertThat(note.kind()).isEqualTo(QuestionKind.NOTE);
        assertThat(note.canSkip()).isFalse();
        assertThat(note.text()).isEqualTo("状況を一言で教えてください");
    }

    @Test
    void ほかに手がかりがあれば_わからないと答えた質問があっても補足は省ける() {
        List<IntakeEntry> entries = List.of(type(InquiryType.HOW_TO), unknown("system", QuestionKind.SYSTEM));

        assertThat(asked(snapshot(entries, List.of(), false, Urgency.NORMAL)).canSkip())
                .isTrue();
    }

    @Test
    void 詳しい質問も含めすべてわからないなら省けない() {
        IntakeQuestion q1 = detail("q1", "1", "o1", "A");
        List<IntakeEntry> entries = List.of(
                unknown("type", QuestionKind.TYPE),
                unknown("system", QuestionKind.SYSTEM),
                new IntakeEntry("q1", QuestionKind.DETAIL, "1", IntakeAnswer.unknown()));

        assertThat(asked(snapshot(entries, List.of(q1), false, Urgency.NORMAL)).canSkip())
                .isFalse();
    }

    @Test
    void 文章を書いていれば補足は聞かずに入口を終える() {
        assertThat(planner.next(snapshot(placed(), List.of(), true, Urgency.NORMAL)))
                .isInstanceOf(IntakeNext.Complete.class);
    }

    @Test
    void 文章を書いていて_すべてわからなくても補足は聞かない() {
        List<IntakeEntry> entries =
                List.of(unknown("type", QuestionKind.TYPE), unknown("system", QuestionKind.SYSTEM));

        assertThat(planner.next(snapshot(entries, List.of(), true, Urgency.NORMAL)))
                .isInstanceOf(IntakeNext.Complete.class);
    }

    @Test
    void 補足に答えれば入口を終える() {
        List<IntakeEntry> entries = new ArrayList<>(placed());
        entries.add(new IntakeEntry("note", QuestionKind.NOTE, "補足", IntakeAnswer.skipped()));

        assertThat(planner.next(snapshot(entries, List.of(), false, Urgency.NORMAL)))
                .isInstanceOf(IntakeNext.Complete.class);
    }

    // ---- 緊急度 BLOCKED ----

    @Test
    void 業務が止まっているときは詳しい質問を聞かない() {
        // 設定に質問がある置き場所でも、聞き手を呼ぶ置き場所でも聞かない
        List<IntakeEntry> configured = List.of(
                type(InquiryType.LOOKS_WRONG), system("job-admin"), unknown("function", QuestionKind.FUNCTION));
        assertThat(asked(snapshot(configured, null, false, Urgency.BLOCKED)).kind())
                .isEqualTo(QuestionKind.NOTE);
        assertThat(asked(snapshot(placed(), null, false, Urgency.BLOCKED)).kind())
                .isEqualTo(QuestionKind.NOTE);
    }

    @Test
    void 業務が止まっていて文章がなければ補足は省けない() {
        IntakeQuestion note = asked(snapshot(placed(), null, false, Urgency.BLOCKED));

        assertThat(note.canSkip()).isFalse();
        assertThat(note.text()).isEqualTo("状況を一言で教えてください");
    }

    @Test
    void 業務が止まっていて文章があれば補足を聞かずに終える() {
        assertThat(planner.next(snapshot(placed(), null, true, Urgency.BLOCKED)))
                .isInstanceOf(IntakeNext.Complete.class);
    }

    @Test
    void 業務が止まっていても置き場所は聞く() {
        assertThat(asked(snapshot(List.of(), null, false, Urgency.BLOCKED)).kind())
                .isEqualTo(QuestionKind.TYPE);
    }

    // ---- 答えの受け取り ----

    private IntakeEntry accept(List<IntakeEntry> entries, IntakeReply reply) {
        return planner.accept(snapshot(entries), reply);
    }

    @Test
    void 選択肢を選ぶと選んだ時点の名前を残す() {
        IntakeEntry entry = accept(List.of(), new IntakeReply("type", "LOOKS_WRONG", null, false, false));

        assertThat(entry.kind()).isEqualTo(QuestionKind.TYPE);
        assertThat(entry.text()).isEqualTo("何をしたいですか");
        assertThat(entry.answer()).isEqualTo(IntakeAnswer.option("LOOKS_WRONG", "おかしい"));
    }

    @Test
    void わからないは補足以外のすべての質問で選べる() {
        assertThat(accept(List.of(), new IntakeReply("type", null, null, true, false))
                        .answer()
                        .kind())
                .isEqualTo(AnswerKind.UNKNOWN);
        assertThat(accept(List.of(type(InquiryType.HOW_TO)), new IntakeReply("system", null, null, true, false))
                        .answer()
                        .kind())
                .isEqualTo(AnswerKind.UNKNOWN);
        assertThat(accept(
                                List.of(type(InquiryType.HOW_TO), system("job-admin")),
                                new IntakeReply("function", null, null, true, false))
                        .answer()
                        .kind())
                .isEqualTo(AnswerKind.UNKNOWN);
        List<IntakeEntry> forDetail = List.of(
                type(InquiryType.LOOKS_WRONG), system("job-admin"), unknown("function", QuestionKind.FUNCTION));
        assertThat(accept(forDetail, new IntakeReply("since", null, null, true, false))
                        .answer()
                        .kind())
                .isEqualTo(AnswerKind.UNKNOWN);
        assertThatThrownBy(() -> planner.accept(
                        snapshot(placed(), List.of(), false, Urgency.NORMAL),
                        new IntakeReply("note", null, null, true, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 違う質問への答えは状態の誤りになる() {
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("system", "billing", null, false, false)))
                .isInstanceOf(QuestionMismatchException.class);
    }

    @Test
    void 答える質問がないときの答えは状態の誤りになる() {
        List<IntakeEntry> complete = List.of(type(InquiryType.HOW_TO), system("reports"));
        IntakeSnapshot done = snapshot(complete, List.of(), true, Urgency.NORMAL);

        assertThatThrownBy(() -> planner.accept(done, new IntakeReply("note", null, "x", false, false)))
                .isInstanceOf(QuestionMismatchException.class);
    }

    @Test
    void 選択肢にない値や答え方の不備は入力の誤りになる() {
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("type", "INCIDENT", null, false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        // 選択肢のある質問に文章では答えられない
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("type", null, "仕組み", false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        // 答え方が何もない、または2つ以上ある
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("type", null, null, false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("type", "HOW_TO", null, true, false)))
                .isInstanceOf(IllegalArgumentException.class);
        // 書かずに進めるのは補足だけ
        assertThatThrownBy(() -> accept(List.of(), new IntakeReply("type", null, null, false, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 文章で答える質問は前後の空白を除いて受け取り_長さを検証する() {
        List<IntakeEntry> entries = List.of(type(InquiryType.DO_FOR_ME), system("billing"), function("invoice"));

        assertThat(accept(entries, new IntakeReply("partner", null, "  A社 ", false, false))
                        .answer())
                .isEqualTo(IntakeAnswer.text("A社"));
        assertThatThrownBy(() -> accept(entries, new IntakeReply("partner", null, "   ", false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(accept(entries, new IntakeReply("partner", null, "あ".repeat(1000), false, false)))
                .isNotNull();
        assertThatThrownBy(() -> accept(entries, new IntakeReply("partner", null, "あ".repeat(1001), false, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 補足は4000文字まで書け_書かずに進めるのは省ける場合だけ() {
        IntakeSnapshot canSkip = snapshot(placed(), List.of(), false, Urgency.NORMAL);
        IntakeSnapshot mustWrite = snapshot(placed(), List.of(), false, Urgency.BLOCKED);

        assertThat(planner.accept(canSkip, new IntakeReply("note", null, null, false, true)).answer())
                .isEqualTo(IntakeAnswer.skipped());
        assertThat(planner.accept(canSkip, new IntakeReply("note", null, "あ".repeat(4000), false, false)))
                .isNotNull();
        assertThatThrownBy(() ->
                        planner.accept(canSkip, new IntakeReply("note", null, "あ".repeat(4001), false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.accept(mustWrite, new IntakeReply("note", null, null, false, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 戻る ----

    private static List<IntakeEntry> fullEntries() {
        return List.of(
                type(InquiryType.LOOKS_WRONG),
                system("job-admin"),
                function("posting"),
                answered(SINCE, "today"),
                new IntakeEntry("note", QuestionKind.NOTE, "補足", IntakeAnswer.text("メモ")));
    }

    @Test
    void 指定した質問以降の答えを捨てる() {
        IntakePlanner.Back back = planner.back(fullEntries(), "system");

        assertThat(back.entries()).extracting(IntakeEntry::questionId).containsExactly("type");
        // 置き場所の答えを捨てたので、聞き手が作った質問も捨てる
        assertThat(back.detailsReset()).isTrue();
        // 捨てた後の置き場所は、決まっていない状態に戻る
        assertThat(IntakeEntries.placement(back.entries()))
                .isEqualTo(new Placement(InquiryType.LOOKS_WRONG, null, null));
        assertThat(asked(snapshot(back.entries())).kind()).isEqualTo(QuestionKind.SYSTEM);
    }

    @Test
    void 詳しい質問や補足まで戻っても置き場所は残り_聞き手の質問は残す() {
        IntakePlanner.Back toDetail = planner.back(fullEntries(), "since");
        IntakePlanner.Back toNote = planner.back(fullEntries(), "note");

        assertThat(toDetail.entries())
                .extracting(IntakeEntry::questionId)
                .containsExactly("type", "system", "function");
        assertThat(toDetail.detailsReset()).isFalse();
        assertThat(IntakeEntries.placement(toDetail.entries()))
                .isEqualTo(new Placement(InquiryType.LOOKS_WRONG, "job-admin", "posting"));
        assertThat(toNote.entries()).hasSize(4);
        assertThat(toNote.detailsReset()).isFalse();
    }

    @Test
    void 最初の質問まで戻ると答えが空になる() {
        IntakePlanner.Back back = planner.back(fullEntries(), "type");

        assertThat(back.entries()).isEmpty();
        assertThat(IntakeEntries.placement(back.entries())).isEqualTo(Placement.NONE);
    }

    @Test
    void 答え終えていない質問へは戻れない() {
        assertThatThrownBy(() -> planner.back(fullEntries(), "partner")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.back(List.of(), "type")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 受付が置いた答えに戻ると置き場所が決まっていない状態に戻る() {
        List<IntakeEntry> entries = List.of(
                new IntakeEntry("type", QuestionKind.TYPE, "t", IntakeAnswer.inferred("HOW_TO", "使い方")),
                new IntakeEntry("system", QuestionKind.SYSTEM, "s", IntakeAnswer.inferred("billing", "請求管理")));

        IntakePlanner.Back back = planner.back(entries, "type");

        assertThat(IntakeEntries.placement(back.entries())).isEqualTo(Placement.NONE);
    }

    // ---- 受付が置いた値の取り込み ----

    @Test
    void 置けた置き場所は受付が置いたものとして入り_置けなかったものは質問として聞く() {
        IntakePlanner.Placed placed = planner.place(List.of(), new Reception(InquiryType.HOW_TO, "job-admin", null));

        assertThat(placed.entries()).extracting(IntakeEntry::questionId).containsExactly("type", "system");
        assertThat(placed.entries()).allSatisfy(e -> assertThat(e.answer().kind()).isEqualTo(AnswerKind.INFERRED));
        assertThat(placed.entries().get(0).answer().optionId()).isEqualTo("HOW_TO");
        assertThat(placed.entries().get(0).answer().label()).isEqualTo("使い方");
        assertThat(placed.entries().get(1).answer().label()).isEqualTo("求人管理画面");
        assertThat(placed.changed()).isTrue();
        assertThat(IntakeEntries.placedBy(placed.entries(), QuestionKind.TYPE)).isEqualTo(PlacedBy.AI);
        // 置けなかった機能は、質問として聞く
        assertThat(asked(new IntakeSnapshot(placed.entries(), null, true, Urgency.NORMAL)).kind())
                .isEqualTo(QuestionKind.FUNCTION);
    }

    @Test
    void 何も置けなければ何も変わらず質問に進む() {
        IntakePlanner.Placed placed = planner.place(List.of(), new Reception(null, null, null));

        assertThat(placed.entries()).isEmpty();
        assertThat(placed.changed()).isFalse();
        assertThat(asked(snapshot(placed.entries())).kind()).isEqualTo(QuestionKind.TYPE);
    }

    @Test
    void 本人が選んだものは変えない() {
        List<IntakeEntry> entries = List.of(type(InquiryType.DO_FOR_ME), system("billing"));

        IntakePlanner.Placed placed =
                planner.place(entries, new Reception(InquiryType.HOW_TO, "job-admin", "posting"));

        assertThat(IntakeEntries.placement(placed.entries()))
                .isEqualTo(new Placement(InquiryType.DO_FOR_ME, "billing", null));
        assertThat(placed.entries()).isEqualTo(entries);
        assertThat(placed.changed()).isFalse();
    }

    @Test
    void 本人が選んだシステムの機能を受付が置く() {
        List<IntakeEntry> entries = List.of(type(InquiryType.DO_FOR_ME), system("billing"));

        // 受付が別のシステムの機能を返しても、選ばれたシステムの機能でなければ置かない
        IntakePlanner.Placed wrong = planner.place(entries, new Reception(null, null, "posting"));
        assertThat(IntakeEntries.placement(wrong.entries()).functionId()).isNull();

        IntakePlanner.Placed right = planner.place(entries, new Reception(null, null, "invoice"));
        assertThat(IntakeEntries.placement(right.entries()).functionId()).isEqualTo("invoice");
        assertThat(IntakeEntries.placedBy(right.entries(), QuestionKind.FUNCTION)).isEqualTo(PlacedBy.AI);
        assertThat(IntakeEntries.placedBy(right.entries(), QuestionKind.TYPE)).isEqualTo(PlacedBy.ASKER);
    }

    @Test
    void 候補にないidと_システムのない機能は置かない() {
        IntakePlanner.Placed placed =
                planner.place(List.of(), new Reception(InquiryType.HOW_TO, "unknown-system", "posting"));

        assertThat(IntakeEntries.placement(placed.entries()))
                .isEqualTo(new Placement(InquiryType.HOW_TO, null, null));
    }

    @Test
    void わからないと答えた置き場所も受付が置ければ置き換える() {
        List<IntakeEntry> entries = List.of(unknown("type", QuestionKind.TYPE), system("job-admin"));

        IntakePlanner.Placed placed = planner.place(entries, new Reception(InquiryType.LOOKS_WRONG, null, null));

        assertThat(placed.entries()).extracting(IntakeEntry::questionId).containsExactly("type", "system");
        assertThat(IntakeEntries.placedBy(placed.entries(), QuestionKind.TYPE)).isEqualTo(PlacedBy.AI);
        // 置けなかった「わからない」は、そのまま残る
        IntakePlanner.Placed unchanged = planner.place(entries, new Reception(null, null, null));
        assertThat(unchanged.entries()).isEqualTo(entries);
    }

    @Test
    void 以前に受付が置いた項目は変えない() {
        List<IntakeEntry> entries =
                List.of(new IntakeEntry("type", QuestionKind.TYPE, "t", IntakeAnswer.inferred("HOW_TO", "使い方")));

        IntakePlanner.Placed placed = planner.place(entries, new Reception(InquiryType.LOOKS_WRONG, null, null));

        assertThat(IntakeEntries.placement(placed.entries()).type()).isEqualTo(InquiryType.HOW_TO);
    }

    @Test
    void 置き場所が変わると詳しい質問の答えを捨てる() {
        List<IntakeEntry> entries = List.of(
                type(InquiryType.HOW_TO),
                unknown("system", QuestionKind.SYSTEM),
                new IntakeEntry("q1", QuestionKind.DETAIL, "1", IntakeAnswer.option("o1", "A")));

        IntakePlanner.Placed placed = planner.place(entries, new Reception(null, "reports", null));

        assertThat(placed.changed()).isTrue();
        assertThat(placed.detailsReset()).isTrue();
        assertThat(placed.entries()).extracting(IntakeEntry::kind).doesNotContain(QuestionKind.DETAIL);
    }

    @Test
    void 置き場所が変わらなければ詳しい質問の答えを残す() {
        List<IntakeEntry> entries = new ArrayList<>(List.of(type(InquiryType.HOW_TO), system("reports")));
        entries.add(new IntakeEntry("q1", QuestionKind.DETAIL, "1", IntakeAnswer.option("o1", "A")));

        IntakePlanner.Placed placed = planner.place(entries, new Reception(InquiryType.LOOKS_WRONG, "billing", null));

        assertThat(placed.changed()).isFalse();
        assertThat(placed.detailsReset()).isFalse();
        assertThat(placed.entries()).isEqualTo(entries);
    }

    @Test
    void 受付を呼ぶのは決まっていない置き場所が残っているときだけ() {
        assertThat(planner.needsPlacement(List.of())).isTrue();
        assertThat(planner.needsPlacement(List.of(type(InquiryType.HOW_TO)))).isTrue();
        assertThat(planner.needsPlacement(List.of(type(InquiryType.HOW_TO), unknown("system", QuestionKind.SYSTEM))))
                .isTrue();
        // 機能の設定がないシステムなら、置き場所はそろっている
        assertThat(planner.needsPlacement(List.of(type(InquiryType.HOW_TO), system("reports"))))
                .isFalse();
        assertThat(planner.needsPlacement(List.of(type(InquiryType.HOW_TO), system("billing"))))
                .isTrue();
        assertThat(planner.needsPlacement(
                        List.of(type(InquiryType.HOW_TO), system("billing"), function("invoice"))))
                .isFalse();
    }

    // ---- 置き場所の導出 ----

    @Test
    void 置き場所は答えから導き_選んだ人は答えの種類で決まる() {
        List<IntakeEntry> entries = List.of(
                type(InquiryType.HOW_TO),
                new IntakeEntry("system", QuestionKind.SYSTEM, "s", IntakeAnswer.inferred("billing", "請求管理")),
                unknown("function", QuestionKind.FUNCTION));

        assertThat(IntakeEntries.placement(entries)).isEqualTo(new Placement(InquiryType.HOW_TO, "billing", null));
        assertThat(IntakeEntries.chosen(entries)).isEqualTo(new Placement(InquiryType.HOW_TO, null, null));
        assertThat(IntakeEntries.placedBy(entries, QuestionKind.TYPE)).isEqualTo(PlacedBy.ASKER);
        assertThat(IntakeEntries.placedBy(entries, QuestionKind.SYSTEM)).isEqualTo(PlacedBy.AI);
        assertThat(IntakeEntries.placedBy(entries, QuestionKind.FUNCTION)).isNull();
    }

    // ---- 設定の質問の答えの読み取り ----

    private static final List<IntakeEntry> LOOKS_WRONG_PLACED = List.of(
            type(InquiryType.LOOKS_WRONG), system("job-admin"), unknown("function", QuestionKind.FUNCTION));

    private static IntakeSnapshot withText(List<IntakeEntry> entries, List<String> attempted, Urgency urgency) {
        return new IntakeSnapshot(entries, null, true, urgency, attempted);
    }

    @Test
    void 文章があり設定の質問があれば_まず答えの読み取りを頼む() {
        IntakeNext next = planner.next(withText(LOOKS_WRONG_PLACED, List.of(), Urgency.NORMAL));

        assertThat(next).isInstanceOf(IntakeNext.NeedsPrefill.class);
        assertThat(((IntakeNext.NeedsPrefill) next).questions()).containsExactly(SINCE);
    }

    @Test
    void 文章がなければ答えの読み取りを頼まず_そのまま聞く() {
        assertThat(asked(snapshot(LOOKS_WRONG_PLACED)).id()).isEqualTo("since");
    }

    @Test
    void 緊急度が止まっているときは読み取りも行わない() {
        IntakeNext next = planner.next(withText(LOOKS_WRONG_PLACED, List.of(), Urgency.BLOCKED));

        assertThat(next).isInstanceOf(IntakeNext.Complete.class);
    }

    @Test
    void 読み取りを済ませた質問は聞き_答え終えた質問は読み取らない() {
        // 読み取りを済ませ、答えが採れなかった質問は聞く
        IntakeNext attempted = planner.next(withText(LOOKS_WRONG_PLACED, List.of("since"), Urgency.NORMAL));
        assertThat(attempted).isInstanceOf(IntakeNext.Ask.class);
        assertThat(((IntakeNext.Ask) attempted).question().id()).isEqualTo("since");
        // 答え終えていれば、読み取りの対象にしない
        List<IntakeEntry> answered = new ArrayList<>(LOOKS_WRONG_PLACED);
        answered.add(answered(SINCE, "today"));
        assertThat(planner.next(withText(answered, List.of(), Urgency.NORMAL))).isInstanceOf(IntakeNext.Complete.class);
    }

    @Test
    void 設定に質問がなければ読み取りは頼まず聞き手の質問に進む() {
        IntakeNext next = planner.next(withText(List.of(type(InquiryType.HOW_TO), system("reports")), List.of(), Urgency.NORMAL));

        assertThat(next).isInstanceOf(IntakeNext.NeedsInterviewer.class);
    }

    @Test
    void 読み取った選択肢は_その質問の選択肢にあるものだけ採る() {
        List<IntakeEntry> taken = planner.acceptPrefill(
                List.of(),
                List.of(SINCE),
                "今日から出ません",
                new Prefill(List.of(new Prefill.Answer("since", "today", null))));
        List<IntakeEntry> notInOptions = planner.acceptPrefill(
                List.of(), List.of(SINCE), "x", new Prefill(List.of(new Prefill.Answer("since", "yesterday", null))));
        List<IntakeEntry> missing = planner.acceptPrefill(
                List.of(), List.of(SINCE), "x", new Prefill(List.of(new Prefill.Answer("since", null, "今日から"))));

        assertThat(taken).singleElement().satisfies(e -> {
            assertThat(e.questionId()).isEqualTo("since");
            assertThat(e.kind()).isEqualTo(QuestionKind.DETAIL);
            assertThat(e.text()).isEqualTo("いつから");
            assertThat(e.answer()).isEqualTo(IntakeAnswer.inferred("today", "今日から"));
        });
        assertThat(notInOptions).isEmpty();
        assertThat(missing).isEmpty();
    }

    @Test
    void 設定にない質問の答えは捨て_同じ質問の答えは先のものだけ採る() {
        List<IntakeEntry> taken = planner.acceptPrefill(
                List.of(),
                List.of(SINCE),
                "今日から",
                new Prefill(List.of(
                        new Prefill.Answer("nothing", "today", null),
                        new Prefill.Answer("since", "earlier", null),
                        new Prefill.Answer("since", "today", null))));

        assertThat(taken).singleElement().satisfies(e -> assertThat(e.answer().optionId()).isEqualTo("earlier"));
        assertThat(planner.acceptPrefill(
                        List.of(),
                        List.of(SINCE),
                        "今日から",
                        new Prefill(List.of(new Prefill.Answer("nothing", "today", null)))))
                .isEmpty();
    }

    @Test
    void 文章で答える質問は_本人の文章にそのまま含まれる答えだけ採り_空白の違いは無視する() {
        Prefill inText = new Prefill(List.of(new Prefill.Answer("partner", null, "A社")));
        Prefill madeUp = new Prefill(List.of(new Prefill.Answer("partner", null, "B社")));
        Prefill spaced = new Prefill(List.of(new Prefill.Answer("partner", null, "株式会社 A")));
        Prefill blank = new Prefill(List.of(new Prefill.Answer("partner", null, "  ")));
        Prefill optionOnly = new Prefill(List.of(new Prefill.Answer("partner", "x", null)));

        List<IntakeEntry> taken = planner.acceptPrefill(List.of(), List.of(PARTNER), "A社の請求書を直して", inText);
        assertThat(taken).singleElement().satisfies(e -> assertThat(e.answer()).isEqualTo(IntakeAnswer.inferredText("A社")));
        assertThat(planner.acceptPrefill(List.of(), List.of(PARTNER), "A社の請求書を直して", madeUp)).isEmpty();
        // 文章の側の空白（全角を含む）と、答えの側の空白が違っても、同じ並びなら採る
        assertThat(planner.acceptPrefill(List.of(), List.of(PARTNER), "株式会社　A　の請求書", spaced)).hasSize(1);
        assertThat(planner.acceptPrefill(List.of(), List.of(PARTNER), "A社", blank)).isEmpty();
        assertThat(planner.acceptPrefill(List.of(), List.of(PARTNER), "A社", optionOnly)).isEmpty();
        assertThat(planner.acceptPrefill(List.of(), List.of(PARTNER), "", inText)).isEmpty();
    }

    @Test
    void 読み取りの結果は読み取りを頼んだ質問の順で_答え終えた質問は採らない() {
        IntakeQuestion second = detail("second", "2問目", "a", "A");
        Prefill prefill = new Prefill(List.of(
                new Prefill.Answer("second", "a", null), new Prefill.Answer("since", "today", null)));

        List<IntakeEntry> both = planner.acceptPrefill(List.of(), List.of(SINCE, second), "x", prefill);
        List<IntakeEntry> skipsAnswered = planner.acceptPrefill(
                List.of(answered(SINCE, "earlier")), List.of(SINCE, second), "x", prefill);

        assertThat(both).extracting(IntakeEntry::questionId).containsExactly("since", "second");
        assertThat(skipsAnswered).extracting(IntakeEntry::questionId).containsExactly("second");
    }

    @Test
    void 読み取った答えは通常の答えと同じく_戻って答え直せる() {
        List<IntakeEntry> entries = new ArrayList<>(LOOKS_WRONG_PLACED);
        entries.add(new IntakeEntry("since", QuestionKind.DETAIL, "いつから", IntakeAnswer.inferred("today", "今日から")));

        IntakePlanner.Back back = planner.back(entries, "since");

        assertThat(back.entries()).hasSize(3);
        // 読み取りを済ませてあれば、戻った質問は聞かれる
        IntakeNext next = planner.next(withText(back.entries(), List.of("since"), Urgency.NORMAL));
        assertThat(((IntakeNext.Ask) next).question().id()).isEqualTo("since");
    }
}
