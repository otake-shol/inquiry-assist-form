package dev.inquiryassist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 追加質問の個数と、障害で質問を出さない制約を確かめる。 */
class TriageResultTest {

    private static List<FollowUpQuestion> questions(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new FollowUpQuestion("q" + i, "質問" + i, List.of("はい", "いいえ")))
                .toList();
    }

    @Test
    void 追加質問は上限まで持てる() {
        TriageResult result =
                new TriageResult(InquiryCategory.SPEC_QUESTION, questions(TriageResult.MAX_QUESTIONS));

        assertEquals(TriageResult.MAX_QUESTIONS, result.questions().size());
    }

    @Test
    void 追加質問が上限を超えると作れない() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TriageResult(
                                InquiryCategory.SPEC_QUESTION,
                                questions(TriageResult.MAX_QUESTIONS + 1)));
    }

    @Test
    void 障害では追加質問を持てない() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TriageResult(InquiryCategory.INCIDENT, questions(1)));
    }
}
