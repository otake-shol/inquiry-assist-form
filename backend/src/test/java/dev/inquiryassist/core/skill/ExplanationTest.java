package dev.inquiryassist.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 説明の出力の型の不変条件（{@code docs/skill-architecture.md} の型の表）を確かめる。 */
class ExplanationTest {

    @Test
    void 手順は空でないactionを持ち_checkはnullでもよい() {
        assertThat(new Explanation.Step("編集を開く", null).check()).isNull();
        assertThat(new Explanation.Step("編集を開く", "画面が開く").check()).isEqualTo("画面が開く");
        assertThatThrownBy(() -> new Explanation.Step(" ", "確認")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Explanation.Step(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 手順はnullなら空の一覧_10個までで_並びを保つ() {
        assertThat(new Explanation("説明", true, null).steps()).isEmpty();

        List<Explanation.Step> ten = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ten.add(new Explanation.Step("手順" + i, null));
        }
        assertThat(new Explanation("説明", true, ten).steps()).isEqualTo(ten);

        ten.add(new Explanation.Step("手順11", null));
        assertThatThrownBy(() -> new Explanation("説明", true, ten)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 二引数の作り方は手順なし_説明の文章は空でない() {
        assertThat(new Explanation("説明", false).steps()).isEmpty();
        assertThat(new Explanation("説明", false).answersQuestion()).isFalse();
        assertThatThrownBy(() -> new Explanation(" ", true, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 手順の一覧は後から変えられない() {
        List<Explanation.Step> mutable = new ArrayList<>(List.of(new Explanation.Step("a", null)));
        Explanation explanation = new Explanation("説明", true, mutable);
        mutable.add(new Explanation.Step("b", null));

        assertThat(explanation.steps()).hasSize(1);
        assertThatThrownBy(() -> explanation.steps().add(new Explanation.Step("c", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
