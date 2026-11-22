package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.IntakeAnswer;
import dev.inquiryassist.core.intake.IntakeEntry;
import dev.inquiryassist.core.intake.PlacedBy;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.Phase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 記録から本人向けの表現と担当者向けの項目を作る規則（要約の決め方、置き場所の名前）を、Spring を起動せずに確認する。 */
class InquiryPresenterTest {

    private static Map<String, String> names(String ja, String en) {
        return en == null ? Map.of("ja", ja) : Map.of("ja", ja, "en", en);
    }

    private static InquiryPresenter presenter() {
        InquiryProperties properties = new InquiryProperties(
                List.of("ja", "en"),
                List.of(
                        new InquiryProperties.NamedEntry("HOW_TO", names("使い方を知りたい", "How to use something"), Map.of()),
                        // 英語の名前がない種類は、既定の言語（日本語）の名前に落ちる
                        new InquiryProperties.NamedEntry("LOOKS_WRONG", names("おかしいかもしれない", null), Map.of())),
                List.of(),
                List.of(new InquiryProperties.SystemEntry(
                        "job-admin",
                        names("求人管理画面", "Job admin"),
                        List.of(new InquiryProperties.FunctionEntry("posting", names("求人の掲載", "Posting"))),
                        null)),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of(),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("利用者", "担当者"),
                Map.of(),
                InquiryProperties.Rules.defaults(),
                List.of());
        return new InquiryPresenter(
                new IntakeLocales(properties), properties, SkillImplementations.from(new MockEnvironment()));
    }

    private static InquiryEntity entity() {
        return new InquiryEntity("u", Urgency.NORMAL, Instant.now());
    }

    private static IntakeEntry placed(QuestionKind kind, String id, String label, boolean byAi) {
        String questionId = kind.name().toLowerCase();
        return new IntakeEntry(
                questionId,
                kind,
                "q",
                byAi ? IntakeAnswer.inferred(id, label) : IntakeAnswer.option(id, label));
    }

    @Test
    void 要約は言い直しの文章を第一に使う() {
        InquiryEntity e = entity();
        e.texts.add("本人の文章");
        e.structuredByAi = new StructuredInquiry("言い直し", null, null, "目的", null, null);

        assertThat(presenter().summaryOf(e, "ja")).isEqualTo("言い直し");
    }

    @Test
    void 言い直しがなければ本人が書いた文章の先頭80文字を使い_補足も文章に含まれる() {
        InquiryEntity e = entity();
        e.texts.add("あ".repeat(100));

        assertThat(presenter().summaryOf(e, "ja")).isEqualTo("あ".repeat(80));

        InquiryEntity withNote = entity();
        withNote.intake.add(new IntakeEntry("note", QuestionKind.NOTE, "補足", IntakeAnswer.text("補足の文章")));
        assertThat(presenter().summaryOf(withNote, "ja")).isEqualTo("補足の文章");
    }

    @Test
    void 文章もなければ置き場所の名前を点でつないだものを使い_何もなければ内容なしにする() {
        InquiryEntity e = entity();
        e.intake.add(placed(QuestionKind.TYPE, "HOW_TO", "使い方", false));
        e.intake.add(placed(QuestionKind.SYSTEM, "job-admin", "求人管理画面", true));
        e.intake.add(placed(QuestionKind.FUNCTION, "posting", "求人の掲載", true));

        assertThat(presenter().summaryOf(e, "ja")).isEqualTo("使い方を知りたい・求人管理画面・求人の掲載");

        InquiryEntity onlyType = entity();
        onlyType.intake.add(placed(QuestionKind.TYPE, "LOOKS_WRONG", "x", false));
        assertThat(presenter().summaryOf(onlyType, "ja")).isEqualTo("おかしいかもしれない");

        assertThat(presenter().summaryOf(entity(), "ja")).isEqualTo("（内容なし）");
    }

    @Test
    void 置き場所の名前は保存した値ではなく設定から引き_設定にないものは識別子を名前にする() {
        InquiryEntity e = entity();
        e.intake.add(placed(QuestionKind.TYPE, "HOW_TO", "古い名前", false));
        e.intake.add(placed(QuestionKind.SYSTEM, "removed-system", "古い名前", true));

        InquiryView.PlacedView type = presenter().placed(e, QuestionKind.TYPE, "ja");
        InquiryView.PlacedView system = presenter().placed(e, QuestionKind.SYSTEM, "ja");

        assertThat(type).isEqualTo(new InquiryView.PlacedView("HOW_TO", "使い方を知りたい", PlacedBy.ASKER));
        assertThat(system).isEqualTo(new InquiryView.PlacedView("removed-system", "removed-system", PlacedBy.AI));
        assertThat(presenter().placed(e, QuestionKind.FUNCTION, "ja")).isNull();
    }

    @Test
    void わからないと答えた置き場所は決まっていないものとして返す() {
        InquiryEntity e = entity();
        e.intake.add(new IntakeEntry("type", QuestionKind.TYPE, "q", IntakeAnswer.unknown()));

        assertThat(presenter().placed(e, QuestionKind.TYPE, "ja")).isNull();
    }

    @Test
    void 入口の途中の表現は次の質問を持ち_入口を終えた後は次の質問がなく終わっている() {
        InquiryEntity e = entity();
        InquiryView intake = presenter().toView(e);

        assertThat(intake.intake().next().questionId()).isEqualTo("type");
        assertThat(intake.intake().complete()).isFalse();
        assertThat(intake.canSendNow()).isFalse();

        e.phase = Phase.CLOSED;
        e.turn = null;
        e.awaiting = null;
        e.ending = Ending.WITHDRAWN;
        InquiryView closed = presenter().toView(e);
        assertThat(closed.intake().next()).isNull();
        assertThat(closed.intake().complete()).isTrue();
        assertThat(closed.canSendNow()).isFalse();
    }

    @Test
    void 担当者向けの項目は種類_システム_機能と緊急度を持つ() {
        InquiryEntity e = entity();
        e.urgency = Urgency.BLOCKED;
        e.handedOverAt = Instant.now();
        e.intake.add(placed(QuestionKind.TYPE, "LOOKS_WRONG", "x", true));
        e.intake.add(placed(QuestionKind.SYSTEM, "job-admin", "x", false));
        e.intake.add(placed(QuestionKind.FUNCTION, "posting", "x", false));

        OperatorItem item = presenter().toItem(e, "ja");

        assertThat(item.type()).isEqualTo(new OperatorItem.Ref("LOOKS_WRONG", "おかしいかもしれない"));
        assertThat(item.system()).isEqualTo(new OperatorItem.Ref("job-admin", "求人管理画面"));
        assertThat(item.function()).isEqualTo(new OperatorItem.Ref("posting", "求人の掲載"));
        assertThat(item.urgency()).isEqualTo(Urgency.BLOCKED);
    }

    @Test
    void 見る人の言語で名前と要約の文言が切り替わり_英語の名前がなければ日本語に落ちる() {
        InquiryEntity e = entity();
        e.intake.add(placed(QuestionKind.TYPE, "HOW_TO", "x", false));
        e.intake.add(placed(QuestionKind.SYSTEM, "job-admin", "x", true));
        e.intake.add(placed(QuestionKind.FUNCTION, "posting", "x", true));

        assertThat(presenter().placed(e, QuestionKind.TYPE, "en").label()).isEqualTo("How to use something");
        assertThat(presenter().placed(e, QuestionKind.SYSTEM, "en").label()).isEqualTo("Job admin");
        assertThat(presenter().placed(e, QuestionKind.FUNCTION, "en").label()).isEqualTo("Posting");
        assertThat(presenter().summaryOf(e, "en")).isEqualTo("How to use something / Job admin / Posting");
        assertThat(presenter().summaryOf(entity(), "en")).isEqualTo("(No content)");

        InquiryEntity wrong = entity();
        wrong.intake.add(placed(QuestionKind.TYPE, "LOOKS_WRONG", "x", false));
        assertThat(presenter().placed(wrong, QuestionKind.TYPE, "en").label()).isEqualTo("おかしいかもしれない");
        // 設定にない言語は、既定の言語
        assertThat(presenter().placed(e, QuestionKind.TYPE, "fr").label()).isEqualTo("使い方を知りたい");
    }

    @Test
    void 入口の質問文と答えの名前も見る人の言語に引き直す() {
        InquiryEntity e = entity();
        e.intake.add(new IntakeEntry("type", QuestionKind.TYPE, "何をしたいですか", IntakeAnswer.option("HOW_TO", "使い方を知りたい")));
        e.intake.add(new IntakeEntry("system", QuestionKind.SYSTEM, "どのシステムですか", IntakeAnswer.unknown()));
        e.intake.add(new IntakeEntry("q1", QuestionKind.DETAIL, "どの場面での話ですか", IntakeAnswer.option("o1", "画面を開いたとき")));
        e.intake.add(new IntakeEntry("note", QuestionKind.NOTE, "状況を一言で教えてください", IntakeAnswer.text("書いた文章")));

        List<InquiryView.EntryView> ja = presenter().entries(e, "ja");
        List<InquiryView.EntryView> en = presenter().entries(e, "en");

        assertThat(ja.get(0).text()).isEqualTo("何をしたいですか");
        assertThat(en.get(0).text()).isEqualTo("What can we help you with?");
        assertThat(en.get(0).answer().label()).isEqualTo("How to use something");
        assertThat(en.get(1).text()).isEqualTo("Which system is it about?");
        assertThat(en.get(1).answer().label()).isNull();
        // 聞き手（AI）が作った質問は、書かれたときの言語のまま
        assertThat(en.get(2).text()).isEqualTo("どの場面での話ですか");
        assertThat(en.get(2).answer().label()).isEqualTo("画面を開いたとき");
        // 書かずには進めない補足の質問を見分けて、その言語の文言にする。本人が書いた文章は変わらない
        assertThat(en.get(3).text()).isEqualTo("Tell us briefly what is happening");
        assertThat(en.get(3).answer().text()).isEqualTo("書いた文章");
    }
}
