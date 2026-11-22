package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V12（終わらせた人）が、既存の終わっている行の {@code closed_by} を、履歴の最後の要素の {@code actor} から（履歴が空なら終わり方から）
 * 埋めることと、返事がないときの決まりの索引が聞き返しへの返事も引けることを確かめる。V11 までを適用した空のデータベースに行を入れてから、
 * V12 を適用する。
 */
class V12MigrationTest {

    private static PostgreSQLContainer postgres;
    private static int databases;

    private JdbcTemplate jdbc;
    private DataSource dataSource;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer("postgres:17");
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    @BeforeEach
    void migrateToV11() {
        String name = "v12_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("11").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private static String historyEndingWith(String actor) {
        return "[{\"at\":1795327205.0,\"actor\":\"ASKER\",\"event\":\"CREATED\"},"
                + "{\"at\":1795400000.0,\"actor\":\"" + actor + "\",\"event\":\"X\"}]";
    }

    private UUID closed(String ending, String history) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, ending, history) values (?::uuid, 'local-user', 'CLOSED', ?, ?::jsonb)",
                id.toString(),
                ending,
                history);
        return id;
    }

    private String closedBy(UUID id) {
        return jdbc.queryForObject("select closed_by from inquiries where id = ?::uuid", String.class, id.toString());
    }

    @Test
    void 履歴のある終わった行は最後の要素のactorから埋める() {
        // 履歴の最後のactorを使う。終わり方から決めるのではない（ここでは、あえて終わり方と食い違う組み合わせにしている）
        UUID byAsker = closed("DECLINED", historyEndingWith("ASKER"));
        UUID byOperator = closed("RESOLVED", historyEndingWith("OPERATOR"));
        UUID byTime = closed("WITHDRAWN", historyEndingWith("TIME"));

        flyway("12").migrate();

        assertThat(closedBy(byAsker)).isEqualTo("ASKER");
        assertThat(closedBy(byOperator)).isEqualTo("OPERATOR");
        assertThat(closedBy(byTime)).isEqualTo("TIME");
    }

    @Test
    void 履歴が空の終わった行は終わり方から決める() {
        UUID declined = closed("DECLINED", "[]");
        UUID answered = closed("ANSWERED", "[]");
        UUID resolved = closed("RESOLVED", "[]");
        UUID withdrawn = closed("WITHDRAWN", "[]");

        flyway("12").migrate();

        assertThat(closedBy(declined)).isEqualTo("OPERATOR");
        assertThat(closedBy(answered)).isEqualTo("TIME");
        assertThat(closedBy(resolved)).isEqualTo("ASKER");
        assertThat(closedBy(withdrawn)).isEqualTo("ASKER");
    }

    @Test
    void 履歴の最後のactorがAIなど想定外の値なら終わり方から決める() {
        UUID id = closed("DECLINED", historyEndingWith("AI"));

        flyway("12").migrate();

        assertThat(closedBy(id)).isEqualTo("OPERATOR");
    }

    @Test
    void 終わっていない行はnullのまま() {
        UUID open = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, awaiting, history) values (?::uuid, 'local-user', 'KNOW', 'ASKER_REPLY', ?::jsonb)",
                open.toString(),
                historyEndingWith("OPERATOR"));

        flyway("12").migrate();

        assertThat(closedBy(open)).isNull();
    }

    @Test
    void closed_byは3つの値だけを許す() {
        flyway("12").migrate();
        UUID id = closed("RESOLVED", "[]");

        assertThatThrownBy(() -> jdbc.update("update inquiries set closed_by = 'AI' where id = ?::uuid", id.toString()))
                .hasMessageContaining("closed_by");
    }

    @Test
    void 判定の索引は結果の確かめと聞き返しへの返事を対象にし_V11の索引は作り直される() {
        flyway("12").migrate();

        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'inquiries' and indexname like 'inquiries_%idx'",
                String.class);
        assertThat(indexes).contains("inquiries_no_reply_idx").doesNotContain("inquiries_result_confirmation_idx");
        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'inquiries_no_reply_idx'", String.class);
        assertThat(definition).contains("RESULT_CONFIRMATION").contains("ASKER_REPLY");
    }
}
