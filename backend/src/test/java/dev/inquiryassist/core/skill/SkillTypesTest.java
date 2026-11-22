package dev.inquiryassist.core.skill;

import static dev.inquiryassist.core.skill.SkillTestSupport.citation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.TechnicalMemo;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillTypesTest {

    private static FollowUpQuestion question(int n) {
        return new FollowUpQuestion("q" + n, "質問" + n, List.of());
    }

    @Test
    void Findingは根拠が1件以上ないと作れない() {
        assertThatThrownBy(() -> new Finding("事実", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding("事実", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(" ", List.of(citation("a", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Finding("事実", List.of(citation("a", "x"))).evidence()).hasSize(1);
    }

    @Test
    void Interviewは質問を最大2個まで持てる() {
        assertThat(new Interview(List.of(question(1), question(2))).questions()).hasSize(2);
        assertThatThrownBy(() -> new Interview(List.of(question(1), question(2), question(3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Interview.none().questions()).isEmpty();
    }

    @Test
    void Receptionはどの項目も読み取れなければnullにできる() {
        Reception none = new Reception(null, null, null);
        assertThat(none.type()).isNull();
        assertThat(none.systemId()).isNull();
        assertThat(none.functionId()).isNull();
    }

    private static final List<SystemOption> CANDIDATES = List.of(
            new SystemOption("a", "システムA", List.of(new dev.inquiryassist.core.FunctionOption("a-1", "機能A-1"))),
            new SystemOption("b", "システムB"));

    @Test
    void Receptionの正規化は本人が選んだ項目を変えない() {
        Placement decided = new Placement(InquiryType.HOW_TO, "a", null);

        Reception result = Reception.normalize(new Reception(InquiryType.LOOKS_WRONG, "b", "a-1"), CANDIDATES, decided);

        assertThat(result).isEqualTo(new Reception(InquiryType.HOW_TO, "a", "a-1"));
    }

    @Test
    void Receptionの正規化は候補にないidと決まったシステムの機能でない機能をnullにする() {
        assertThat(Reception.normalize(new Reception(null, "zzz", "a-1"), CANDIDATES, Placement.NONE))
                .isEqualTo(new Reception(null, null, null));
        assertThat(Reception.normalize(new Reception(null, "b", "a-1"), CANDIDATES, Placement.NONE))
                .isEqualTo(new Reception(null, "b", null));
        assertThat(Reception.normalize(new Reception(null, null, "a-1"), CANDIDATES, Placement.NONE).functionId())
                .isNull();
    }

    @Test
    void VerifiedInvestigationは確かめられた事実の根拠に照合を通っていない出典を許さない() {
        var passed = citation("ok", "a");
        var failed = citation("ng", "b");
        var results = List.of(new CitationVerifier.Result(passed, true), new CitationVerifier.Result(failed, false));

        var ok = new VerifiedInvestigation(
                List.of(new Finding("事実", List.of(passed))), List.of(), List.of(), List.of(), results);
        assertThat(ok.confirmed()).hasSize(1);
        assertThatThrownBy(() -> new VerifiedInvestigation(
                        List.of(new Finding("事実", List.of(passed, failed))), List.of(), List.of(), List.of(), results))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedInvestigation(
                        List.of(new Finding("事実", List.of(failed))), List.of(), List.of(), List.of(), results))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void TechnicalMemoのunconfirmedは省略すると空になる() {
        assertThat(new TechnicalMemo(List.of("a"), List.of("b"), List.of("c")).unconfirmed()).isEmpty();
        assertThat(new TechnicalMemo(null, null, null, null).unconfirmed()).isEmpty();
    }

    @Test
    void スキルの識別子は小文字の固定値() {
        assertThat(java.util.Arrays.stream(Skill.values()).map(Skill::id).toList())
                .containsExactly("reception", "interviewer", "restater", "investigator", "explainer", "advisor", "drafter", "translator");
    }
}
