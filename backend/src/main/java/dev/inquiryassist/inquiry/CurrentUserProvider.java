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
}
