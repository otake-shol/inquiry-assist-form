package dev.inquiryassist.core.skill;

import static dev.inquiryassist.core.skill.SkillTestSupport.citation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.FollowUpQuestion;
import dev.inquiryassist.core.InquiryCategory;
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
    void Interviewは質問を最大3個まで持てる() {
        assertThat(new Interview(List.of(question(1), question(2), question(3))).questions()).hasSize(3);
        assertThatThrownBy(() -> new Interview(List.of(question(1), question(2), question(3), question(4))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Interview.none().questions()).isEmpty();
    }

    @Test
    void Receptionは種別が必須で対象システムの推測は任意() {
        assertThatThrownBy(() -> new Reception(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Reception(InquiryCategory.INCIDENT, null).guessedSystemId()).isNull();
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
                .containsExactly("reception", "interviewer", "restater", "investigator", "explainer");
    }
}
