package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Skill;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 保存した利用量から実行記録を作る規則を、Spring を起動せずに確認する。 */
class TraceViewsTest {

    private static InquiryProperties properties(boolean traceEnabled) {
        return new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, java.time.Duration.ofSeconds(5)),
                null,
                Map.of(),
                new InquiryProperties.Trace(traceEnabled),
                new InquiryProperties.Dev("利用者", "担当者"),
                java.util.Map.of(),
                InquiryProperties.Rules.defaults(),
                List.of());
    }

    private static SkillImplementations implementations() {
        return SkillImplementations.from(new MockEnvironment());
    }

    private static InquiryEntity entity() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.addUsage(Skill.RECEPTION, Usage.ofMock(), 3);
        e.addUsage(Skill.INVESTIGATOR, new Usage("qwen", "v1", 2663, 197, 0, false), 2921);
        return e;
    }

    @Test
    void モックの呼び出しはmock_モックでない保存済みの呼び出しはollamaのまま_モデルの名前もそのまま出て_実行した順に並ぶ() {
        List<TraceEntry> trace = TraceViews.of(entity(), properties(true), implementations(), true);

        assertThat(trace).extracting(TraceEntry::skill).containsExactly("reception", "investigator");
        assertThat(trace).extracting(TraceEntry::provider).containsExactly("mock", "ollama");
        assertThat(trace.get(1)).isEqualTo(new TraceEntry("investigator", "ollama", "qwen", "v1", 2663, 197, 2921L, false));
    }

    @Test
    void 設定上の実装が実際のモデルなら_モックでない呼び出しは設定上の実装名になる() {
        SkillImplementations configured =
                SkillImplementations.from(new MockEnvironment().withProperty("inquiry.skills.investigator.provider", "bedrock"));
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.addUsage(Skill.INVESTIGATOR, new Usage("some-model", "v1", 10, 2, 0, false), 5);
        e.addUsage(Skill.RECEPTION, Usage.ofMock(), 1);

        assertThat(TraceViews.of(e, properties(true), configured, true))
                .extracting(TraceEntry::provider)
                .containsExactly("bedrock", "mock");
    }

    @Test
    void 時間を持たない既存の形の利用量は_durationMillisがnullのまま読める() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.usages.add(new InquiryEntity.StoredUsage("restater", "mock", "mock", 0, 0, 0, true, false, null));

        assertThat(TraceViews.of(e, properties(true), implementations(), true).get(0).durationMillis()).isNull();
    }

    @Test
    void 無効にするとnullを返す() {
        assertThat(TraceViews.of(entity(), properties(false), implementations(), true)).isNull();
    }

    @Test
    void 助言と下書きの呼び出しは担当者向けにだけ含め_本人向けからは除く() {
        InquiryEntity e = entity();
        e.addUsage(Skill.ADVISOR, Usage.ofMock(), 4);
        e.addUsage(Skill.DRAFTER, Usage.ofMock(), 5);

        assertThat(TraceViews.of(e, properties(true), implementations(), true))
                .extracting(TraceEntry::skill)
                .containsExactly("reception", "investigator", "advisor", "drafter");
        assertThat(TraceViews.of(e, properties(true), implementations(), false))
                .extracting(TraceEntry::skill)
                .containsExactly("reception", "investigator");
    }
}
