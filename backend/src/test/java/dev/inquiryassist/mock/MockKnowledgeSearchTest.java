package dev.inquiryassist.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.KnowledgeSearch;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockKnowledgeSearchTest {

    private final MockKnowledgeSearch search = new MockKnowledgeSearch();
    private final AccessScope all = new AccessScope("u", Set.of("job-board", "faq", "glossary"));
    private final AccessScope codeOnly = new AccessScope("u", Set.of("job-board"));

    @Test
    void 閲覧できる範囲の行を行範囲で読める() {
        var excerpt = search.read(
                "job-board", "src/main/java/com/example/jobboard/JobPostingService.java", 10, 12, all);

        assertThat(excerpt.text()).contains("return !now.isBefore(posting.expiresAt());");
        assertThat(excerpt.startLine()).isEqualTo(10);
        assertThat(excerpt.endLine()).isEqualTo(12);
    }

    @Test
    void 検索は閲覧範囲内のリポジトリだけを返す() {
        List<KnowledgeSearch.SearchHit> everything = search.search("掲載期限", all, 50);
        List<KnowledgeSearch.SearchHit> scoped = search.search("掲載期限", codeOnly, 50);

        assertThat(everything).extracting(KnowledgeSearch.SearchHit::repository).contains("faq", "glossary");
        assertThat(scoped).isNotEmpty().allSatisfy(h -> assertThat(h.repository()).isEqualTo("job-board"));
    }

    @Test
    void 検索のヒットは実在する行を指す() {
        var hit = search.search("expiresAt", all, 50).stream()
                .filter(h -> h.repository().equals("job-board"))
                .findFirst()
                .orElseThrow();

        var excerpt = search.read(hit.repository(), hit.path(), hit.line(), hit.line(), all);

        assertThat(excerpt.text()).contains("expiresAt");
    }

    @Test
    void 閲覧範囲外のリポジトリの読み取りはSecurityExceptionになる() {
        assertThatThrownBy(() -> search.read("faq", "posting-expiry.md", 1, 2, codeOnly))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../faq/posting-expiry.md",
                "src/../../faq/posting-expiry.md",
                "/etc/passwd",
                "src\\..\\x",
                "src//x",
                ""
            })
    void 対象ディレクトリの外を指すパスはSecurityExceptionになる(String path) {
        assertThatThrownBy(() -> search.read("job-board", path, 1, 2, all)).isInstanceOf(SecurityException.class);
    }

    @Test
    void 存在しないファイルは読み取りに失敗する() {
        assertThatThrownBy(() -> search.read("job-board", "src/Ghost.java", 1, 2, all))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 未知のリポジトリ名は範囲に含まれていてもSecurityExceptionになる() {
        AccessScope odd = new AccessScope("u", Set.of("../secret"));
        assertThatThrownBy(() -> search.read("../secret", "x", 1, 1, odd)).isInstanceOf(SecurityException.class);
    }
}
