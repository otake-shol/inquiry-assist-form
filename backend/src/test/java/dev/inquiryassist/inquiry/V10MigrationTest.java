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

/** V10（言語の列）が、既存の記録を日本語（ja）にし、これから作る記録の言語を必須にすることを確かめる。 */
class V10MigrationTest {

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
    void migrateToV9() {
        String name = "v10_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("9").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private UUID insert() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inquiries (id, asker_id, phase) values (?::uuid, 'local-user', 'INTAKE')", id.toString());
        return id;
    }

    @Test
    void 既存の記録の言語はjaになる() {
        UUID first = insert();
        UUID second = insert();

        flyway("10").migrate();

        assertThat(jdbc.queryForObject("select language from inquiries where id = ?::uuid", String.class, first.toString()))
                .isEqualTo("ja");
        assertThat(jdbc.queryForObject("select language from inquiries where id = ?::uuid", String.class, second.toString()))
                .isEqualTo("ja");
    }

    @Test
    void 言語の列は空にできず_言語を指定して作れる() {
        flyway("10").migrate();

        jdbc.update("insert into inquiries (id, asker_id, phase, language) values (gen_random_uuid(), 'u', 'INTAKE', 'en')");
        assertThat(jdbc.queryForObject("select count(*) from inquiries where language = 'en'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, asker_id, phase, language) values (gen_random_uuid(), 'u', 'INTAKE', null)"))
                .hasMessageContaining("language");
    }
}
