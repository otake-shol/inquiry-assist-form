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

/** V16（担当者のための訳の列）が、既存の行を訳なし（空のオブジェクト）にし、空にできない列であることを確かめる。 */
class V16MigrationTest {

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
    void migrateToV15() {
        String name = "v16_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("15").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void 既存の行は訳なしになり_ほかの列は変わらない() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, language) values (?::uuid, 'local-user', 'KNOW', 'en')", id.toString());

        flyway("16").migrate();

        assertThat(jdbc.queryForObject("select translations::text from inquiries where id = ?::uuid", String.class, id.toString()))
                .isEqualTo("{}");
        assertThat(jdbc.queryForObject("select language from inquiries where id = ?::uuid", String.class, id.toString())).isEqualTo("en");
    }

    @Test
    void 訳はJSONのオブジェクトを持て_nullにはできない() {
        flyway("16").migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inquiries (id, asker_id, phase) values (?::uuid, 'local-user', 'INTAKE')", id.toString());

        jdbc.update("update inquiries set translations = '{\"ja\":{\"abc\":\"訳\"}}'::jsonb where id = ?::uuid", id.toString());
        assertThat(jdbc.queryForObject("select translations -> 'ja' ->> 'abc' from inquiries where id = ?::uuid", String.class, id.toString()))
                .isEqualTo("訳");
        assertThatThrownBy(() -> jdbc.update("update inquiries set translations = null where id = ?::uuid", id.toString()))
                .hasMessageContaining("translations");
    }
}
