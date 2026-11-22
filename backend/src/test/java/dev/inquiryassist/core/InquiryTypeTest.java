package dev.inquiryassist.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 種類ごとの経路（契約書「種類ごとの経路」の表）。経路表を変えるときは、このテストも直す。 */
class InquiryTypeTest {

    @Test
    void 調べないのは変えてほしいだけ() {
        assertThat(List.of(InquiryType.values()))
                .filteredOn(t -> !t.investigates())
                .containsExactly(InquiryType.CHANGE_REQUEST);
    }

    @Test
    void 使い方と仕組みは答えの後に本人が確かめる() {
        for (InquiryType type : List.of(InquiryType.HOW_TO, InquiryType.HOW_IT_WORKS)) {
            assertThat(type.filesAfterAnswer(true, true)).isFalse();
            assertThat(type.filesAfterAnswer(true, false)).isFalse();
            assertThat(type.filesAfterAnswer(false, false)).isFalse();
        }
    }

    @Test
    void おかしいかもしれないは答えにかかわらず担当者に送る() {
        assertThat(InquiryType.LOOKS_WRONG.filesAfterAnswer(true, true)).isTrue();
        assertThat(InquiryType.LOOKS_WRONG.filesAfterAnswer(false, false)).isTrue();
    }

    @Test
    void やってほしいは根拠があり問いに答えているときだけ本人が確かめる() {
        assertThat(InquiryType.DO_FOR_ME.filesAfterAnswer(true, true)).isFalse();
        assertThat(InquiryType.DO_FOR_ME.filesAfterAnswer(true, false)).isTrue();
        assertThat(InquiryType.DO_FOR_ME.filesAfterAnswer(false, false)).isTrue();
    }

    @Test
    void 変えてほしいだけが確定と同時に担当者に送る() {
        assertThat(List.of(InquiryType.values()))
                .filteredOn(InquiryType::filesOnConfirm)
                .containsExactly(InquiryType.CHANGE_REQUEST);
    }

    @Test
    void 種類が決まっていなければ仕組みを知りたいと同じ経路になる() {
        assertThat(InquiryType.routeOf(null)).isEqualTo(InquiryType.HOW_IT_WORKS);
        assertThat(InquiryType.routeOf(InquiryType.DO_FOR_ME)).isEqualTo(InquiryType.DO_FOR_ME);
    }
}
