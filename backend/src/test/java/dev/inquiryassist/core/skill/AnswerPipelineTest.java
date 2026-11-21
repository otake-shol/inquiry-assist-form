package dev.inquiryassist.core.skill;

import static dev.inquiryassist.core.skill.SkillTestSupport.SCOPE;
import static dev.inquiryassist.core.skill.SkillTestSupport.citation;
import static dev.inquiryassist.core.skill.SkillTestSupport.finding;
import static dev.inquiryassist.core.skill.SkillTestSupport.verifierOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerPipelineTest {

    private static final StructuredInquiry INQUIRY =
            new StructuredInquiry("要約", InquiryCategory.SPEC_QUESTION, null, "目的", null, null, null);
    private static final Map<String, String> FILES = Map.of("real", "return a;");

    /** 決まった調査結果を返し、進捗を通知する偽の調査。 */
    private static InvestigatorSkill investigator(Investigation result, List<String> events) {
        return (inquiry, scope, progress) -> {
            events.add("investigate");
            progress.onProgress("調査の進捗");
            return new AiResult<>(result, new Usage("inv-model", "v1", 10, 5, 0, false));
        };
    }

    /** 受け取った引数を記録する偽の説明。 */
    private static class RecordingExplainer implements ExplainerSkill {
        final List<List<Finding>> received = new ArrayList<>();
        RuntimeException failure;
        boolean answers = true;

        @Override
        public AiResult<Explanation> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings) {
            received.add(confirmedFindings);
            if (failure != null) {
                throw failure;
            }
            return new AiResult<>(new Explanation("説明文", answers), new Usage("exp-model", "v2", 20, 8, 0, false));
        }
    }

    private AnswerPipeline pipeline(Investigation investigation, RecordingExplainer explainer, List<String> events) {
        return new AnswerPipeline(investigator(investigation, events), verifierOf(FILES), explainer);
    }

    private static Investigation mixed() {
        return new Investigation(
                List.of(
                        finding("確かめられる事実", citation("real", "return a;")),
                        finding("確かめられない事実", citation("ghost", "return z;"))),
                List.of("見つからなかった"),
                List.of("次の確認"));
    }

    @Test
    void 説明に渡るのは確かめられた事実だけで確かめられなかった事実は技術メモに残る() {
        var explainer = new RecordingExplainer();

        var result = pipeline(mixed(), explainer, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {});

        assertThat(explainer.received).singleElement().satisfies(received -> {
            assertThat(received).extracting(Finding::statement).containsExactly("確かめられる事実");
            assertThat(received.get(0).evidence()).containsExactly(citation("real", "return a;"));
        });
        assertThat(result.answer().grounded()).isTrue();
        assertThat(result.answer().askerExplanation()).isEqualTo("説明文");
        assertThat(result.answer().citations()).containsExactly(citation("real", "return a;"));
        assertThat(result.answer().memo().findings()).containsExactly("確かめられる事実");
        assertThat(result.answer().memo().unconfirmed()).containsExactly("確かめられない事実");
        assertThat(result.answer().memo().notFound()).containsExactly("見つからなかった");
        assertThat(result.answer().memo().nextChecks()).containsExactly("次の確認");
        assertThat(result.investigation().citationResults()).hasSize(2);
    }

    @Test
    void 確かめられた事実が0件なら説明を呼ばず根拠なしになり事実はunconfirmedに残る() {
        var explainer = new RecordingExplainer();
        var investigation = new Investigation(
                List.of(finding("幽霊の事実", citation("ghost", "x"))), List.of(), List.of("確認する"));
        List<String> progress = new ArrayList<>();

        var result = pipeline(investigation, explainer, new ArrayList<>()).run(INQUIRY, SCOPE, progress::add);

        assertThat(explainer.received).isEmpty();
        assertThat(result.answer().grounded()).isFalse();
        assertThat(result.answer().askerExplanation()).isNull();
        assertThat(result.answer().citations()).isEmpty();
        assertThat(result.answer().memo().unconfirmed()).containsExactly("幽霊の事実");
        assertThat(result.answer().memo().nextChecks()).containsExactly("確認する");
        assertThat(result.usages()).extracting(SkillUsage::skill).containsExactly(Skill.INVESTIGATOR);
        // 説明を呼ばないので「答えをまとめています」は流れない
        assertThat(progress).containsExactly("調査の進捗", AnswerPipeline.PROGRESS_VERIFYING);
    }

    @Test
    void 説明の判定がそのままansweredになる_根拠なしでは常にfalse() {
        var answering = new RecordingExplainer();
        var offTopic = new RecordingExplainer();
        offTopic.answers = false;

        assertThat(pipeline(mixed(), answering, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}).answer().answered()).isTrue();
        var notAnswered = pipeline(mixed(), offTopic, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}).answer();
        assertThat(notAnswered.grounded()).isTrue();
        assertThat(notAnswered.answered()).isFalse();
        assertThat(notAnswered.askerExplanation()).isEqualTo("説明文");

        var ghost = new Investigation(List.of(finding("幽霊", citation("ghost", "x"))), List.of(), List.of());
        var ungrounded = pipeline(ghost, answering, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}).answer();
        assertThat(ungrounded.grounded()).isFalse();
        assertThat(ungrounded.answered()).isFalse();
    }

    @Test
    void 事実が1件もなくても説明を呼ばない() {
        var explainer = new RecordingExplainer();

        var result = pipeline(new Investigation(List.of(), List.of("なし"), List.of()), explainer, new ArrayList<>())
                .run(INQUIRY, SCOPE, m -> {});

        assertThat(explainer.received).isEmpty();
        assertThat(result.answer().grounded()).isFalse();
    }

    @Test
    void 進捗は調査_照合_説明の順に流れる() {
        List<String> progress = new ArrayList<>();

        pipeline(mixed(), new RecordingExplainer(), new ArrayList<>()).run(INQUIRY, SCOPE, progress::add);

        assertThat(progress)
                .containsExactly("調査の進捗", "根拠を確かめています", "答えをまとめています");
    }

    @Test
    void スキルは調査_説明の順に呼ばれ利用量がスキルごとに記録される() {
        List<String> events = new ArrayList<>();
        var explainer = new RecordingExplainer() {
            @Override
            public AiResult<Explanation> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings) {
                events.add("explain");
                return super.explain(inquiry, confirmedFindings);
            }
        };

        var result = pipeline(mixed(), explainer, events).run(INQUIRY, SCOPE, m -> {});

        assertThat(events).containsExactly("investigate", "explain");
        assertThat(result.usages()).extracting(SkillUsage::skill).containsExactly(Skill.INVESTIGATOR, Skill.EXPLAINER);
        assertThat(result.usages().get(0).usage().model()).isEqualTo("inv-model");
        assertThat(result.usages().get(1).usage().model()).isEqualTo("exp-model");
        assertThat(result.usages()).noneMatch(SkillUsage::failed);
    }

    @Test
    void 調査と説明のそれぞれにかかった時間が記録される() {
        var result = pipeline(mixed(), new RecordingExplainer(), new ArrayList<>()).run(INQUIRY, SCOPE, m -> {});

        assertThat(result.usages()).extracting(SkillUsage::durationMillis).doesNotContainNull().allMatch(d -> d >= 0);
    }

    @Test
    void 失敗した説明の呼び出しにも時間が記録される() {
        var explainer = new RecordingExplainer();
        explainer.failure = new AiUnavailableException("説明に失敗", null);

        assertThatThrownBy(() -> pipeline(mixed(), explainer, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}))
                .isInstanceOfSatisfying(ExplanationFailedException.class, e -> assertThat(e.usages())
                        .extracting(SkillUsage::durationMillis)
                        .doesNotContainNull()
                        .allMatch(d -> d >= 0));
    }

    @Test
    void 説明が失敗すると調査と照合の結果を持った例外で伝える() {
        var explainer = new RecordingExplainer();
        explainer.failure = new AiUnavailableException("説明に失敗", null);

        assertThatThrownBy(() -> pipeline(mixed(), explainer, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}))
                .isInstanceOfSatisfying(ExplanationFailedException.class, e -> {
                    assertThat(e.getCause()).isSameAs(explainer.failure);
                    assertThat(e.investigation().confirmed()).extracting(Finding::statement)
                            .containsExactly("確かめられる事実");
                    assertThat(e.investigation().unconfirmed()).extracting(Finding::statement)
                            .containsExactly("確かめられない事実");
                    assertThat(e.investigation().citationResults()).hasSize(2);
                    assertThat(e.usages()).extracting(SkillUsage::skill)
                            .containsExactly(Skill.INVESTIGATOR, Skill.EXPLAINER);
                    assertThat(e.usages().get(1).failed()).isTrue();
                    assertThat(e.usages().get(0).failed()).isFalse();
                });
    }

    @Test
    void 説明の拒否も調査の結果を持った例外で伝える() {
        var explainer = new RecordingExplainer();
        explainer.failure = new AiRefusalException("拒否");

        assertThatThrownBy(() -> pipeline(mixed(), explainer, new ArrayList<>()).run(INQUIRY, SCOPE, m -> {}))
                .isInstanceOfSatisfying(
                        ExplanationFailedException.class, e -> assertThat(e.getCause()).isInstanceOf(AiRefusalException.class));
    }

    @Test
    void 調査の失敗はそのまま伝わり説明は呼ばれない() {
        var explainer = new RecordingExplainer();
        InvestigatorSkill failing = (inquiry, scope, progress) -> {
            throw new AiRefusalException("調査を拒否");
        };

        assertThatThrownBy(() -> new AnswerPipeline(failing, verifierOf(FILES), explainer).run(INQUIRY, SCOPE, m -> {}))
                .isInstanceOf(AiRefusalException.class);
        assertThat(explainer.received).isEmpty();
    }
}
