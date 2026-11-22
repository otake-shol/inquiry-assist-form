package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.skill.Skill;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 設定から、言語ごとの名前を既定の言語に解決した一覧（{@link IntakeCatalog}）を作る規則と、プロンプトの版の既定を確認する。 */
class InquiryPropertiesTest {

    private static Map<String, String> names(String... languageAndName) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < languageAndName.length; i += 2) {
            map.put(languageAndName[i], languageAndName[i + 1]);
        }
        return map;
    }

    private static InquiryProperties properties(
            List<String> languages,
            List<InquiryProperties.NamedEntry> types,
            List<InquiryProperties.NamedEntry> urgencies,
            List<InquiryProperties.SystemEntry> systems,
            List<InquiryProperties.CellEntry> cells,
            Map<String, InquiryProperties.SkillSettings> skills) {
        return new InquiryProperties(
                languages,
                types,
                urgencies,
                systems,
                cells,
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                skills,
                new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("利用者", "担当者"),
                java.util.Map.of());
    }

    private static InquiryProperties withCatalog(
            List<String> languages,
            List<InquiryProperties.SystemEntry> systems,
            List<InquiryProperties.CellEntry> cells) {
        return properties(
                languages,
                List.of(new InquiryProperties.NamedEntry("HOW_TO", names("ja", "使い方", "en", "How to"), names("ja", "手順"))),
                List.of(new InquiryProperties.NamedEntry("HURRY", names("ja", "急ぎ", "en", "Urgent"), Map.of())),
                systems,
                cells,
                Map.of());
    }

    @Test
    void 名前は既定の言語のものを使い_なければ最初に書かれた名前_それもなければ代わりの値() {
        InquiryProperties ja = withCatalog(List.of("ja", "en"), List.of(), List.of());
        InquiryProperties en = withCatalog(List.of("en", "ja"), List.of(), List.of());

        assertThat(ja.defaultLanguage()).isEqualTo("ja");
        assertThat(en.defaultLanguage()).isEqualTo("en");
        assertThat(ja.nameOf(names("ja", "日本語", "en", "English"), "x")).isEqualTo("日本語");
        assertThat(en.nameOf(names("ja", "日本語", "en", "English"), "x")).isEqualTo("English");
        // 既定の言語の名前がなければ、書かれた最初の名前
        assertThat(en.nameOf(names("ja", "日本語"), "x")).isEqualTo("日本語");
        assertThat(ja.nameOf(names("ja", " "), "x")).isEqualTo("x");
        assertThat(ja.nameOf(Map.of(), "x")).isEqualTo("x");
        assertThat(ja.nameOf(null, "x")).isEqualTo("x");
    }

    @Test
    void 言語を省くとjaが既定() {
        assertThat(withCatalog(List.of(), List.of(), List.of()).defaultLanguage()).isEqualTo("ja");
    }

    @Test
    void 種類と緊急度は5つと3つがそろい_書かれていないものは識別子を名前にする() {
        IntakeCatalog catalog = withCatalog(List.of("ja"), List.of(), List.of()).catalog();

        assertThat(catalog.types()).extracting(IntakeCatalog.TypeInfo::type).containsExactly(InquiryType.values());
        assertThat(catalog.typeLabel(InquiryType.HOW_TO)).isEqualTo("使い方");
        assertThat(catalog.types().get(0).hint()).isEqualTo("手順");
        assertThat(catalog.typeLabel(InquiryType.CHANGE_REQUEST)).isEqualTo("CHANGE_REQUEST");
        assertThat(catalog.types().get(4).hint()).isEmpty();
        assertThat(catalog.urgencies()).extracting(IntakeCatalog.UrgencyInfo::urgency).containsExactly(Urgency.values());
        assertThat(catalog.urgencyLabel(Urgency.HURRY)).isEqualTo("急ぎ");
        assertThat(catalog.urgencyLabel(Urgency.BLOCKED)).isEqualTo("BLOCKED");
    }

    @Test
    void システムと機能とマスの設定は既定の言語の名前になり_省いた項目はnullで質問は設定の順() {
        List<InquiryProperties.SystemEntry> systems = List.of(
                new InquiryProperties.SystemEntry(
                        "a",
                        names("ja", "システムA", "en", "System A"),
                        List.of(new InquiryProperties.FunctionEntry("a-1", names("ja", "機能A-1", "en", "Function A-1"))),
                        null),
                new InquiryProperties.SystemEntry("b", names("ja", "システムB"), List.of(), null));
        List<InquiryProperties.CellEntry> cells = List.of(
                new InquiryProperties.CellEntry(
                        "a",
                        "",
                        InquiryType.LOOKS_WRONG,
                        List.of(
                                new InquiryProperties.QuestionEntry(
                                        "since",
                                        names("ja", "いつから", "en", "Since when"),
                                        List.of(
                                                new InquiryProperties.OptionEntry("today", names("ja", "今日", "en", "Today")),
                                                new InquiryProperties.OptionEntry("earlier", names("ja", "前から")))),
                                new InquiryProperties.QuestionEntry("partner", names("ja", "取引先"), List.of()))));

        IntakeCatalog catalog = withCatalog(List.of("ja", "en"), systems, cells).catalog();

        assertThat(catalog.systems()).extracting(IntakeCatalog.SystemInfo::label).containsExactly("システムA", "システムB");
        assertThat(catalog.systems().get(0).aliases()).containsExactly("システムA", "System A");
        // ほかの言語の名前も、文章から読み取るために持つ
        assertThat(catalog.systems().get(0).functions())
                .containsExactly(new Choice("a-1", "機能A-1", List.of("機能A-1", "Function A-1")));
        assertThat(catalog.systems().get(1).functions()).isEmpty();
        IntakeCatalog.Cell cell = catalog.cells().get(0);
        assertThat(cell.system()).isEqualTo("a");
        assertThat(cell.function()).isNull();
        assertThat(cell.type()).isEqualTo(InquiryType.LOOKS_WRONG);
        assertThat(cell.specificity()).isEqualTo(2);
        assertThat(cell.questions()).extracting(q -> q.id()).containsExactly("since", "partner");
        assertThat(cell.questions().get(0).text()).isEqualTo("いつから");
        assertThat(cell.questions().get(0).options())
                .containsExactly(
                        new Choice("today", "今日", List.of("今日", "Today")), new Choice("earlier", "前から", List.of("前から")));
        assertThat(cell.questions().get(1).options()).isEmpty();
        assertThat(cell.questions().get(1).allowsText()).isTrue();
    }

    @Test
    void 指定した言語の名前で一覧を作り_その言語の名前がない項目は項目ごとに既定の言語に落ちる() {
        List<InquiryProperties.SystemEntry> systems = List.of(
                new InquiryProperties.SystemEntry("a", names("ja", "システムA", "en", "System A"), List.of(), null),
                new InquiryProperties.SystemEntry("b", names("ja", "システムB"), List.of(), null));

        IntakeCatalog en = withCatalog(List.of("ja", "en"), systems, List.of()).catalog("en");

        assertThat(en.systems()).extracting(IntakeCatalog.SystemInfo::label).containsExactly("System A", "システムB");
        assertThat(en.typeLabel(InquiryType.HOW_TO)).isEqualTo("How to");
        assertThat(en.urgencyLabel(Urgency.HURRY)).isEqualTo("Urgent");
        // 名前のない言語は、既定の言語
        assertThat(withCatalog(List.of("ja", "en"), systems, List.of()).catalog("fr").systems().get(0).label())
                .isEqualTo("システムA");
    }

    @Test
    void プロンプトの版の既定は受付_聞き手_言い直し_説明がv2で調査がv1_設定があればそれを使う() {
        InquiryProperties defaults = withCatalog(List.of("ja"), List.of(), List.of());

        assertThat(defaults.skill(Skill.RECEPTION).promptVersion()).isEqualTo("v2");
        assertThat(defaults.skill(Skill.INTERVIEWER).promptVersion()).isEqualTo("v2");
        assertThat(defaults.skill(Skill.RESTATER).promptVersion()).isEqualTo("v2");
        assertThat(defaults.skill(Skill.EXPLAINER).promptVersion()).isEqualTo("v2");
        assertThat(defaults.skill(Skill.INVESTIGATOR).promptVersion()).isEqualTo("v1");

        InquiryProperties configured = properties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "reception",
                        new InquiryProperties.SkillSettings("ollama", "m", 0, false, Duration.ofSeconds(5), null),
                        "restater",
                        new InquiryProperties.SkillSettings("ollama", "m", 0, false, Duration.ofSeconds(5), "v3")));
        assertThat(configured.skill(Skill.RECEPTION).promptVersion()).isEqualTo("v2");
        assertThat(configured.skill(Skill.RECEPTION).provider()).isEqualTo("ollama");
        assertThat(configured.skill(Skill.RESTATER).promptVersion()).isEqualTo("v3");
    }
}
