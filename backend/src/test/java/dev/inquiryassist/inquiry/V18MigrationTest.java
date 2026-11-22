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

/** V18（担当者が記録した先例）が、既存の先例を変えず、{@code recorded_by} に {@code OPERATOR} を許すことを確かめる。 */
class V18MigrationTest {

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
    void migrateToV17() {
        String name = "v18_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("17").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private UUID inquiry() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inquiries (id, asker_id, phase) values (?::uuid, 'local-user', 'INTAKE')", id.toString());
        return id;
    }

    private void precedent(UUID inquiry, String recordedBy) {
        jdbc.update(
                "insert into precedents (id, inquiry_id, situation, decision_kind, decision, reason, decided_by, recorded_by,"
                        + " confirmed, language, decided_at, updated_at)"
                        + " values (gen_random_uuid(), ?::uuid, 's', 'ACCEPT', 'd', 'r', 'o', ?, true, 'ja', now(), now())",
                inquiry.toString(),
                recordedBy);
    }

    @Test
    void 既存の先例は変わらず_OPERATORを許し_そのほかの値は許さない() {
        UUID inquiry = inquiry();
        precedent(inquiry, "OWNER");
        assertThatThrownBy(() -> precedent(inquiry, "OPERATOR")).hasMessageContaining("recorded_by");

        flyway("18").migrate();

        assertThat(jdbc.queryForObject("select count(*) from precedents where recorded_by = 'OWNER'", Integer.class)).isEqualTo(1);
        precedent(inquiry, "OPERATOR");
        assertThat(jdbc.queryForObject("select count(*) from precedents", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> precedent(inquiry, "AI")).hasMessageContaining("recorded_by");
    }
}
