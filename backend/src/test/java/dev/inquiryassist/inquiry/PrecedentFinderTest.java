package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 同じ置き場所の先例を引く決まりのうち、並べ方と件数と、引かない場合を、DB なしで確かめる（引く条件そのものは、API のテスト）。 */
class PrecedentFinderTest {

    private final PrecedentRepository repository = mock(PrecedentRepository.class);
    private final PrecedentFinder finder = new PrecedentFinder(repository);

    private static PrecedentEntity precedent(String functionId, String decision) {
        PrecedentEntity p = new PrecedentEntity(
                UUID.randomUUID(),
                InquiryType.HOW_TO,
                "s",
                functionId,
                "状況",
                DecisionKind.AS_INTENDED,
                decision,
                "理由",
                "持ち主",
                "ja",
                Instant.now());
        return p;
    }

    private void returns(PrecedentEntity... newestFirst) {
        when(repository.findSamePlace(any(), any(), any(), any())).thenReturn(List.of(newestFirst));
    }

    @Test
    void 機能が同じ先例を先に_その中も後ろも新しい順で_5件まで() {
        // 新しい順に並んで返ってくる（a1 が最新）
        PrecedentEntity a1 = precedent("other", "a1");
        PrecedentEntity a2 = precedent("same", "a2");
        PrecedentEntity a3 = precedent(null, "a3");
        PrecedentEntity a4 = precedent("same", "a4");
        PrecedentEntity a5 = precedent("other", "a5");
        PrecedentEntity a6 = precedent("same", "a6");
        PrecedentEntity a7 = precedent("same", "a7");
        returns(a1, a2, a3, a4, a5, a6, a7);

        List<PrecedentEntity> found = finder.find(UUID.randomUUID(), new Placement(InquiryType.HOW_TO, "s", "same"));

        assertThat(found).extracting(p -> p.decision).containsExactly("a2", "a4", "a6", "a7", "a1");
    }

    @Test
    void 機能が決まっていない問い合わせでは_機能が決まっていない先例が先() {
        PrecedentEntity withFunction = precedent("f", "withFunction");
        PrecedentEntity without = precedent(null, "without");
        returns(withFunction, without);

        assertThat(finder.find(UUID.randomUUID(), new Placement(InquiryType.HOW_TO, "s", null)))
                .extracting(p -> p.decision)
                .containsExactly("without", "withFunction");
    }

    @Test
    void システムか種類が決まっていなければ引かずに空() {
        assertThat(finder.find(UUID.randomUUID(), new Placement(InquiryType.HOW_TO, null, null))).isEmpty();
        assertThat(finder.find(UUID.randomUUID(), new Placement(null, "s", "f"))).isEmpty();
        assertThat(finder.find(UUID.randomUUID(), Placement.NONE)).isEmpty();
        verify(repository, never()).findSamePlace(any(), any(), any(), any());
    }

    @Test
    void 先例がなければ空() {
        returns();

        assertThat(finder.find(UUID.randomUUID(), new Placement(InquiryType.HOW_TO, "s", null))).isEmpty();
    }

    @Test
    void 引く件数の上限は5件() {
        List<PrecedentEntity> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(precedent("f", "p" + i));
        }
        when(repository.findSamePlace(any(), any(), any(), any())).thenReturn(many);

        assertThat(finder.find(UUID.randomUUID(), new Placement(InquiryType.HOW_TO, "s", "f"))).hasSize(5);
        assertThat(PrecedentFinder.LIMIT).isEqualTo(5);
    }
}
