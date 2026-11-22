package dev.inquiryassist.core.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 見る人の言語を決める（契約書「見る人の言語」）。リクエストごとに、使える言語から1つを選ぶ。
 *
 * <p>Spring にも HTTP にも依存しない。ヘッダーの値を文字列で受け取る。
 */
public final class LanguageResolver {

    private final List<String> available;

    /**
     * @param available 使える言語（先頭が既定の言語）。空なら {@code ja} だけ
     */
    public LanguageResolver(List<String> available) {
        this.available = available == null || available.isEmpty() ? List.of("ja") : List.copyOf(available);
    }

    /**
     * @return 既定の言語（使える言語の先頭）
     */
    public String defaultLanguage() {
        return available.get(0);
    }

    /**
     * @return 使える言語
     */
    public List<String> available() {
        return available;
    }

    /**
     * 見る人の言語を決める。クエリの {@code lang} が使える言語なら、それを優先する（ヘッダーを付けられない接続のため）。
     * なければ、{@code Accept-Language} に書かれた順（品質値の大きい順。同じなら書かれた順）に、使える言語と比べて、
     * 最初に当てはまったもの。{@code en-US} は {@code en} として比べる。どれにも当てはまらなければ既定の言語。
     *
     * @param lang クエリの {@code lang}。なければ {@code null}
     * @param acceptLanguage {@code Accept-Language} ヘッダーの値。なければ {@code null}
     * @return 使える言語のどれか
     */
    public String resolve(String lang, String acceptLanguage) {
        String requested = match(lang);
        if (requested != null) {
            return requested;
        }
        if (acceptLanguage != null) {
            for (String tag : orderedTags(acceptLanguage)) {
                String matched = match(tag);
                if (matched != null) {
                    return matched;
                }
            }
        }
        return defaultLanguage();
    }

    /** 言語タグを使える言語と比べる。地域などの後ろの部分は無視する。当てはまらなければ null。 */
    private String match(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String primary = tag.strip().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return available.stream().filter(l -> l.equalsIgnoreCase(primary)).findFirst().orElse(null);
    }

    /** {@code Accept-Language} の言語タグを、品質値の大きい順に並べる。品質値が0のものと {@code *} は除く。 */
    private static List<String> orderedTags(String header) {
        record Weighted(String tag, double quality, int order) {}
        List<Weighted> items = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length; i++) {
            String[] segments = parts[i].strip().split(";");
            String tag = segments[0].strip();
            if (tag.isEmpty() || tag.equals("*")) {
                continue;
            }
            double quality = 1.0;
            for (int j = 1; j < segments.length; j++) {
                String s = segments[j].strip();
                if (s.startsWith("q=") || s.startsWith("Q=")) {
                    try {
                        quality = Double.parseDouble(s.substring(2));
                    } catch (NumberFormatException e) {
                        quality = 0;
                    }
                }
            }
            if (quality > 0) {
                items.add(new Weighted(tag, quality, i));
            }
        }
        items.sort((a, b) -> a.quality() != b.quality()
                ? Double.compare(b.quality(), a.quality())
                : Integer.compare(a.order(), b.order()));
        return items.stream().map(Weighted::tag).toList();
    }
}
