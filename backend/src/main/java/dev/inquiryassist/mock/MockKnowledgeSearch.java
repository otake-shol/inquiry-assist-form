package dev.inquiryassist.mock;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.KnowledgeSearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code mock-knowledge/} の固定ファイルを一次情報として読むモック実装。
 *
 * <p>リポジトリ名は {@code job-board}（サンプルのコード）、{@code faq}、{@code glossary}。
 * 閲覧範囲外は検索結果に含めず、読み取りは {@link SecurityException} で拒否する。
 */
@Component
@ConditionalOnProperty(name = "inquiry.knowledge.provider", havingValue = "mock", matchIfMissing = true)
public class MockKnowledgeSearch implements KnowledgeSearch {

    private static final String ROOT = "mock-knowledge/";
    private static final Map<String, Citation.SourceType> REPOSITORIES = Map.of(
            "job-board", Citation.SourceType.CODE,
            "faq", Citation.SourceType.FAQ,
            "glossary", Citation.SourceType.GLOSSARY);
    private static final String REVISION = "mock";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * 検索語（空白区切り）のいずれかを含む行を、閲覧できる範囲から探す。大文字小文字は区別しない。
     *
     * @param query 検索語
     * @param scope 投稿者の閲覧範囲
     * @param limit 返す件数の上限
     * @return 該当箇所。閲覧範囲外のものは含まない
     */
    @Override
    public List<SearchHit> search(String query, AccessScope scope, int limit) {
        List<String> terms = List.of(query.toLowerCase(Locale.ROOT).trim().split("\\s+"));
        List<SearchHit> hits = new ArrayList<>();
        for (Map.Entry<String, Citation.SourceType> repo : REPOSITORIES.entrySet()) {
            if (!scope.allows(repo.getKey())) {
                continue;
            }
            for (String path : listFiles(repo.getKey())) {
                List<String> lines = readLines(repo.getKey(), path);
                for (int i = 0; i < lines.size() && hits.size() < limit; i++) {
                    String lower = lines.get(i).toLowerCase(Locale.ROOT);
                    if (!lower.isBlank() && terms.stream().anyMatch(t -> !t.isEmpty() && lower.contains(t))) {
                        hits.add(new SearchHit(repo.getValue(), repo.getKey(), path, i + 1, lines.get(i)));
                    }
                }
            }
        }
        return hits;
    }

    /**
     * ファイルの一部を行範囲で読む。終了行がファイルの末尾を超える場合は末尾までを返す。
     *
     * @param repository リポジトリまたは文書集合の名前
     * @param path ファイルパス
     * @param startLine 開始行（1始まり）
     * @param endLine 終了行
     * @param scope 投稿者の閲覧範囲
     * @return 読み取った内容
     * @throws SecurityException 閲覧範囲外、または対象ディレクトリの外を指定した場合
     * @throws NoSuchElementException ファイルが存在しない、または開始行がファイルの範囲外の場合
     */
    @Override
    public FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope) {
        if (!REPOSITORIES.containsKey(repository) || !scope.allows(repository)) {
            throw new SecurityException("閲覧範囲外のリポジトリ: " + repository);
        }
        requireInsideRepository(path);
        List<String> lines = readLines(repository, path);
        if (startLine < 1 || endLine < startLine || startLine > lines.size()) {
            throw new NoSuchElementException("行範囲が不正: " + startLine + "-" + endLine);
        }
        int end = Math.min(endLine, lines.size());
        String text = String.join("\n", lines.subList(startLine - 1, end));
        return new FileExcerpt(repository, path, startLine, end, text, REVISION);
    }

    /** パスがリポジトリのディレクトリの外を指していないかを字面で検査する。 */
    private static void requireInsideRepository(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0) {
            throw new SecurityException("許可されないパス: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new SecurityException("許可されないパス: " + path);
            }
        }
    }

    private List<String> readLines(String repository, String path) {
        Resource resource = new ClassPathResource(ROOT + repository + "/" + path);
        if (!resource.exists()) {
            throw new NoSuchElementException("ファイルが存在しない: " + repository + "/" + path);
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> listFiles(String repository) {
        String prefix = ROOT + repository + "/";
        try {
            List<String> paths = new ArrayList<>();
            for (Resource resource : resolver.getResources("classpath*:" + prefix + "**")) {
                String url = resource.getURL().toString();
                int index = url.lastIndexOf(prefix);
                if (index >= 0 && resource.isReadable() && !url.endsWith("/")) {
                    paths.add(url.substring(index + prefix.length()));
                }
            }
            return paths.stream().sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
