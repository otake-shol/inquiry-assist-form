package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
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
 * V9（状態の作り替え）が、V8 までの形の記録（段階、結果、対応状況）を、区間、誰の番か、待っていること、終わり方に
 * 読み替えることを確かめる。V8 までを適用した空のデータベースに古い形の行を入れてから、V9 を適用する。
 */
class V9MigrationTest {

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
    void migrateToV8() {
        String name = "v9_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("8").migrate();
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

    private UUID legacy(String step, String outcome, String handlingStatus, String type, boolean filed) {
        return legacy(step, outcome, handlingStatus, type, filed, "[]");
    }

    private UUID legacy(String step, String outcome, String handlingStatus, String type, boolean filed, String messages) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, step, outcome, handling_status, asker_id, intake, filed_at, messages)"
                        + " values (?::uuid, ?, ?, ?, 'local-user', ?::jsonb, ?, ?::jsonb)",
                id.toString(),
                step,
                outcome,
                handlingStatus,
                intakeOf(type),
                filed ? OffsetDateTime.parse("2026-11-20T03:00:00Z") : null,
                messages);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select * from inquiries where id = ?::uuid", id.toString());
    }

    private void assertState(UUID id, String phase, String turn, String awaiting, String ending) {
        Map<String, Object> r = row(id);
        assertThat(r.get("phase")).isEqualTo(phase);
        assertThat(r.get("turn")).isEqualTo(turn);
        assertThat(r.get("awaiting")).isEqualTo(awaiting);
        assertThat(r.get("ending")).isEqualTo(ending);
    }

    @Test
    void 終わっていない件は段階から区間と番と待っていることに読み替える() {
        UUID intake = legacy("INTAKE", "IN_PROGRESS", null, null, false);
        UUID structured = legacy("STRUCTURED", "IN_PROGRESS", null, "HOW_TO", false);
        UUID answering = legacy("ANSWERING", "IN_PROGRESS", null, "HOW_TO", false);
        UUID answered = legacy("ANSWERED", "IN_PROGRESS", null, "HOW_TO", false);

        flyway("9").migrate();

        assertState(intake, "INTAKE", "ASKER", "INTAKE_ANSWER", null);
        assertState(structured, "INTAKE", "ASKER", "QUESTION_CONFIRMATION", null);
        assertState(answering, "KNOW", "AI", "INVESTIGATION", null);
        assertState(answered, "KNOW", "ASKER", "RESULT_CONFIRMATION", null);
        assertThat(row(intake).get("handed_over_at")).isNull();
    }

    @Test
    void 自己解決は解決済み_離脱は取り下げに読み替える() {
        UUID resolved = legacy("CLOSED", "SELF_RESOLVED", null, "HOW_TO", false);
        UUID abandoned = legacy("CLOSED", "ABANDONED", null, "HOW_TO", false);

        flyway("9").migrate();

        assertState(resolved, "CLOSED", null, null, "RESOLVED");
        assertState(abandoned, "CLOSED", null, null, "WITHDRAWN");
    }

    @Test
    void 担当者に送られ完了していない件は担当者の番になり_区間は種類で決まる() {
        UUID open = legacy("CLOSED", "FILED", "OPEN", "HOW_TO", true);
        UUID inProgress = legacy("CLOSED", "FILED", "IN_PROGRESS", "LOOKS_WRONG", true);
        UUID unset = legacy("CLOSED", "FILED", null, null, true);
        UUID howItWorks = legacy("CLOSED", "FILED", "OPEN", "HOW_IT_WORKS", true);
        UUID doForMe = legacy("CLOSED", "FILED", "OPEN", "DO_FOR_ME", true);
        UUID change = legacy("CLOSED", "FILED", "OPEN", "CHANGE_REQUEST", true);

        flyway("9").migrate();

        assertState(open, "KNOW", "OPERATOR", "HANDLING", null);
        assertState(inProgress, "DECIDE", "OPERATOR", "HANDLING", null);
        // 対応状況が未設定、種類が決まっていない件は KNOW
        assertState(unset, "KNOW", "OPERATOR", "HANDLING", null);
        assertState(howItWorks, "KNOW", "OPERATOR", "HANDLING", null);
        assertState(doForMe, "DECIDE", "OPERATOR", "HANDLING", null);
        assertState(change, "DECIDE", "OPERATOR", "HANDLING", null);
        // 担当者に初めて渡した時刻は、以前の filed_at
        assertThat(((java.sql.Timestamp) row(open).get("handed_over_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-20T03:00:00Z").toInstant());
        assertThat(row(open).get("phase_before_closed")).isNull();
    }

    @Test
    void 担当者が完了にした件は回答済みで終わり_終わる前の区間を持つ() {
        UUID know = legacy("CLOSED", "FILED", "DONE", "HOW_TO", true);
        UUID decide = legacy("CLOSED", "FILED", "DONE", "DO_FOR_ME", true);

        flyway("9").migrate();

        assertState(know, "CLOSED", null, null, "ANSWERED");
        assertState(decide, "CLOSED", null, null, "ANSWERED");
        assertThat(row(know).get("phase_before_closed")).isEqualTo("KNOW");
        assertThat(row(decide).get("phase_before_closed")).isEqualTo("DECIDE");
        assertThat(row(know).get("handed_over_at")).isNotNull();
    }

    @Test
    void 既存のメッセージは担当者の発言を結果_本人の追記を追記に読み替え_履歴は空() {
        String messages = "[{\"id\":\"a\",\"from\":\"OPERATOR\",\"authorName\":\"担当者\",\"body\":\"1\",\"createdAt\":\"2026-11-21T00:00:00Z\"},"
                + "{\"id\":\"b\",\"from\":\"ASKER\",\"authorName\":\"本人\",\"body\":\"2\",\"createdAt\":\"2026-11-21T00:01:00Z\"},"
                + "{\"id\":\"c\",\"from\":\"OPERATOR\",\"authorName\":\"担当者\",\"body\":\"3\",\"createdAt\":\"2026-11-21T00:02:00Z\"}]";
        UUID id = legacy("CLOSED", "FILED", "IN_PROGRESS", "HOW_TO", true, messages);
        UUID none = legacy("INTAKE", "IN_PROGRESS", null, null, false);

        flyway("9").migrate();

        String migrated = jdbc.queryForObject("select messages::text from inquiries where id = ?::uuid", String.class, id.toString());
        assertThat(JsonPath.<List<String>>read(migrated, "$[*].kind")).containsExactly("RESULT", "NOTE", "RESULT");
        assertThat(JsonPath.<List<String>>read(migrated, "$[*].body")).containsExactly("1", "2", "3");
        assertThat(JsonPath.<String>read(migrated, "$[0].authorName")).isEqualTo("担当者");
        assertThat(jdbc.queryForObject("select messages::text from inquiries where id = ?::uuid", String.class, none.toString()))
                .isEqualTo("[]");
        assertThat(jdbc.queryForObject("select history::text from inquiries where id = ?::uuid", String.class, id.toString()))
                .isEqualTo("[]");
        assertThat(row(id).get("declined")).isNull();
        assertThat(row(id).get("placement_override")).isNull();
    }

    @Test
    void 使わなくなった列は落ち_新しい列の制約が効く() {
        flyway("9").migrate();

        List<String> dropped = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'inquiries'"
                        + " and column_name in ('step', 'outcome', 'handling_status', 'filed_at')",
                String.class);
        assertThat(dropped).isEmpty();
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, asker_id, phase) values (gen_random_uuid(), 'x', 'TRIAGED')"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, asker_id, phase, ending) values (gen_random_uuid(), 'x', 'CLOSED', 'FILED')"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, asker_id, phase, awaiting) values (gen_random_uuid(), 'x', 'INTAKE', 'WAITING')"))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.update(
                        "insert into inquiries (id, asker_id, phase, turn, awaiting) values (gen_random_uuid(), 'x', 'INTAKE', 'ASKER', 'INTAKE_ANSWER')"))
                .isEqualTo(1);
    }
}
