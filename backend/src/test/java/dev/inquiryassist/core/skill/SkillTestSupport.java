package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.KnowledgeSearch;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** core のスキルのテストで使う、Spring なしの補助。 */
final class SkillTestSupport {

    static final AccessScope SCOPE = new AccessScope("u", Set.of("repo"));

    private SkillTestSupport() {}

    /** パスごとの本文を返す検索。登録のないパスは存在しないものとして失敗する。 */
    static KnowledgeSearch searchOf(Map<String, String> files) {
        return new KnowledgeSearch() {
            @Override
            public List<SearchHit> search(String query, AccessScope scope, int limit) {
                return List.of();
            }

            @Override
            public FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope) {
                String text = files.get(path);
                if (text == null) {
                    throw new NoSuchElementException(path);
                }
                return new FileExcerpt(repository, path, startLine, endLine, text, "rev");
            }
        };
    }

    static CitationVerifier verifierOf(Map<String, String> files) {
        return new CitationVerifier(searchOf(files));
    }

    static Citation citation(String path, String quote) {
        return new Citation(Citation.SourceType.CODE, "repo", path, 1, 3, quote, null);
    }

    static Finding finding(String statement, Citation... evidence) {
        return new Finding(statement, List.of(evidence));
    }
}
