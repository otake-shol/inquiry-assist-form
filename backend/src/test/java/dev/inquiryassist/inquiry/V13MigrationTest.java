package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
 * V13（調査の状況と、渡した理由）が、既存の行の調査の状況と、渡した理由を読み替えることを確かめる。V12 までを適用した空のデータベースに
 * 行を入れてから、V13 を適用する。
 */
class V13MigrationTest {

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
    void migrateToV12() {
        String name = "v13_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("12").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private static String intakeOf(String type) {
        if (type == null) {
            return "[]";
        }
        return "[{\"questionId\":\"type\",\"kind\":\"TYPE\",\"text\":\"何をしたいですか\","
                + "\"answer\":{\"kind\":\"OPTION\",\"optionId\":\"" + type + "\",\"label\":\"x\"}}]";
    }

    /** 担当者に渡した行（{@code handedOver}）か、渡していない行を入れる。 */
    private UUID insert(boolean handedOver, boolean sentImmediately, String urgency, String type, String technicalMemo, Boolean grounded) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, urgency, sent_immediately, intake, handed_over_at, technical_memo, answer_grounded)"
                        + " values (?::uuid, 'local-user', 'KNOW', ?, ?, ?::jsonb, ?, ?::jsonb, ?)",
                id.toString(),
                urgency,
                sentImmediately,
                intakeOf(type),
                handedOver ? Timestamp.from(Instant.parse("2026-11-20T03:00:00Z")) : null,
                technicalMemo,
                grounded);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select * from inquiries where id = ?::uuid", id.toString());
    }

    @Test
    void 調査の結果が保存されている行はDONEで本人向け_ないものはNOT_RUN() {
        UUID withMemo = insert(false, false, "NORMAL", "HOW_TO", "{\"findings\":[]}", null);
        UUID withGrounded = insert(false, false, "NORMAL", "HOW_TO", null, false);
        UUID none = insert(false, false, "NORMAL", "HOW_TO", null, null);

        flyway("13").migrate();

        for (UUID id : new UUID[] {withMemo, withGrounded}) {
            assertThat(row(id).get("investigation_status")).isEqualTo("DONE");
            assertThat(row(id).get("investigation_audience")).isEqualTo("ASKER");
        }
        assertThat(row(none).get("investigation_status")).isEqualTo("NOT_RUN");
        assertThat(row(none).get("investigation_audience")).isNull();
        assertThat(row(none).get("investigation_started_at")).isNull();
        assertThat(row(none).get("investigation_finished_at")).isNull();
        assertThat(row(none).get("investigation_unconfirmed_question")).isEqualTo(false);
    }

    @Test
    void 渡した理由は今すぐ送った件を緊急度で分け_それ以外は種類の経路かUNRESOLVED() {
        UUID blocked = insert(true, true, "BLOCKED", "HOW_TO", null, null);
        UUID sentNow = insert(true, true, "NORMAL", "LOOKS_WRONG", null, null);
        UUID sentNowHurry = insert(true, true, "HURRY", null, null, null);
        UUID wrong = insert(true, false, "NORMAL", "LOOKS_WRONG", null, null);
        UUID doForMe = insert(true, false, "NORMAL", "DO_FOR_ME", null, null);
        UUID change = insert(true, false, "NORMAL", "CHANGE_REQUEST", null, null);
        UUID howTo = insert(true, false, "NORMAL", "HOW_TO", null, null);
        UUID howItWorks = insert(true, false, "NORMAL", "HOW_IT_WORKS", null, null);
        UUID undecided = insert(true, false, "NORMAL", null, null, null);
        UUID notHanded = insert(false, false, "NORMAL", "LOOKS_WRONG", null, null);

        flyway("13").migrate();

        assertThat(row(blocked).get("handover_reason")).isEqualTo("BLOCKED");
        assertThat(row(sentNow).get("handover_reason")).isEqualTo("SENT_NOW");
        assertThat(row(sentNowHurry).get("handover_reason")).isEqualTo("SENT_NOW");
        assertThat(row(wrong).get("handover_reason")).isEqualTo("ROUTE");
        assertThat(row(doForMe).get("handover_reason")).isEqualTo("ROUTE");
        assertThat(row(change).get("handover_reason")).isEqualTo("ROUTE");
        assertThat(row(howTo).get("handover_reason")).isEqualTo("UNRESOLVED");
        assertThat(row(howItWorks).get("handover_reason")).isEqualTo("UNRESOLVED");
        assertThat(row(undecided).get("handover_reason")).isEqualTo("UNRESOLVED");
        assertThat(row(notHanded).get("handover_reason")).isNull();
    }

    @Test
    void 担当者が置き直した種類があれば_それで渡した理由を決める() {
        UUID id = insert(true, false, "NORMAL", "HOW_TO", null, null);
        jdbc.update("update inquiries set placement_override = '{\"type\":\"DO_FOR_ME\"}'::jsonb where id = ?::uuid", id.toString());

        flyway("13").migrate();

        assertThat(row(id).get("handover_reason")).isEqualTo("ROUTE");
    }

    @Test
    void 値は決まった集合だけを許す() {
        flyway("13").migrate();
        UUID id = insert(false, false, "NORMAL", null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("update inquiries set handover_reason = 'OTHER' where id = ?::uuid", id.toString()))
                .hasMessageContaining("handover_reason");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("update inquiries set investigation_status = 'STOPPED' where id = ?::uuid", id.toString()))
                .hasMessageContaining("investigation_status");
    }
}
