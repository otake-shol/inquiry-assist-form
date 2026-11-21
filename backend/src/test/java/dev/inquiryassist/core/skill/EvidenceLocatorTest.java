package dev.inquiryassist.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.Citation;
import org.junit.jupiter.api.Test;

/** 引用から行番号を求める処理を確認する。 */
class EvidenceLocatorTest {

    private static EvidenceLocator withFile() {
        EvidenceLocator locator = new EvidenceLocator();
        locator.remember(Citation.SourceType.FAQ, "faq", "room.md", 4, "会議室は、利用日の30日前から予約できます。", null);
        locator.remember(null, "faq", "room.md", 5, "  予約の取り消しは、前日の17時まで受け付けます。", "rev1");
        locator.remember(null, "faq", "room.md", 9, "延長は、次の予約がない場合に限り認めます。", "rev1");
        return locator;
    }

    @Test
    void 見た行にある引用は行番号と種類と版つきの出典になる() {
        Citation citation = withFile().locate("faq", "room.md", "利用日の30日前から予約できます").orElseThrow();

        assertThat(citation.startLine()).isEqualTo(4);
        assertThat(citation.endLine()).isEqualTo(4);
        assertThat(citation.sourceType()).isEqualTo(Citation.SourceType.FAQ);
        assertThat(citation.revision()).isEqualTo("rev1");
        assertThat(citation.quote()).isEqualTo("利用日の30日前から予約できます");
    }

    @Test
    void 空白や改行の違いは無視し_続いた行にまたがる引用は行の範囲になる() {
        Citation citation = withFile()
                .locate("faq", "room.md", "予約できます。\n予約の取り消しは、 前日の17時まで")
                .orElseThrow();

        assertThat(citation.startLine()).isEqualTo(4);
        assertThat(citation.endLine()).isEqualTo(5);
    }

    @Test
    void 行が飛んでいるところをまたぐ引用は見つからない扱いになり1行目を指す() {
        Citation citation = withFile()
                .locate("faq", "room.md", "前日の17時まで受け付けます。延長は、次の予約がない場合")
                .orElseThrow();

        assertThat(citation.startLine()).isEqualTo(1);
        assertThat(citation.endLine()).isEqualTo(1);
    }

    @Test
    void 見ていないファイルや見ていない文の引用も出典にはなるが1行目を指す() {
        EvidenceLocator locator = withFile();

        Citation unseenFile = locator.locate("faq", "other.md", "会議室は、利用日の30日前から").orElseThrow();
        Citation unseenText = locator.locate("faq", "room.md", "会議室は60日前から予約できます").orElseThrow();

        assertThat(unseenFile.startLine()).isEqualTo(1);
        assertThat(unseenText.startLine()).isEqualTo(1);
        assertThat(unseenText.endLine()).isEqualTo(1);
    }

    @Test
    void 短すぎる引用と_場所や引用が空のものは出典にしない() {
        EvidenceLocator locator = withFile();

        assertThat(locator.locate("faq", "room.md", "会議室は、")).isEmpty();
        assertThat(locator.locate("faq", "room.md", "  ")).isEmpty();
        assertThat(locator.locate("faq", "room.md", null)).isEmpty();
        assertThat(locator.locate("faq", " ", "会議室は、利用日の30日前から")).isEmpty();
        assertThat(locator.locate(null, "room.md", "会議室は、利用日の30日前から")).isEmpty();
    }

    @Test
    void 種類が分からない資料の集まりはDOCUMENTとして扱う() {
        EvidenceLocator locator = new EvidenceLocator();
        locator.remember(null, "wiki", "a.md", 1, "交通費の精算は月末に締めます。", null);

        assertThat(locator.locate("wiki", "a.md", "交通費の精算は月末に").orElseThrow().sourceType())
                .isEqualTo(Citation.SourceType.DOCUMENT);
    }
}
