package dev.inquiryassist.ollama;

import dev.inquiryassist.core.skill.Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** スキルの system プロンプトを classpath（{@code skills/<識別子>/system-<版>.md}）から読む。コードには埋め込まない。 */
final class Prompts {

    private Prompts() {}

    /**
     * @param skill スキル
     * @param version プロンプトの版（例: {@code v1}）。利用量の記録の {@code promptVersion} になる
     * @return system プロンプトの本文
     * @throws UncheckedIOException その版のファイルがない場合
     */
    static String systemOf(Skill skill, String version) {
        ClassPathResource resource = new ClassPathResource("skills/" + skill.id() + "/system-" + version + ".md");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("プロンプトを読めません: " + resource.getPath(), e);
        }
    }

    /**
     * 聞き手の「答えを読み取る」指示文（{@code skills/<識別子>/prefill-<版>.md}）を読む。
     *
     * @param skill スキル
     * @param version 指示文の版（例: {@code v1}）
     * @return 指示文の本文
     * @throws UncheckedIOException その版のファイルがない場合
     */
    static String prefillOf(Skill skill, String version) {
        ClassPathResource resource = new ClassPathResource("skills/" + skill.id() + "/prefill-" + version + ".md");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("プロンプトを読めません: " + resource.getPath(), e);
        }
    }

    /**
     * 人が読む文章を書くスキルの指示文を、本人の言語に合わせる。日本語のときは、指示文をそのまま返す
     * （日本語のときにモデルへ送る内容は、言語に対応する前と1文字も変えない。評価の結果を有効に保つため）。
     * 日本語以外のときだけ、末尾に書く言語の指示（{@code skills/shared/output-language-v1.md}）を足す。
     *
     * @param system 指示文
     * @param language 本人の言語
     * @return 本人の言語に合わせた指示文
     */
    static String withOutputLanguage(String system, String language) {
        if (language == null || language.isBlank() || "ja".equalsIgnoreCase(language)) {
            return system;
        }
        ClassPathResource resource = new ClassPathResource("skills/shared/output-language-v1.md");
        try (InputStream in = resource.getInputStream()) {
            String shared = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return system + shared.replace("{{language}}", languageName(language));
        } catch (IOException e) {
            throw new UncheckedIOException("プロンプトを読めません: " + resource.getPath(), e);
        }
    }

    /** 書く言語の名前。指示文が日本語で書かれているため、日本語の名前に、その言語での名前を添える（例: 英語（English））。 */
    static String languageName(String language) {
        java.util.Locale locale = java.util.Locale.forLanguageTag(language);
        return locale.getDisplayLanguage(java.util.Locale.JAPANESE) + "（" + locale.getDisplayLanguage(locale) + "）";
    }
}
