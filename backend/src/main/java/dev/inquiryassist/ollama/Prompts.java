package dev.inquiryassist.ollama;

import dev.inquiryassist.core.skill.Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** スキルの system プロンプトを classpath（{@code skills/<識別子>/system-<版>.md}）から読む。コードには埋め込まない。 */
final class Prompts {

    /** 使うプロンプトの版。利用量の記録の {@code promptVersion} になる。 */
    static final String VERSION = "v1";

    private Prompts() {}

    /**
     * @param skill スキル
     * @return system プロンプトの本文
     */
    static String systemOf(Skill skill) {
        ClassPathResource resource = new ClassPathResource("skills/" + skill.id() + "/system-" + VERSION + ".md");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("プロンプトを読めません: " + resource.getPath(), e);
        }
    }
}
