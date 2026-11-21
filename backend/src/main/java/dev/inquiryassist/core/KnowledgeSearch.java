package dev.inquiryassist.core;

import java.util.List;

/**
 * 一次情報（ソースコード、FAQ、ドキュメント、用語対応表）の検索と読み取りの境界。
 *
 * <p>読み取り専用。閲覧範囲は実装がここで適用し、範囲外の内容を返さない。
 */
public interface KnowledgeSearch {

    /**
     * 一次情報を検索する。
     *
     * @param query 検索語
     * @param scope 投稿者の閲覧範囲
     * @param limit 返す件数の上限
     * @return 該当箇所。閲覧範囲外のものは含まない
     */
    List<SearchHit> search(String query, AccessScope scope, int limit);

    /**
     * ファイルの一部を行範囲で読む。
     *
     * @param repository リポジトリまたは文書集合の名前
     * @param path ファイルパス
     * @param startLine 開始行（1始まり）
     * @param endLine 終了行
     * @param scope 投稿者の閲覧範囲
     * @return 読み取った内容
     * @throws SecurityException 閲覧範囲外、または対象ディレクトリの外を指定した場合
     */
    FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope);

    /**
     * 検索の該当箇所。
     *
     * @param sourceType 出典の種類
     * @param repository リポジトリまたは文書集合の名前
     * @param path ファイルパス
     * @param line 該当行（1始まり）
     * @param snippet 該当行の周辺
     */
    record SearchHit(
            Citation.SourceType sourceType, String repository, String path, int line, String snippet) {}

    /**
     * ファイルの抜粋。
     *
     * @param repository リポジトリまたは文書集合の名前
     * @param path ファイルパス
     * @param startLine 開始行（1始まり）
     * @param endLine 終了行
     * @param text 抜粋の本文
     * @param revision 読み取った版（コミットなど）
     */
    record FileExcerpt(
            String repository, String path, int startLine, int endLine, String text, String revision) {}
}
