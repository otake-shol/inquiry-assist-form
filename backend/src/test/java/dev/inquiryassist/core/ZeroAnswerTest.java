package dev.inquiryassist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 出典のない回答が「根拠あり」として本人に届かないことを確かめる。 */
class ZeroAnswerTest {

    private static final TechnicalMemo MEMO =
            new TechnicalMemo(List.of("掲載期限は expiresAt で判定"), List.of(), List.of());

    private static Citation citation(String path) {
        return new Citation(Citation.SourceType.CODE, "job-board", path, 10, 12, "expiresAt", "abc123");
    }

    @Test
    void 根拠ありの回答は出典なしでは作れない() {
        assertThrows(
                IllegalArgumentException.class, () -> new ZeroAnswer(true, "説明", MEMO, List.of()));
    }

    @Test
    void 根拠ありの回答は本人向けの説明なしでは作れない() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ZeroAnswer(true, " ", MEMO, List.of(citation("A.java"))));
    }

    @Test
    void 照合できた出典だけが残る() {
        ZeroAnswer answer =
                new ZeroAnswer(true, "説明", MEMO, List.of(citation("A.java"), citation("Ghost.java")));

        ZeroAnswer verified = answer.withVerifiedCitations(List.of(citation("A.java")));

        assertTrue(verified.grounded());
        assertEquals(List.of(citation("A.java")), verified.citations());
        assertEquals("説明", verified.askerExplanation());
    }

    @Test
    void 照合できた出典がなければ根拠なしに落ち技術メモは残る() {
        ZeroAnswer answer = new ZeroAnswer(true, "説明", MEMO, List.of(citation("Ghost.java")));

        ZeroAnswer verified = answer.withVerifiedCitations(List.of());

        assertFalse(verified.grounded());
        assertNull(verified.askerExplanation());
        assertTrue(verified.citations().isEmpty());
        assertEquals(MEMO, verified.memo());
    }
}
