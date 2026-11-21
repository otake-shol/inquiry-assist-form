package dev.inquiryassist.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationVerifierTest {

    private static final AccessScope SCOPE = new AccessScope("u", Set.of("repo"));

    /** 指定した本文を返し、読み取りの呼び出しを記録する検索。 */
    private static final class FakeSearch implements KnowledgeSearch {
        final List<String> reads = new ArrayList<>();
        final String text;
        final RuntimeException failure;

        FakeSearch(String text, RuntimeException failure) {
            this.text = text;
            this.failure = failure;
        }

        @Override
        public List<SearchHit> search(String query, AccessScope scope, int limit) {
            return List.of();
        }

        @Override
        public FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope) {
            reads.add(repository + "/" + path + ":" + startLine + "-" + endLine);
            if (failure != null) {
                throw failure;
            }
            return new FileExcerpt(repository, path, startLine, endLine, text, "rev");
        }
    }

    private static Citation citation(String repository, String quote) {
        return new Citation(Citation.SourceType.CODE, repository, "a/B.java", 3, 5, quote, null);
    }

    @Test
    void 読み取った範囲の本文に引用文が含まれれば照合できる() {
        FakeSearch search = new FakeSearch("if (x) {\n    return a;\n}", null);

        var results = new CitationVerifier(search).verify(List.of(citation("repo", "return a;")), SCOPE);

        assertThat(results).singleElement().satisfies(r -> assertThat(r.verified()).isTrue());
        assertThat(search.reads).containsExactly("repo/a/B.java:3-5");
    }

    @Test
    void 空白や改行の違いは無視して照合する() {
        FakeSearch search = new FakeSearch("if (x)   {\n\t return   a ;\n}", null);

        var results = new CitationVerifier(search).verify(List.of(citation("repo", "if (x) { return a ;")), SCOPE);

        assertThat(results.get(0).verified()).isTrue();
    }

    @Test
    void 本文に含まれない引用文は照合に失敗する() {
        FakeSearch search = new FakeSearch("return a;", null);

        var results = new CitationVerifier(search).verify(List.of(citation("repo", "return b;")), SCOPE);

        assertThat(results.get(0).verified()).isFalse();
    }

    @Test
    void 読み取りで例外が出た出典は照合失敗として扱う() {
        FakeSearch search = new FakeSearch(null, new IllegalStateException("存在しない"));

        var results = new CitationVerifier(search).verify(List.of(citation("repo", "x")), SCOPE);

        assertThat(results.get(0).verified()).isFalse();
    }

    @Test
    void 閲覧範囲外の出典は読み取らずに照合失敗とする() {
        FakeSearch search = new FakeSearch("secret", null);

        var results = new CitationVerifier(search).verify(List.of(citation("other-repo", "secret")), SCOPE);

        assertThat(results.get(0).verified()).isFalse();
        assertThat(search.reads).isEmpty();
    }
}
