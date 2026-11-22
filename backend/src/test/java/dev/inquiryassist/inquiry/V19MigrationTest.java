package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

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
 * V19（削った機能の列を落とす）が、先例の {@code recorded_by} と {@code confirmed}、問い合わせの {@code translations} を落とし、
 * 先例と問い合わせの行を残すことを確かめる。V18 までを適用した空のデータベースに行を入れてから、V19 を適用する。
 */
class V19MigrationTest {

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
    void migrateToV18() {
        String name = "v19_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("18").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = ?", String.class, table);
    }

    private UUID insertInquiry() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, translations) values (?::uuid, 'local-user', 'INTAKE', ?::jsonb)",
                id.toString(),
                "{\"ja\":{\"abc\":\"訳\"}}");
        return id;
    }

    private UUID insertPrecedent(UUID inquiry, String recordedBy, boolean confirmed) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into precedents (id, inquiry_id, type, system_id, function_id, situation, decision_kind, decision, reason,"
                        + " decided_by, recorded_by, confirmed, language, decided_at, updated_at)"
                        + " values (?::uuid, ?::uuid, 'HOW_TO', 's', 'f', '状況', 'ACCEPT', '決定', '理由', '決めた人', ?, ?, 'ja', now(), now())",
                id.toString(),
                inquiry.toString(),
                recordedBy,
                confirmed);
        return id;
    }

    @Test
    void 列が落ち_先例と問い合わせの行は残る() {
        UUID inquiry = insertInquiry();
        UUID owner = insertPrecedent(inquiry, "OWNER", true);
        UUID operator = insertPrecedent(inquiry, "OPERATOR", false);
        assertThat(columns("precedents")).contains("recorded_by", "confirmed");
        assertThat(columns("inquiries")).contains("translations");

        flyway("19").migrate();

        assertThat(columns("precedents")).doesNotContain("recorded_by", "confirmed");
        assertThat(columns("inquiries")).doesNotContain("translations");
        // 先例と問い合わせの行、ほかの列は、そのまま残る
        assertThat(jdbc.queryForObject("select count(*) from precedents", Integer.class)).isEqualTo(2);
        for (UUID id : new UUID[] {owner, operator}) {
            var row = jdbc.queryForMap("select * from precedents where id = ?::uuid", id.toString());
            assertThat(row.get("decision")).isEqualTo("決定");
            assertThat(row.get("decided_by")).isEqualTo("決めた人");
            assertThat(row.get("system_id")).isEqualTo("s");
            assertThat(row.get("inquiry_id").toString()).isEqualTo(inquiry.toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from inquiries where id = ?::uuid", Integer.class, inquiry.toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select phase from inquiries where id = ?::uuid", String.class, inquiry.toString()))
                .isEqualTo("INTAKE");
    }

    @Test
    void 落とした後は_その列なしで先例を入れられ_マスの索引は残る() {
        UUID inquiry = insertInquiry();
        flyway("19").migrate();

        jdbc.update(
                "insert into precedents (id, inquiry_id, situation, decision_kind, decision, reason, decided_by, language,"
                        + " decided_at, updated_at)"
                        + " values (gen_random_uuid(), ?::uuid, '状況', 'AS_INTENDED', '決定', '理由', '持ち主', 'ja', now(), now())",
                inquiry.toString());

        assertThat(jdbc.queryForObject("select count(*) from precedents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename = 'precedents'", String.class))
                .contains("precedents_cell_idx");
    }
}
