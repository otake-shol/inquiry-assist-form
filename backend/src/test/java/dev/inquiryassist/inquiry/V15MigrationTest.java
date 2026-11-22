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

/** V15（説明が出す手順の列）が、既存の行を手順なし（空の一覧）にし、空にできない列であることを確かめる。 */
class V15MigrationTest {

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
    void migrateToV14() {
        String name = "v15_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("14").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void 既存の行は手順なしで_答えを持つ行も作り直さない() {
        UUID withAnswer = UUID.randomUUID();
        UUID withoutAnswer = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, asker_explanation, answer_grounded)"
                        + " values (?::uuid, 'local-user', 'KNOW', '説明', true)",
                withAnswer.toString());
        jdbc.update("insert into inquiries (id, asker_id, phase) values (?::uuid, 'local-user', 'INTAKE')", withoutAnswer.toString());

        flyway("15").migrate();

        for (UUID id : new UUID[] {withAnswer, withoutAnswer}) {
            assertThat(jdbc.queryForObject("select answer_steps::text from inquiries where id = ?::uuid", String.class, id.toString()))
                    .isEqualTo("[]");
        }
        assertThat(jdbc.queryForObject("select asker_explanation from inquiries where id = ?::uuid", String.class, withAnswer.toString()))
                .isEqualTo("説明");
    }

    @Test
    void 手順はJSONの一覧を持て_nullにはできない() {
        flyway("15").migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inquiries (id, asker_id, phase) values (?::uuid, 'local-user', 'INTAKE')", id.toString());

        jdbc.update(
                "update inquiries set answer_steps = '[{\"action\":\"a\",\"check\":null}]'::jsonb where id = ?::uuid",
                id.toString());
        assertThat(jdbc.queryForObject("select answer_steps -> 0 ->> 'action' from inquiries where id = ?::uuid", String.class, id.toString()))
                .isEqualTo("a");
        assertThatThrownBy(() -> jdbc.update("update inquiries set answer_steps = null where id = ?::uuid", id.toString()))
                .hasMessageContaining("answer_steps");
    }
}
