package dev.inquiryassist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 種別ごとの経路が要件定義の経路表と一致することを確かめる。 */
class InquiryCategoryTest {

    @ParameterizedTest(name = "{0}: 0次回答={1}, 必ず起票={2}, 受付={3}")
    @CsvSource({
        "SPEC_QUESTION, true,  false, true",
        "SUSPECTED_BUG, true,  true,  true",
        "WORK_REQUEST,  false, true,  true",
        "INCIDENT,      false, false, false",
    })
    void 種別ごとの経路(
            InquiryCategory category, boolean zeroAnswer, boolean alwaysFiled, boolean accepted) {
        assertEquals(zeroAnswer, category.receivesZeroAnswer());
        assertEquals(alwaysFiled, category.alwaysFiled());
        assertEquals(accepted, category.acceptedByForm());
    }
}
