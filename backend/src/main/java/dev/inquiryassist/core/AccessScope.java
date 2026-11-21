package dev.inquiryassist.core;

import java.util.Set;

/**
 * 投稿者の閲覧範囲。検索の時点で適用し、範囲外の一次情報をAIに渡さない。
 *
 * @param userId 投稿者の識別子
 * @param allowedRepositories 投稿者が見てよいリポジトリと文書集合の名前
 */
public record AccessScope(String userId, Set<String> allowedRepositories) {

    public AccessScope {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId は必須");
        }
        allowedRepositories = allowedRepositories == null ? Set.of() : Set.copyOf(allowedRepositories);
    }

    /**
     * 指定のリポジトリを見てよいかを返す。
     *
     * @param repository リポジトリまたは文書集合の名前
     * @return 閲覧できる場合は true
     */
    public boolean allows(String repository) {
        return allowedRepositories.contains(repository);
    }
}
