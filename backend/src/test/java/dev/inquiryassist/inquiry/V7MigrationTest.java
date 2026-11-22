package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.intake.IntakeEntry;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * V7（入口の作り替え）が、V6 までの形の記録を新しい形に読み替えることを確かめる。V6 までを適用した空のデータベースに
 * 古い形の行を入れてから、V7 を適用する。アプリケーションの文脈は起動せず、Flyway を直接使う。
 */
class V7MigrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
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

    /** テストごとに新しいデータベースを作り、V6 までを適用する。 */
    @BeforeEach
    void migrateToV6() {
        String name = "v7_test_" + (++databases);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("6").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static final String QUESTIONS = "[{\"id\":\"q1\",\"text\":\"どの画面ですか\",\"options\":[\"求人管理画面\"],\"answer\":\"求人管理画面\"},"
            + "{\"id\":\"q2\",\"text\":\"いつからですか\",\"options\":[],\"answer\":null}]";

    private static String structured(String category) {
        return "{\"summary\":\"言い直し\",\"category\":\"" + category
                + "\",\"targetSystem\":\"求人管理画面\",\"goal\":\"目的\",\"whatHappened\":null,"
                + "\"expectedResult\":null,\"urgency\":\"HIGH\"}";
    }

    private UUID legacy(
            String step,
            String byAi,
            String confirmed,
            String outcome,
            String systemId,
            boolean guessed,
            String followUps,
            boolean submitted,
            String structured) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, step, asker_id, utterance, category_by_ai, category_confirmed, follow_ups,"
                        + " answers_submitted, structured_by_ai, structured_confirmed, outcome, system_id, system_label,"
                        + " system_guessed) values (?::uuid, ?, 'local-user', '元の発話', ?, ?, ?::jsonb, ?, ?::jsonb,"
                        + " ?::jsonb, ?, ?, ?, ?)",
                id.toString(),
                step,
                byAi,
                confirmed,
                followUps,
                submitted,
                structured,
                structured,
                outcome,
                systemId,
                systemId == null ? null : "システム名",
                guessed);
        return id;
    }

    private String json(UUID id, String column) {
        return jdbc.queryForObject("select " + column + "::text from inquiries where id = ?::uuid", String.class, id.toString());
    }

    private String value(UUID id, String column) {
        return jdbc.queryForObject("select " + column + " from inquiries where id = ?::uuid", String.class, id.toString());
    }

    @Test
    void 種別は種類に読み替え_障害は緊急度BLOCKEDになる() {
        UUID spec = legacy("CLOSED", "SPEC_QUESTION", "SPEC_QUESTION", "SELF_RESOLVED", null, false, "[]", false, null);
        UUID bug = legacy("CLOSED", "SUSPECTED_BUG", "SUSPECTED_BUG", "FILED", null, false, "[]", false, null);
        UUID work = legacy("CLOSED", "WORK_REQUEST", "WORK_REQUEST", "FILED", null, false, "[]", false, null);
        UUID incident = legacy("CLOSED", "INCIDENT", "INCIDENT", "INCIDENT_REDIRECTED", null, false, "[]", true, null);

        flyway("latest").migrate();

        assertThat(JsonPath.<String>read(json(spec, "intake"), "$[0].answer.optionId")).isEqualTo("HOW_IT_WORKS");
        assertThat(JsonPath.<String>read(json(bug, "intake"), "$[0].answer.optionId")).isEqualTo("LOOKS_WRONG");
        assertThat(JsonPath.<String>read(json(work, "intake"), "$[0].answer.optionId")).isEqualTo("DO_FOR_ME");
        assertThat(JsonPath.<String>read(json(incident, "intake"), "$[0].answer.optionId")).isEqualTo("LOOKS_WRONG");
        assertThat(value(spec, "urgency")).isEqualTo("NORMAL");
        assertThat(value(bug, "urgency")).isEqualTo("NORMAL");
        assertThat(value(work, "urgency")).isEqualTo("NORMAL");
        assertThat(value(incident, "urgency")).isEqualTo("BLOCKED");
        // 種類の名前は、そのときの名前を答えに残す
        assertThat(JsonPath.<String>read(json(work, "intake"), "$[0].answer.label")).isEqualTo("やってほしい");
    }

    @Test
    void 置いた人は_本人が種別を変更していれば本人_そうでなければAI() {
        UUID untouched = legacy("STRUCTURED", "SUSPECTED_BUG", "SUSPECTED_BUG", "IN_PROGRESS", null, false, "[]", true, null);
        UUID changed = legacy("STRUCTURED", "SPEC_QUESTION", "SUSPECTED_BUG", "IN_PROGRESS", null, false, "[]", true, null);

        flyway("latest").migrate();

        assertThat(JsonPath.<String>read(json(untouched, "intake"), "$[0].answer.kind")).isEqualTo("INFERRED");
        assertThat(JsonPath.<String>read(json(changed, "intake"), "$[0].answer.kind")).isEqualTo("OPTION");
    }

    @Test
    void 対象システムの推測かどうかは置いた人に移り_決まっていなければ入口が進んでいるものはわからないになる() {
        UUID guessed = legacy("STRUCTURED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", "billing", true, "[]", true, null);
        UUID chosen = legacy("STRUCTURED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", "job-admin", false, "[]", true, null);
        UUID none = legacy("STRUCTURED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", null, false, "[]", true, null);
        UUID triagedNone = legacy("TRIAGED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", null, false, "[]", false, null);

        flyway("latest").migrate();

        assertThat(JsonPath.<String>read(json(guessed, "intake"), "$[1].answer.kind")).isEqualTo("INFERRED");
        assertThat(JsonPath.<String>read(json(guessed, "intake"), "$[1].answer.optionId")).isEqualTo("billing");
        assertThat(JsonPath.<String>read(json(guessed, "intake"), "$[1].answer.label")).isEqualTo("システム名");
        assertThat(JsonPath.<String>read(json(chosen, "intake"), "$[1].answer.kind")).isEqualTo("OPTION");
        assertThat(JsonPath.<String>read(json(none, "intake"), "$[1].answer.kind")).isEqualTo("UNKNOWN");
        // 入口の途中だった記録は、システムの質問から続ける（答えのないものは「わからない」にしない）
        assertThat(JsonPath.<List<?>>read(json(triagedNone, "intake"), "$")).hasSize(1);
    }

    @Test
    void 段階TRIAGEDはINTAKEになり_入口の答えは空から始め_結果INCIDENT_REDIRECTEDはABANDONEDになる() {
        UUID triaged = legacy("TRIAGED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", null, false, QUESTIONS, false, null);
        UUID failedTriage = legacy("TRIAGED", null, null, "IN_PROGRESS", null, false, "[]", false, null);
        UUID redirected = legacy("CLOSED", "INCIDENT", "INCIDENT", "INCIDENT_REDIRECTED", null, false, "[]", true, null);
        UUID closed = legacy("CLOSED", "SUSPECTED_BUG", "SUSPECTED_BUG", "FILED", null, false, "[]", false, null);

        flyway("latest").migrate();

        assertThat(value(triaged, "step")).isEqualTo("INTAKE");
        assertThat(value(failedTriage, "step")).isEqualTo("INTAKE");
        assertThat(value(closed, "step")).isEqualTo("CLOSED");
        // 送信していない追加質問は移さず、種類が決まっていなければ答えは空
        assertThat(JsonPath.<List<?>>read(json(triaged, "intake"), "$")).hasSize(1);
        assertThat(JsonPath.<List<?>>read(json(failedTriage, "intake"), "$")).isEmpty();
        assertThat(value(redirected, "outcome")).isEqualTo("ABANDONED");
        assertThat(value(closed, "outcome")).isEqualTo("FILED");
        // 入口の途中だった記録は、聞き手を呼び直さずに続けられる
        assertThat(json(triaged, "detail_questions")).isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                        "select detail_questions is null from inquiries where id = ?::uuid", Boolean.class, closed.toString()))
                .isTrue();
    }

    @Test
    void 送信済みの追加質問と回答は詳しい質問への答えとして移り_本人が書いた発話は文章に入る() {
        UUID id = legacy("STRUCTURED", "SPEC_QUESTION", "SPEC_QUESTION", "IN_PROGRESS", "job-admin", false, QUESTIONS, true, null);

        flyway("latest").migrate();

        String intake = json(id, "intake");
        assertThat(JsonPath.<List<String>>read(intake, "$[*].questionId")).containsExactly("type", "system", "q1", "q2");
        assertThat(JsonPath.<List<String>>read(intake, "$[*].kind")).containsExactly("TYPE", "SYSTEM", "DETAIL", "DETAIL");
        assertThat(JsonPath.<String>read(intake, "$[2].text")).isEqualTo("どの画面ですか");
        assertThat(JsonPath.<String>read(intake, "$[2].answer.kind")).isEqualTo("TEXT");
        assertThat(JsonPath.<String>read(intake, "$[2].answer.text")).isEqualTo("求人管理画面");
        assertThat(JsonPath.<String>read(intake, "$[3].answer.kind")).isEqualTo("UNKNOWN");
        assertThat(JsonPath.<List<String>>read(json(id, "texts"), "$")).containsExactly("元の発話");
    }

    @Test
    void 整形後の問いは種類を持ち_緊急度を持たない形に読み替える() {
        UUID id = legacy("STRUCTURED", "SUSPECTED_BUG", "SUSPECTED_BUG", "IN_PROGRESS", null, false, "[]", true, structured("SUSPECTED_BUG"));
        UUID incident = legacy("CLOSED", "INCIDENT", "INCIDENT", "INCIDENT_REDIRECTED", null, false, "[]", true, structured("INCIDENT"));

        flyway("latest").migrate();

        String migrated = json(id, "structured_by_ai");
        assertThat(JsonPath.<String>read(migrated, "$.type")).isEqualTo("LOOKS_WRONG");
        assertThat(migrated).doesNotContain("category").doesNotContain("urgency");
        assertThat(JsonPath.<String>read(json(id, "structured_confirmed"), "$.type")).isEqualTo("LOOKS_WRONG");
        assertThat(JsonPath.<String>read(json(incident, "structured_by_ai"), "$.type")).isEqualTo("LOOKS_WRONG");
        // 新しい形として読める
        StructuredInquiry read = JSON.readValue(migrated, StructuredInquiry.class);
        assertThat(read.type()).isEqualTo(InquiryType.LOOKS_WRONG);
        assertThat(read.summary()).isEqualTo("言い直し");
        assertThat(read.targetSystem()).isEqualTo("求人管理画面");
    }

    @Test
    void 移した入口の答えは新しい形のまま読める() {
        UUID id = legacy("STRUCTURED", "SPEC_QUESTION", "WORK_REQUEST", "IN_PROGRESS", "billing", true, QUESTIONS, true, null);

        flyway("latest").migrate();

        List<IntakeEntry> entries = JSON.readValue(json(id, "intake"), new TypeReference<List<IntakeEntry>>() {});
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).questionId()).isEqualTo("type");
        assertThat(entries.get(0).answer().optionId()).isEqualTo("DO_FOR_ME");
        assertThat(entries.get(1).answer().optionId()).isEqualTo("billing");
    }

    @Test
    void 使わなくなった列は落ち_新しい段階と結果の制約が効く() {
        flyway("latest").migrate();

        List<String> dropped = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'inquiries'"
                        + " and column_name in ('category_by_ai', 'category_confirmed', 'follow_ups', 'ai_questions',"
                        + " 'answers_submitted', 'system_id', 'system_label', 'system_guessed', 'utterance')",
                String.class);
        assertThat(dropped).isEmpty();
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, step, asker_id) values (gen_random_uuid(), 'TRIAGED', 'x')"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, step, asker_id, outcome) values (gen_random_uuid(), 'CLOSED', 'x',"
                                + " 'INCIDENT_REDIRECTED')"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into inquiries (id, step, asker_id, urgency) values (gen_random_uuid(), 'INTAKE', 'x', 'URGENT')"))
                .isInstanceOf(Exception.class);
        // 新しい形の行は入る
        assertThat(jdbc.update("insert into inquiries (id, step, asker_id) values (gen_random_uuid(), 'INTAKE', 'x')"))
                .isEqualTo(1);
    }
}
