package dev.inquiryassist.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 見る人の言語の決め方（契約書「見る人の言語」）を、Spring を起動せずに確認する。 */
class LanguageResolverTest {

    private final LanguageResolver resolver = new LanguageResolver(List.of("ja", "en"));

    @Test
    void 何も指定がなければ使える言語の先頭が既定の言語() {
        assertThat(resolver.resolve(null, null)).isEqualTo("ja");
        assertThat(resolver.resolve("", "")).isEqualTo("ja");
        assertThat(resolver.defaultLanguage()).isEqualTo("ja");
        assertThat(new LanguageResolver(List.of("en", "ja")).resolve(null, null)).isEqualTo("en");
    }

    @Test
    void 使える言語が空ならjaだけ() {
        LanguageResolver empty = new LanguageResolver(List.of());
        assertThat(empty.available()).containsExactly("ja");
        assertThat(empty.resolve(null, "en")).isEqualTo("ja");
    }

    @Test
    void 地域つきの言語タグは地域を無視して比べる() {
        assertThat(resolver.resolve(null, "en-US")).isEqualTo("en");
        assertThat(resolver.resolve(null, "en_GB")).isEqualTo("en");
        assertThat(resolver.resolve(null, "ja-JP")).isEqualTo("ja");
        assertThat(resolver.resolve(null, "EN-us")).isEqualTo("en");
    }

    @Test
    void 品質値の大きい順に比べ_同じなら書かれた順() {
        assertThat(resolver.resolve(null, "fr, en;q=0.8")).isEqualTo("en");
        assertThat(resolver.resolve(null, "ja;q=0.5, en;q=0.9")).isEqualTo("en");
        assertThat(resolver.resolve(null, "en, ja")).isEqualTo("en");
        assertThat(resolver.resolve(null, "ja, en")).isEqualTo("ja");
    }

    @Test
    void 品質値0とワイルドカードは候補にしない() {
        assertThat(resolver.resolve(null, "en;q=0, ja;q=0.1")).isEqualTo("ja");
        assertThat(resolver.resolve(null, "en;q=0")).isEqualTo("ja");
        assertThat(resolver.resolve(null, "*")).isEqualTo("ja");
        assertThat(resolver.resolve(null, "fr, *;q=0.5, en;q=0.1")).isEqualTo("en");
    }

    @Test
    void 読めない品質値の候補は使わない() {
        assertThat(resolver.resolve(null, "en;q=abc, ja;q=0.3")).isEqualTo("ja");
    }

    @Test
    void 使えない言語だけなら既定の言語() {
        assertThat(resolver.resolve(null, "fr, de;q=0.8")).isEqualTo("ja");
    }

    @Test
    void クエリのlangはAccept_Languageより優先する() {
        assertThat(resolver.resolve("en", "ja")).isEqualTo("en");
        assertThat(resolver.resolve("ja", "en-US")).isEqualTo("ja");
        assertThat(resolver.resolve("EN", null)).isEqualTo("en");
    }

    @Test
    void 使えないlangは無視してAccept_Languageで決める() {
        assertThat(resolver.resolve("fr", "en")).isEqualTo("en");
        assertThat(resolver.resolve("fr", null)).isEqualTo("ja");
        assertThat(resolver.resolve("  ", "en")).isEqualTo("en");
    }
}
