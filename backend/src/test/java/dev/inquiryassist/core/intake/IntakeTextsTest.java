package dev.inquiryassist.core.intake;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.InquiryType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 入口の決まった質問の文言を、言語ごとに1か所で持つこと（契約書「言語」）を確認する。 */
class IntakeTextsTest {

    @Test
    void 日本語は従来の文言() {
        IntakeTexts ja = IntakeTexts.of("ja");
        assertThat(ja.type()).isEqualTo("何をしたいですか");
        assertThat(ja.system()).isEqualTo("どのシステムですか");
        assertThat(ja.function()).isEqualTo("どの機能ですか");
        assertThat(ja.note()).isEqualTo("補足があれば書いてください");
        assertThat(ja.noteRequired()).isEqualTo("状況を一言で教えてください");
        assertThat(ja.noContent()).isEqualTo("（内容なし）");
    }

    @Test
    void 英語は英語の文言でenで始まる言語タグも英語() {
        assertThat(IntakeTexts.of("en")).isEqualTo(IntakeTexts.EN);
        assertThat(IntakeTexts.of("en-US")).isEqualTo(IntakeTexts.EN);
        assertThat(IntakeTexts.EN.type()).isEqualTo("What can we help you with?");
        assertThat(IntakeTexts.EN.noContent()).isEqualTo("(No content)");
    }

    @Test
    void 言語を決められなければ日本語() {
        assertThat(IntakeTexts.of(null)).isEqualTo(IntakeTexts.JA);
        assertThat(IntakeTexts.of("fr")).isEqualTo(IntakeTexts.JA);
    }

    @Test
    void 書かずには進めない補足の質問を保存した文言から見分ける() {
        assertThat(IntakeTexts.isNoteRequired(IntakeTexts.JA.noteRequired())).isTrue();
        assertThat(IntakeTexts.isNoteRequired(IntakeTexts.EN.noteRequired())).isTrue();
        assertThat(IntakeTexts.isNoteRequired(IntakeTexts.JA.note())).isFalse();
        assertThat(IntakeTexts.isNoteRequired(IntakeTexts.EN.note())).isFalse();
    }

    @Test
    void 質問を決める処理は渡した言語の文言で質問を作る() {
        IntakeCatalog catalog = new IntakeCatalog(
                List.of(new IntakeCatalog.TypeInfo(InquiryType.HOW_TO, "使い方", "")), List.of(), List.of(), List.of());
        IntakeSnapshot empty = new IntakeSnapshot(List.of(), null, false, null);
        IntakeNext ja = new IntakePlanner(catalog, IntakeTexts.JA).next(empty);
        IntakeNext en = new IntakePlanner(catalog, IntakeTexts.EN).next(empty);
        assertThat(((IntakeNext.Ask) ja).question().text()).isEqualTo("何をしたいですか");
        assertThat(((IntakeNext.Ask) en).question().text()).isEqualTo("What can we help you with?");
        // 1つ目の引数だけの作り方は、日本語の文言
        assertThat(((IntakeNext.Ask) new IntakePlanner(catalog).next(empty)).question().text())
                .isEqualTo("何をしたいですか");
    }
}
