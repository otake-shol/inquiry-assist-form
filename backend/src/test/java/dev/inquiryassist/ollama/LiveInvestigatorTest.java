package dev.inquiryassist.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Investigation;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import dev.inquiryassist.mock.MockKnowledgeSearch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 調査を、実際の Ollama と見本の資料で動かす確認。{@code liveTest} で手元から実行する。
 *
 * <p>確かめるのは、道具を使った調査が最後まで進み、型どおりの結果と利用量を返すことだけ。
 * 何を調べ、何を事実としたかは標準出力に出す（中身の良し悪しは、評価で測る）。
 */
@Tag("live")
class LiveInvestigatorTest {

    private static final String MODEL = "qwen3.6:35b";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AccessScope SCOPE = new AccessScope("live", Set.of("job-board", "faq", "glossary"));

    /** 道具の呼び出しを記録する検索。 */
    private static final class Recording implements KnowledgeSearch {
        private final KnowledgeSearch delegate = new MockKnowledgeSearch();
        final List<String> calls = new ArrayList<>();

        @Override
        public List<SearchHit> search(String query, AccessScope scope, int limit) {
            List<SearchHit> hits = delegate.search(query, scope, limit);
            calls.add("search「" + query + "」→ " + hits.size() + " 件");
            return hits;
        }

        @Override
        public FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope) {
            calls.add("read " + repository + "/" + path + " " + startLine + "-" + endLine);
            return delegate.read(repository, path, startLine, endLine, scope);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "掲載期限を過ぎた求人が、応募者向けの一覧でどうなるかを知りたい。",
                "取引先ごとに請求の締め日を変えられるかを知りたい。",
                "掲載期間を延長すると、応募者向けの一覧での並び順が変わるかを知りたい。",
                "出張の交通費を精算できる期限を知りたい。"
            })
    void 調査は道具を使って最後まで進み_型どおりの結果と利用量を返す(String question) {
        Recording knowledge = new Recording();
        SkillSettings settings = new SkillSettings("ollama", MODEL, 0, false, Duration.ofSeconds(120), "v1");
        InquiryProperties properties = new InquiryProperties(
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of("investigator", settings),
                new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("開発用ユーザー", "担当者（開発用）"));
        OllamaInvestigatorSkill skill = new OllamaInvestigatorSkill(
                new OllamaClient("http://localhost:11434", "30m", 42, JSON), JSON, properties, knowledge);
        StructuredInquiry inquiry =
                new StructuredInquiry(question, InquiryCategory.SPEC_QUESTION, null, question, null, null, null);
        List<String> progress = new ArrayList<>();

        long start = System.nanoTime();
        AiResult<Investigation> result = skill.investigate(inquiry, SCOPE, progress::add);
        long millis = (System.nanoTime() - start) / 1_000_000;

        CitationVerifier verifier = new CitationVerifier(knowledge);
        System.out.println("\n=== " + question + "（" + millis + "ms、入力 " + result.usage().inputTokens() + "、出力 "
                + result.usage().outputTokens() + "）");
        knowledge.calls.forEach(c -> System.out.println("  道具: " + c));
        System.out.println("  進捗: " + progress);
        for (Finding f : result.value().findings()) {
            System.out.println("  事実: " + f.statement());
            f.evidence().forEach(c -> System.out.println("    根拠（" + (verifier.verify(List.of(c), SCOPE).get(0).verified() ? "照合○" : "照合×")
                    + "）" + c.repository() + "/" + c.path() + ":" + c.startLine() + "-" + c.endLine() + " 「" + c.quote() + "」"));
        }
        System.out.println("  見つからなかったこと: " + result.value().notFound());
        System.out.println("  次に確認すること: " + result.value().nextChecks());

        assertThat(result.usage().model()).isEqualTo(MODEL);
        assertThat(result.usage().inputTokens()).isPositive();
        assertThat(result.value().findings()).hasSizeLessThanOrEqualTo(OllamaInvestigatorSkill.MAX_FINDINGS);
    }
}
