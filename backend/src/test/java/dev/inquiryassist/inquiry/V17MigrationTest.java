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
 * V17（仕様の持ち主への相談と先例）が、既存の行で制約が通ること、新しい値を許すこと、相談の列と先例のテーブルを足すことを確かめる。
 * V16 までを適用した空のデータベースに行を入れてから、V17 を適用する。
 */
class V17MigrationTest {

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
    void migrateToV16() {
        String name = "v17_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("16").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private UUID insert(String phase, String turn, String awaiting, String ending, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, turn, awaiting, ending, handover_reason, closed_by)"
                        + " values (?::uuid, 'local-user', ?, ?, ?, ?, ?, ?)",
                id.toString(),
                phase,
                turn,
                awaiting,
                ending,
                reason,
                ending == null ? null : "ASKER");
        return id;
    }

    @Test
    void 既存の行は変わらず_相談の列は空の一覧になる() {
        List<UUID> ids = List.of(
                insert("INTAKE", "ASKER", "INTAKE_ANSWER", null, null),
                insert("KNOW", "AI", "INVESTIGATION", null, null),
                insert("KNOW", "ASKER", "RESULT_CONFIRMATION", null, null),
                insert("DECIDE", "OPERATOR", "HANDLING", null, "ROUTE"),
                insert("DECIDE", "ASKER", "ASKER_REPLY", null, "TIMEOUT"),
                insert("CLOSED", null, null, "RESOLVED", null),
                insert("CLOSED", null, null, "WITHDRAWN", "REOPENED"));

        flyway("17").migrate();

        for (UUID id : ids) {
            assertThat(jdbc.queryForObject("select consultations::text from inquiries where id = ?::uuid", String.class, id.toString()))
                    .isEqualTo("[]");
        }
        assertThat(jdbc.queryForObject("select count(*) from inquiries", Integer.class)).isEqualTo(ids.size());
        assertThat(jdbc.queryForObject("select turn from inquiries where id = ?::uuid", String.class, ids.get(3).toString()))
                .isEqualTo("OPERATOR");
    }

    @Test
    void 新しい値の行を入れられ_そうでない値は引き続き許さない() {
        flyway("17").migrate();

        insert("DECIDE", "OWNER", "CONSULTATION", null, "DECIDED");
        assertThat(jdbc.queryForObject("select count(*) from inquiries where turn = 'OWNER' and awaiting = 'CONSULTATION'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> insert("DECIDE", "BOSS", "HANDLING", null, null)).hasMessageContaining("turn");
        assertThatThrownBy(() -> insert("DECIDE", "OPERATOR", "WAITING", null, null)).hasMessageContaining("awaiting");
        assertThatThrownBy(() -> insert("DECIDE", "OPERATOR", "HANDLING", null, "OTHER")).hasMessageContaining("handover_reason");
        // closed_by は変えない（持ち主は問い合わせを終わらせない）
        UUID id = insert("CLOSED", null, null, "RESOLVED", null);
        assertThatThrownBy(() -> jdbc.update("update inquiries set closed_by = 'OWNER' where id = ?::uuid", id.toString()))
                .hasMessageContaining("closed_by");
    }

    @Test
    void 先例のテーブルは問い合わせに紐づき_決めた時点のマスをnullでも持て_マスの索引がある() {
        flyway("17").migrate();
        UUID inquiry = insert("DECIDE", "OPERATOR", "HANDLING", null, "ROUTE");

        jdbc.update(
                "insert into precedents (id, inquiry_id, situation, decision_kind, decision, reason, decided_by, recorded_by,"
                        + " confirmed, language, decided_at, updated_at)"
                        + " values (gen_random_uuid(), ?::uuid, '状況', 'AS_INTENDED', '決定', '理由', '持ち主', 'OWNER', true, 'ja', now(), now())",
                inquiry.toString());
        assertThat(jdbc.queryForObject("select count(*) from precedents where type is null and system_id is null", Integer.class))
                .isEqualTo(1);
        // 存在しない問い合わせの先例、決まった集合にない決定の種類は入れられない
        assertThatThrownBy(() -> jdbc.update(
                        "insert into precedents (id, inquiry_id, situation, decision_kind, decision, reason, decided_by, recorded_by,"
                                + " confirmed, language, decided_at, updated_at)"
                                + " values (gen_random_uuid(), gen_random_uuid(), 's', 'AS_INTENDED', 'd', 'r', 'o', 'OWNER', true, 'ja', now(), now())"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into precedents (id, inquiry_id, situation, decision_kind, decision, reason, decided_by, recorded_by,"
                                + " confirmed, language, decided_at, updated_at)"
                                + " values (gen_random_uuid(), ?::uuid, 's', 'MAYBE', 'd', 'r', 'o', 'OWNER', true, 'ja', now(), now())",
                        inquiry.toString()))
                .hasMessageContaining("decision_kind");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename = 'precedents'", String.class))
                .contains("precedents_cell_idx");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'precedents_cell_idx'", String.class))
                .contains("system_id", "function_id", "type");
    }
}
