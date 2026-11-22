package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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
 * V11（返事がないときの決まりの起点）が、既存の記録の起点を、履歴の最後の出来事の時刻から（履歴がなければ更新時刻から）埋め、
 * 知らせた時刻を空にすることを確かめる。V10 までを適用した空のデータベースに行を入れてから、V11 を適用する。
 */
class V11MigrationTest {

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
    void migrateToV10() {
        String name = "v11_test_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("create database " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("10").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private UUID insert(String history, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into inquiries (id, asker_id, phase, awaiting, history, updated_at)"
                        + " values (?::uuid, 'local-user', 'KNOW', 'RESULT_CONFIRMATION', ?::jsonb, ?)",
                id.toString(),
                history,
                Timestamp.from(updatedAt));
        return id;
    }

    private Instant awaitingSince(UUID id) {
        return jdbc.queryForObject("select awaiting_since from inquiries where id = ?::uuid", Timestamp.class, id.toString())
                .toInstant();
    }

    @Test
    void 履歴のある行の起点は最後の出来事の時刻になる_数値でも文字列でも() {
        Instant updatedAt = Instant.parse("2026-11-30T00:00:00Z");
        // アプリが書く形（エポック秒の数値）
        UUID numeric = insert(
                "[{\"at\":1795327205.633872000,\"actor\":\"AI\",\"event\":\"ANSWERED_BY_AI\"},"
                        + "{\"at\":1795400000.500000000,\"actor\":\"OPERATOR\",\"event\":\"RESULT_DELIVERED\"}]",
                updatedAt);
        UUID text = insert(
                "[{\"at\":\"2026-11-18T03:00:00Z\",\"actor\":\"AI\",\"event\":\"ANSWERED_BY_AI\"},"
                        + "{\"at\":\"2026-11-20T03:00:00Z\",\"actor\":\"OPERATOR\",\"event\":\"RESULT_DELIVERED\"}]",
                updatedAt);

        flyway("11").migrate();

        // 最後の要素の時刻。最初の要素ではない
        assertThat(awaitingSince(numeric)).isEqualTo(Instant.ofEpochSecond(1795400000, 500_000_000));
        assertThat(awaitingSince(text)).isEqualTo(Instant.parse("2026-11-20T03:00:00Z"));
    }

    @Test
    void 履歴が空の行の起点は更新時刻になり_知らせた時刻は空() {
        Instant updatedAt = Instant.parse("2026-11-30T00:00:00Z");
        UUID empty = insert("[]", updatedAt);

        flyway("11").migrate();

        assertThat(awaitingSince(empty)).isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject("select reminded_at from inquiries where id = ?::uuid", Timestamp.class, empty.toString()))
                .isNull();
    }

    @Test
    void 移行の後に作る行は起点も知らせた時刻も空にできる() {
        flyway("11").migrate();

        jdbc.update("insert into inquiries (id, asker_id, phase) values (gen_random_uuid(), 'u', 'INTAKE')");
        assertThat(jdbc.queryForObject("select count(*) from inquiries where awaiting_since is null", Integer.class))
                .isEqualTo(1);
    }
}
