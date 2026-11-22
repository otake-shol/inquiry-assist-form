package dev.inquiryassist.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.skill.Advice;
import dev.inquiryassist.core.skill.Draft;
import dev.inquiryassist.core.skill.DraftRequest;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 助言の出力を、通常のコードで確かめる決まりと、型の不変条件（{@code docs/skill-architecture.md}）を、Spring なしで確かめる。 */
class AdviceVerifierTest {

    private static Advice.Judgment judgment(Integer... basedOn) {
        return new Advice.Judgment("判断の案", false, null, List.of(basedOn));
    }

    private static Advice.Procedure procedure(Integer... basedOn) {
        return new Advice.Procedure(List.of(new Advice.Step("対象", "操作", "確かめ方")), "元に戻す", List.of(basedOn));
    }

    // ---- 確かめる ----

    @Test
    void 存在する番号だけを残し_存在しない番号を外す() {
        Advice verified = AdviceVerifier.verify(new Advice(judgment(1, 5, 2, 0, -1), procedure(3, 4)), 3);

        assertThat(verified.judgment().basedOn()).containsExactly(1, 2);
        assertThat(verified.procedure().basedOn()).containsExactly(3);
        // 番号以外の中身は変えない
        assertThat(verified.judgment().text()).isEqualTo("判断の案");
        assertThat(verified.procedure().steps()).hasSize(1);
        assertThat(verified.procedure().rollback()).isEqualTo("元に戻す");
    }

    @Test
    void 外した結果basedOnが空になった案は捨てる() {
        Advice verified = AdviceVerifier.verify(new Advice(judgment(999), procedure(1)), 2);

        assertThat(verified.judgment()).isNull();
        assertThat(verified.procedure()).isNotNull();
    }

    @Test
    void 片方だけ残る() {
        assertThat(AdviceVerifier.verify(new Advice(judgment(1), procedure(9)), 2).judgment()).isNotNull();
        assertThat(AdviceVerifier.verify(new Advice(judgment(1), procedure(9)), 2).procedure()).isNull();
        assertThat(AdviceVerifier.verify(new Advice(judgment(7), procedure(2)), 2).judgment()).isNull();
        assertThat(AdviceVerifier.verify(new Advice(judgment(7), procedure(2)), 2).procedure()).isNotNull();
    }

    @Test
    void 両方とも捨てられたら案は空() {
        Advice verified = AdviceVerifier.verify(new Advice(judgment(), procedure(0)), 3);

        assertThat(verified.judgment()).isNull();
        assertThat(verified.procedure()).isNull();
    }

    @Test
    void もともと案がなければそのまま() {
        Advice verified = AdviceVerifier.verify(new Advice(null, null), 3);

        assertThat(verified.judgment()).isNull();
        assertThat(verified.procedure()).isNull();
    }

    @Test
    void 重複した番号は1つにし_並びを保つ() {
        assertThat(AdviceVerifier.verify(new Advice(judgment(2, 1, 2, 1), null), 2).judgment().basedOn())
                .containsExactly(2, 1);
    }

    @Test
    void 事実が1つなら番号1だけが存在する() {
        assertThat(AdviceVerifier.verify(new Advice(judgment(1, 2), null), 1).judgment().basedOn()).containsExactly(1);
    }

    // ---- 型の不変条件 ----

    @Test
    void 判断の案の文章は空でない() {
        assertThatThrownBy(() -> new Advice.Judgment(" ", false, null, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Advice.Judgment(null, false, null, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        // basedOn が null でも、空として持つ（捨てるかどうかは、確かめる側が決める）
        assertThat(new Advice.Judgment("案", true, "理由", null).basedOn()).isEmpty();
    }

    @Test
    void 手順の案の手順は1件以上() {
        assertThatThrownBy(() -> new Advice.Procedure(List.of(), null, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Advice.Procedure(null, null, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Advice.Procedure(List.of(new Advice.Step("a", "b", "c")), null, null).basedOn()).isEmpty();
    }

    @Test
    void 下書きの文章は空でない() {
        assertThatThrownBy(() -> new Draft("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Draft(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Draft("本文").text()).isEqualTo("本文");
    }

    @Test
    void 下書きの依頼は種類が必須で_事実とやり取りは空にできる() {
        assertThatThrownBy(() -> new DraftRequest(null, null, null, null, null, null, null, "ja"))
                .isInstanceOf(IllegalArgumentException.class);
        DraftRequest request = new DraftRequest(ReplyKind.ASK, null, null, null, null, null, null, "ja");
        assertThat(request.confirmedFindings()).isEmpty();
        assertThat(request.messages()).isEmpty();
    }

    // ---- 先例 ----

    private static Advice.Judgment judgmentOn(List<Integer> facts, List<Integer> precedents) {
        return new Advice.Judgment("判断の案", false, null, facts, precedents);
    }

    private static Advice.Procedure procedureOn(List<Integer> facts, List<Integer> precedents) {
        return new Advice.Procedure(List.of(new Advice.Step("対象", "操作", "確かめ方")), null, facts, precedents);
    }

    @Test
    void 事実だけに基づく案と先例だけに基づく案はどちらも残る() {
        Advice verified = AdviceVerifier.verify(
                new Advice(judgmentOn(List.of(1), List.of()), procedureOn(List.of(), List.of(2))), 1, 2);

        assertThat(verified.judgment().basedOn()).containsExactly(1);
        assertThat(verified.judgment().basedOnPrecedents()).isEmpty();
        assertThat(verified.procedure().basedOn()).isEmpty();
        assertThat(verified.procedure().basedOnPrecedents()).containsExactly(2);
    }

    @Test
    void 事実と先例の番号は別々に確かめる() {
        Advice verified = AdviceVerifier.verify(new Advice(judgmentOn(List.of(1, 3), List.of(1, 2, 9)), null), 2, 2);

        assertThat(verified.judgment().basedOn()).containsExactly(1);
        assertThat(verified.judgment().basedOnPrecedents()).containsExactly(1, 2);
        // 先例を渡していなければ、先例の番号はどれも存在しない
        assertThat(AdviceVerifier.verify(new Advice(judgmentOn(List.of(1), List.of(1)), null), 1).judgment().basedOnPrecedents())
                .isEmpty();
    }

    @Test
    void 事実と先例の番号がどちらも存在しなければ案を捨てる_片方が残れば残る() {
        Advice neither = AdviceVerifier.verify(
                new Advice(judgmentOn(List.of(5), List.of(7)), procedureOn(List.of(0), List.of(8))), 2, 2);
        assertThat(neither.judgment()).isNull();
        assertThat(neither.procedure()).isNull();

        Advice factKept = AdviceVerifier.verify(new Advice(judgmentOn(List.of(1), List.of(9)), null), 1, 1);
        assertThat(factKept.judgment().basedOn()).containsExactly(1);
        assertThat(factKept.judgment().basedOnPrecedents()).isEmpty();
        Advice precedentKept = AdviceVerifier.verify(new Advice(judgmentOn(List.of(9), List.of(1)), null), 1, 1);
        assertThat(precedentKept.judgment().basedOn()).isEmpty();
        assertThat(precedentKept.judgment().basedOnPrecedents()).containsExactly(1);
    }

    @Test
    void 先例に対応する前の作り方は先例なしの案になる() {
        assertThat(new Advice.Judgment("案", false, null, List.of(1)).basedOnPrecedents()).isEmpty();
        assertThat(new Advice.Procedure(List.of(new Advice.Step("a", "b", "c")), null, List.of(1)).basedOnPrecedents())
                .isEmpty();
        assertThat(new Advice.Judgment("案", false, null, null, null).basedOnPrecedents()).isEmpty();
    }
}
