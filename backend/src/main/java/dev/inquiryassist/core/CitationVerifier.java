package dev.inquiryassist.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 0次回答の出典を、一次情報に当たって照合する。
 *
 * <p>閲覧範囲内で、読み取った行範囲の本文に引用文が含まれる（空白の違いは無視）出典だけを成功とする。
 * 読み取りで例外が出た出典（実在しない、範囲外など）は照合失敗として扱う。
 */
public final class CitationVerifier {

    private final KnowledgeSearch knowledgeSearch;

    /**
     * @param knowledgeSearch 出典の本文を読む先
     */
    public CitationVerifier(KnowledgeSearch knowledgeSearch) {
        if (knowledgeSearch == null) {
            throw new IllegalArgumentException("knowledgeSearch は必須");
        }
        this.knowledgeSearch = knowledgeSearch;
    }

    /**
     * 出典1件の照合結果。
     *
     * @param citation 照合した出典
     * @param verified 照合できた場合は true
     */
    public record Result(Citation citation, boolean verified) {}

    /**
     * すべての出典を照合する。入力の順序を保つ。
     *
     * @param citations 照合する出典
     * @param scope 投稿者の閲覧範囲
     * @return 出典ごとの照合結果
     */
    public List<Result> verify(List<Citation> citations, AccessScope scope) {
        List<Result> results = new ArrayList<>();
        for (Citation citation : citations) {
            results.add(new Result(citation, isVerified(citation, scope)));
        }
        return results;
    }

    /**
     * 照合結果から、照合できた出典だけを取り出す。
     *
     * @param results 照合結果
     * @return 照合できた出典
     */
    public static List<Citation> verifiedOnly(List<Result> results) {
        return results.stream().filter(Result::verified).map(Result::citation).toList();
    }

    private boolean isVerified(Citation citation, AccessScope scope) {
        if (!scope.allows(citation.repository())) {
            return false;
        }
        try {
            KnowledgeSearch.FileExcerpt excerpt = knowledgeSearch.read(
                    citation.repository(), citation.path(), citation.startLine(), citation.endLine(), scope);
            return excerpt != null
                    && excerpt.text() != null
                    && stripWhitespace(excerpt.text()).contains(stripWhitespace(citation.quote()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stripWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }
}
