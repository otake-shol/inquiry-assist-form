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
}
