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

    private static SkillImplementations implementations(String skill, String provider) {
        return SkillImplementations.from(new MockEnvironment().withProperty("inquiry.skills." + skill + ".provider", provider));
    }

    private static InquiryEntity entity() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        SkillImplementations implementations = implementations();
        e.addUsage(Skill.RECEPTION, Usage.ofMock(), 3, implementations);
        e.addUsage(Skill.INVESTIGATOR, new Usage("qwen", "v1", 2663, 197, 0, false), 2921, implementations);
        return e;
    }

    @Test
    void 記録した時点の実装の名前を保存し_表示は保存した名前を使い_実行した順に並ぶ() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.addUsage(Skill.RECEPTION, Usage.ofMock(), 3, implementations());
        e.addUsage(Skill.INVESTIGATOR, new Usage("some-model", "v1", 2663, 197, 0, false), 2921, implementations("investigator", "bedrock"));

        List<TraceEntry> trace = TraceViews.of(e, properties(true), true);

        assertThat(trace).extracting(TraceEntry::skill).containsExactly("reception", "investigator");
        assertThat(trace).extracting(TraceEntry::provider).containsExactly("mock", "bedrock");
        assertThat(trace.get(1)).isEqualTo(new TraceEntry("investigator", "bedrock", "some-model", "v1", 2663, 197, 2921L, false));
    }

    @Test
    void 実装を差し替えた後も_差し替える前に記録した名前はそのまま出る() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.addUsage(Skill.INVESTIGATOR, new Usage("m1", "v1", 1, 1, 0, false), 1, implementations("investigator", "alpha"));
        // 設定を beta に差し替えた後の記録
        e.addUsage(Skill.INVESTIGATOR, new Usage("m2", "v1", 1, 1, 0, false), 1, implementations("investigator", "beta"));

        assertThat(TraceViews.of(e, properties(true), true))
                .extracting(TraceEntry::provider)
                .containsExactly("alpha", "beta");
    }

    @Test
    void 名前を持たない古い記録は_モックならmock_そうでなければollamaと読み替える() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.usages.add(new InquiryEntity.StoredUsage("reception", "mock", "mock", 0, 0, 0, true, false, 3L, null));
        e.usages.add(new InquiryEntity.StoredUsage("investigator", "qwen", "v1", 2663, 197, 0, false, false, 2921L, null));

        List<TraceEntry> trace = TraceViews.of(e, properties(true), true);

        assertThat(trace).extracting(TraceEntry::provider).containsExactly("mock", "ollama");
        assertThat(trace.get(1)).isEqualTo(new TraceEntry("investigator", "ollama", "qwen", "v1", 2663, 197, 2921L, false));
    }

    @Test
    void 時間を持たない既存の形の利用量は_durationMillisがnullのまま読める() {
        InquiryEntity e = new InquiryEntity("local-user", Urgency.NORMAL, Instant.now());
        e.usages.add(new InquiryEntity.StoredUsage("restater", "mock", "mock", 0, 0, 0, true, false, null, null));

        assertThat(TraceViews.of(e, properties(true), true).get(0).durationMillis()).isNull();
    }

    @Test
    void 無効にするとnullを返す() {
        assertThat(TraceViews.of(entity(), properties(false), true)).isNull();
    }

    @Test
    void 助言と下書きの呼び出しは担当者向けにだけ含め_本人向けからは除く() {
        InquiryEntity e = entity();
        e.addUsage(Skill.ADVISOR, Usage.ofMock(), 4, implementations());
        e.addUsage(Skill.DRAFTER, Usage.ofMock(), 5, implementations());

        assertThat(TraceViews.of(e, properties(true), true))
                .extracting(TraceEntry::skill)
                .containsExactly("reception", "investigator", "advisor", "drafter");
        assertThat(TraceViews.of(e, properties(true), false))
                .extracting(TraceEntry::skill)
                .containsExactly("reception", "investigator");
    }
}
