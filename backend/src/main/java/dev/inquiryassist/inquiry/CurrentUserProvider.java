package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AccessScope;

/** 現在の投稿者と閲覧範囲を返すポート。SSO 連携の実装は後のフェーズで差し替える。 */
public interface CurrentUserProvider {

    /**
     * 現在の投稿者の識別子と閲覧範囲を返す。
     *
     * @return 投稿者の閲覧範囲
     */
    AccessScope currentScope();

    /**
     * 指定した投稿者の閲覧範囲を返す。裏での調査は、頼んだ人（担当者）ではなく、問い合わせの本人の権限で行うため。
     *
     * @param userId 投稿者の識別子
     * @return その投稿者の閲覧範囲
     */
    AccessScope scopeOf(String userId);
}
