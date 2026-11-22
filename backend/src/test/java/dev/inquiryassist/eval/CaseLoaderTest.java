package dev.inquiryassist.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 例のファイルの読み込みと、実行前の検査を確かめる。 */
class CaseLoaderTest {

    @TempDir
    Path dir;

    private void write(String skill, String json) throws IOException {
        Files.writeString(dir.resolve(skill + ".json"), json);
    }

    private static final String RESTATER_OK =
            "{\"id\":\"rs-x\",\"split\":\"dev\",\"utterance\":\"u\",\"type\":\"HOW_IT_WORKS\",\"systemLabel\":null,"
                    + "\"answers\":[],\"corrections\":[],\"expect\":%s}";

    private CaseLoader.Loaded loadRestater(String expect) throws IOException {
        write("restater", "{\"cases\":[" + RESTATER_OK.formatted(expect) + "]}");
        return CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.RESTATER));
    }

    @Test
    void リポジトリの例の4ファイルが読み込める() {
        Path root = Path.of("..").toAbsolutePath().normalize();

        var loaded = CaseLoader.load(
                List.of(new CaseLoader.Source(root.resolve("evals/cases"), true)), List.of(EvalSkill.values()));

        assertThat(loaded.cases()).extracting(c -> c.skill()).containsOnly(EvalSkill.values());
        for (EvalSkill skill : EvalSkill.values()) {
            var forSkill = loaded.cases().stream().filter(c -> c.skill() == skill).toList();
            // 調整用と確認用の両方がある
            assertThat(forSkill).extracting(EvalCase::split).contains(EvalCase.DEV, EvalCase.HOLDOUT);
        }
        assertThat(loaded.systems()).extracting(s -> s.id()).contains("job-admin", "applicant-list", "billing");
    }

    @Test
    void 正しい例は読み込める() throws IOException {
        var loaded = loadRestater("{\"mustNotMatch\":[\"(?<!一)昨日\"],\"style\":\"plain\"}");

        assertThat(loaded.cases()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("rs-x");
            assertThat(c.isHoldout()).isFalse();
        });
    }

    @Test
    void 知らない条件の名前は実行前にエラーになる() {
        assertThatThrownBy(() -> loadRestater("{\"mustIncludes\":[\"a\"]}"))
                .isInstanceOf(EvalConfigException.class)
                .hasMessageContaining("知らない条件の名前: mustIncludes");
    }

    @Test
    void 別のスキルの条件もエラーになる() {
        // answersQuestion は説明の条件で、言い直しでは使えない
        assertThatThrownBy(() -> loadRestater("{\"answersQuestion\":true}"))
                .isInstanceOf(EvalConfigException.class)
                .hasMessageContaining("知らない条件の名前: answersQuestion");
    }

    @Test
    void 必須の項目の欠けはエラーになる() throws IOException {
        write("restater", "{\"cases\":[{\"id\":\"rs-x\",\"split\":\"dev\",\"utterance\":\"u\",\"expect\":{\"style\":\"plain\"}}]}");

        assertThatThrownBy(() -> CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.RESTATER)))
                .isInstanceOf(EvalConfigException.class)
                .satisfies(e -> {
                    String message = e.getMessage();
                    assertThat(message).contains("type が不正").contains("systemLabel は必須").contains("answers").contains("corrections");
                });
    }

    @Test
    void 条件の値の誤りはエラーになる() {
        assertThatThrownBy(() -> loadRestater("{\"style\":\"casual\",\"maxSentences\":\"3\",\"mustNotMatch\":[\"(\"]}"))
                .isInstanceOf(EvalConfigException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("style は plain か polite")
                        .contains("maxSentences は0以上の整数")
                        .contains("正規表現が不正"));
    }

    @Test
    void 区分の誤りと重複したidはエラーになりすべての誤りをまとめて伝える() throws IOException {
        String bad = RESTATER_OK.formatted("{\"style\":\"plain\"}").replace("\"split\":\"dev\"", "\"split\":\"test\"");
        write("restater", "{\"cases\":[" + bad + "," + RESTATER_OK.formatted("{\"style\":\"plain\"}") + ","
                + RESTATER_OK.formatted("{\"style\":\"plain\"}") + "]}");

        assertThatThrownBy(() -> CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.RESTATER)))
                .isInstanceOfSatisfying(EvalConfigException.class, e -> {
                    assertThat(e.errors()).anyMatch(m -> m.contains("split は dev か holdout"));
                    assertThat(e.errors()).anyMatch(m -> m.contains("id が重複"));
                });
    }

    @Test
    void 受付の例はsystemsと必須の条件を要求しnullを含むsystemIdAnyOfを読める() throws IOException {
        write("reception", "{\"systems\":[{\"id\":\"a\",\"label\":\"A\"}],\"cases\":[{\"id\":\"rc-x\",\"split\":\"holdout\","
                + "\"utterance\":\"u\",\"selectedSystemId\":null,\"expect\":{\"type\":\"HOW_IT_WORKS\",\"systemIdAnyOf\":[\"a\",null]}}]}");

        var loaded = CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.RECEPTION));

        assertThat(loaded.cases()).singleElement().satisfies(c -> assertThat(c.isHoldout()).isTrue());
        assertThat(loaded.systems()).hasSize(1);

        write("reception", "{\"cases\":[{\"id\":\"rc-x\",\"split\":\"dev\",\"utterance\":\"u\",\"selectedSystemId\":null,"
                + "\"expect\":{\"type\":\"HOW_IT_WORKS\"}}]}");
        assertThatThrownBy(() -> CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.RECEPTION)))
                .hasMessageContaining("systems")
                .hasMessageContaining("systemId か systemIdAnyOf が必要");
    }

    @Test
    void 公開の例のファイルがなければエラーで非公開の例がなければ0件になる() throws IOException {
        assertThatThrownBy(() -> CaseLoader.load(List.of(new CaseLoader.Source(dir, true)), List.of(EvalSkill.EXPLAINER)))
                .isInstanceOf(EvalConfigException.class);

        var loaded = CaseLoader.load(List.of(new CaseLoader.Source(dir.resolve("private"), false)), List.of(EvalSkill.EXPLAINER));

        assertThat(loaded.cases()).isEmpty();
    }
}
