package dev.inquiryassist.core.skill;

import static dev.inquiryassist.core.skill.SkillTestSupport.SCOPE;
import static dev.inquiryassist.core.skill.SkillTestSupport.citation;
import static dev.inquiryassist.core.skill.SkillTestSupport.finding;
import static dev.inquiryassist.core.skill.SkillTestSupport.verifierOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.Citation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvestigationVerifierTest {

    private final InvestigationVerifier verifier =
            new InvestigationVerifier(verifierOf(Map.of("real-1", "return a;", "real-2", "return b;")));

    @Test
    void 根拠が一部通る事実は確かめられた事実になり根拠は通ったものだけになる() {
        Citation passed = citation("real-1", "return a;");
        Citation failed = citation("ghost", "return z;");
        var investigation = new Investigation(
                List.of(finding("事実A", passed, failed)), List.of("見つからない"), List.of("次の確認"));

        VerifiedInvestigation result = verifier.verify(investigation, SCOPE);

        assertThat(result.confirmed()).singleElement().satisfies(f -> {
            assertThat(f.statement()).isEqualTo("事実A");
            assertThat(f.evidence()).containsExactly(passed);
        });
        assertThat(result.unconfirmed()).isEmpty();
        assertThat(result.notFound()).containsExactly("見つからない");
        assertThat(result.nextChecks()).containsExactly("次の確認");
    }

    @Test
    void 根拠がすべて落ちる事実は確かめられなかった事実になる() {
        var investigation = new Investigation(
                List.of(finding("実在する事実", citation("real-1", "return a;")),
                        finding("幽霊の事実", citation("ghost", "return z;"), citation("ghost-2", "x"))),
                List.of(),
                List.of());

        VerifiedInvestigation result = verifier.verify(investigation, SCOPE);

        assertThat(result.confirmed()).extracting(Finding::statement).containsExactly("実在する事実");
        assertThat(result.unconfirmed()).extracting(Finding::statement).containsExactly("幽霊の事実");
        // 落ちた事実の元の根拠は、確かめられなかった事実にそのまま残る
        assertThat(result.unconfirmed().get(0).evidence()).hasSize(2);
    }

    @Test
    void 同じ出典が複数の事実に現れても照合結果は1件にまとまる() {
        Citation shared = citation("real-1", "return a;");
        var investigation = new Investigation(List.of(finding("A", shared), finding("B", shared)), List.of(), List.of());

        VerifiedInvestigation result = verifier.verify(investigation, SCOPE);

        assertThat(result.citationResults()).hasSize(1);
        assertThat(result.confirmed()).hasSize(2);
    }

    @Test
    void 事実がなければ空の結果を返す() {
        VerifiedInvestigation result = verifier.verify(new Investigation(List.of(), List.of("なし"), List.of()), SCOPE);

        assertThat(result.confirmed()).isEmpty();
        assertThat(result.unconfirmed()).isEmpty();
        assertThat(result.citationResults()).isEmpty();
    }
}
