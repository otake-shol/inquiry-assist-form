package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** V14（AI の案の列）が、既存の行を作り直さず {@code NOT_RUN}、中身なしにすることを確かめる。V13 までを適用した空のデータベースに行を入れてから適用する。 */
class V14MigrationTest {

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
    void migrateToV13() {
        String name = "v14_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("13").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private UUID insert(boolean withResult) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, awaiting, turn, technical_memo, answer_grounded)"
                        + " values (?::uuid, 'local-user', 'KNOW', 'HANDLING', 'OPERATOR', ?::jsonb, ?)",
                id.toString(),
                withResult ? "{\"findings\":[\"事実\"]}" : null,
                withResult ? Boolean.TRUE : null);
        return id;
    }

    @Test
    void 既存の行はNOT_RUNで案の中身はなく_調査の結果を持つ行も作り直さない() {
        UUID withResult = insert(true);
        UUID without = insert(false);

        flyway("14").migrate();

        for (UUID id : new UUID[] {withResult, without}) {
            var row = jdbc.queryForMap("select advice_status, advice from inquiries where id = ?::uuid", id.toString());
            assertThat(row.get("advice_status")).isEqualTo("NOT_RUN");
            assertThat(row.get("advice")).isNull();
        }
    }

    @Test
    void 案の状況は決まった値だけを許し_中身はJSONを持てる() {
        flyway("14").migrate();
        UUID id = insert(false);

        jdbc.update(
                "update inquiries set advice_status = 'DONE', advice = '{\"judgment\":null,\"procedure\":null}'::jsonb"
                        + " where id = ?::uuid",
                id.toString());
        assertThat(jdbc.queryForObject("select advice_status from inquiries where id = ?::uuid", String.class, id.toString()))
                .isEqualTo("DONE");
        assertThatThrownBy(() -> jdbc.update(
                        "update inquiries set advice_status = 'STOPPED' where id = ?::uuid", id.toString()))
                .hasMessageContaining("advice_status");
    }
}
