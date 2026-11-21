package dev.inquiryassist.core;

/**
 * 0次回答の出典。引用文が実在するかをサーバー側で照合できる形で持つ。
 *
 * @param sourceType 出典の種類
 * @param repository リポジトリ名または文書集合の名前
 * @param path ファイルパスまたは文書の識別子
 * @param startLine 開始行（1始まり）
 * @param endLine 終了行（開始行以上）
 * @param quote 引用文。照合の対象
 * @param revision 参照した版（コミットなど）。不明な場合は {@code null}
 */
public record Citation(
        SourceType sourceType,
        String repository,
        String path,
        int startLine,
        int endLine,
        String quote,
        String revision) {

    /** 出典の種類。 */
    public enum SourceType {
        CODE,
        FAQ,
        DOCUMENT,
        GLOSSARY
    }

    public Citation {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType は必須");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path は必須");
        }
        if (quote == null || quote.isBlank()) {
            throw new IllegalArgumentException("quote は必須");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("行範囲が不正: " + startLine + "-" + endLine);
        }
    }
}
