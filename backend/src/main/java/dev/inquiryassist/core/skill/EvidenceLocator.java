package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.Citation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 調査の間に道具で実際に見た行を覚えておき、モデルが根拠として写した一節（引用）が、どのファイルの何行目にあるかを求める。
 *
 * <p>行番号をモデルに書かせると、ずれる。引用だけを書かせ、行番号は見た行から機械的に求める。
 * 見た行の中に引用が見つからない場合も出典は作るが、行は1行目を指す。その出典は後段の照合
 * （{@link dev.inquiryassist.core.CitationVerifier}）で落ち、事実は「根拠を確かめられなかったこと」として残る。
 * ここでは合否を決めない（確かめる役は照合に一本化する）。
 *
 * <p>1回の調査につき1つ作る。スレッドをまたいで使わない。
 */
public final class EvidenceLocator {

    /** 根拠として認める引用の、空白を除いた最小の長さ。これより短い引用は、どこにでも当たるため根拠にしない。 */
    public static final int MIN_QUOTE_LENGTH = 6;

    private record FileKey(String repository, String path) {}

    private final Map<FileKey, TreeMap<Integer, String>> seen = new HashMap<>();
    private final Map<String, Citation.SourceType> types = new HashMap<>();
    private final Map<String, String> revisions = new HashMap<>();

    /**
     * 道具の結果に出てきた行を覚える。
     *
     * @param sourceType 出典の種類。分からなければ null（検索の結果からは分かり、読み取りの結果からは分からない）
     * @param repository リポジトリまたは文書集合の名前
     * @param path ファイルパス
     * @param line 行番号（1始まり）
     * @param text その行の本文
     * @param revision 読み取った版。分からなければ null
     */
    public void remember(
            Citation.SourceType sourceType, String repository, String path, int line, String text, String revision) {
        if (repository == null || path == null || line < 1 || text == null) {
            return;
        }
        seen.computeIfAbsent(new FileKey(repository, path), k -> new TreeMap<>()).put(line, text);
        if (sourceType != null) {
            types.put(repository, sourceType);
        }
        if (revision != null) {
            revisions.put(repository, revision);
        }
    }

    /**
     * その資料の集まりの、出典の種類を返す。
     *
     * @param repository リポジトリまたは文書集合の名前
     * @return 検索の結果から分かった種類。まだ分からなければ {@link Citation.SourceType#DOCUMENT}
     */
    public Citation.SourceType typeOf(String repository) {
        return types.getOrDefault(repository, Citation.SourceType.DOCUMENT);
    }

    /**
     * 引用から出典を作る。
     *
     * @param repository モデルが挙げたリポジトリまたは文書集合の名前
     * @param path モデルが挙げたファイルパス
     * @param quote モデルが写した一節
     * @return 出典。パスか引用が空、または引用が短すぎる場合は空
     */
    public Optional<Citation> locate(String repository, String path, String quote) {
        if (repository == null || repository.isBlank() || path == null || path.isBlank() || quote == null) {
            return Optional.empty();
        }
        String needle = strip(quote);
        if (needle.length() < MIN_QUOTE_LENGTH) {
            return Optional.empty();
        }
        int[] range = find(seen.get(new FileKey(repository, path)), needle);
        int start = range == null ? 1 : range[0];
        int end = range == null ? 1 : range[1];
        return Optional.of(new Citation(
                typeOf(repository), repository, path, start, end, quote.strip(), revisions.get(repository)));
    }

    /** 連続して見た行をつなげた文字列（空白を除く）から引用を探し、始まりと終わりの行番号を返す。なければ null。 */
    private static int[] find(TreeMap<Integer, String> lines, String needle) {
        if (lines == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        List<Integer> lineOfChar = new ArrayList<>();
        int previous = -1;
        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            if (previous != -1 && entry.getKey() != previous + 1) {
                // 行が飛んでいる。飛んだ行をまたぐ引用は、実際には続いていないので認めない
                int[] found = indexIn(text, lineOfChar, needle);
                if (found != null) {
                    return found;
                }
                text.setLength(0);
                lineOfChar.clear();
            }
            String stripped = strip(entry.getValue());
            text.append(stripped);
            for (int i = 0; i < stripped.length(); i++) {
                lineOfChar.add(entry.getKey());
            }
            previous = entry.getKey();
        }
        return indexIn(text, lineOfChar, needle);
    }

    private static int[] indexIn(StringBuilder text, List<Integer> lineOfChar, String needle) {
        int index = text.indexOf(needle);
        if (index < 0) {
            return null;
        }
        return new int[] {lineOfChar.get(index), lineOfChar.get(index + needle.length() - 1)};
    }

    private static String strip(String text) {
        return text.replaceAll("\\s+", "");
    }
}
