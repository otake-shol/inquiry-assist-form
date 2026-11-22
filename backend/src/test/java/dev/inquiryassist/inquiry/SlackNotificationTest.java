package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Slack への投稿を、テスト内で立てたローカルの HTTP サーバーで受けて確認する。実際の Slack には投稿しない。 */
class SlackNotificationTest extends ApiSupport {

    private static final List<String[]> received = new CopyOnWriteArrayList<>();
    private static final HttpServer server = startServer();

    private static HttpServer startServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String path : List.of("/job-admin", "/default", "/error", "/slow")) {
                s.createContext(path, exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    received.add(new String[] {path, body});
                    if (path.equals("/slow")) {
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int code = path.equals("/error") ? 500 : 200;
                    exchange.sendResponseHeaders(code, -1);
                    exchange.close();
                });
            }
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // リストは、設定の置き場所が違うと丸ごと置き換わる（要素ごとには重ならない）ため、すべてのシステムを与える。
        // job-admin と billing は投稿先を持ち、billing の投稿先は500を返す。reports は個別の投稿先を持たず、
        // slow の投稿先は応答が遅い
        systems(registry, 0, "job-admin", "求人管理画面", base + "/job-admin", "posting", "求人の掲載", "applicants", "応募者の一覧");
        systems(registry, 1, "billing", "請求管理", base + "/error", "invoice", "請求書");
        systems(registry, 2, "reports", "レポート", null);
        systems(registry, 3, "slow", "遅いシステム", base + "/slow");
        registry.add("inquiry.slack.default-webhook-url", () -> base + "/default");
        registry.add("inquiry.slack.timeout", () -> "2s");
        registry.add("inquiry.public-base-url", () -> "https://example.com/");
    }

    /** システム1件を設定に加える。functions は「機能の id, 機能の名前」の並び。 */
    private static void systems(
            DynamicPropertyRegistry registry, int index, String id, String label, String url, String... functions) {
        String prefix = "inquiry.systems[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "label.ja", () -> label);
        if (url != null) {
            registry.add(prefix + "slack-webhook-url", () -> url);
        }
        for (int i = 0; i < functions.length; i += 2) {
            String fid = functions[i];
            String flabel = functions[i + 1];
            registry.add(prefix + "functions[" + i / 2 + "].id", () -> fid);
            registry.add(prefix + "functions[" + i / 2 + "].label.ja", () -> flabel);
        }
    }

    @BeforeEach
    void clear() {
        received.clear();
    }

    private String awaitPost(String path) throws Exception {
        assertThat(waitFor(() -> received.stream().anyMatch(r -> r[0].equals(path)), 5000))
                .as(path + " に投稿が届く")
                .isTrue();
        return received.stream().filter(r -> r[0].equals(path)).findFirst().orElseThrow()[1];
    }

    private void awaitRecorded(String id) throws Exception {
        assertThat(waitFor(
                        () -> {
                            var row = jdbc.queryForMap(
                                    "select slack_notified, slack_error from inquiries where id = ?::uuid", id);
                            return Boolean.TRUE.equals(row.get("slack_notified")) || row.get("slack_error") != null;
                        },
                        8000))
                .isTrue();
    }

    private String slackStatusOf(String id) throws Exception {
        String body = text(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/operator/inquiries/" + id)));
        assertThat(body).doesNotContain("slackNotified");
        return JsonPath.read(body, "$.slackStatus");
    }

    @Test
    void 投稿に成功するとPOSTEDになる() throws Exception {
        String id = filedNow("期限の仕様", "job-admin");

        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("POSTED");
    }

    @Test
    void サーバーエラーとタイムアウトはFAILEDになる() throws Exception {
        String serverError = filedNow("期限の仕様", "billing");
        awaitRecorded(serverError);
        String timeout = filedNow("期限の仕様", "slow");
        awaitRecorded(timeout);

        assertThat(slackStatusOf(serverError)).isEqualTo("FAILED");
        assertThat(slackStatusOf(timeout)).isEqualTo("FAILED");
    }

    @Test
    void 投稿先はあるが結果がまだ出ていない間はPENDINGで結果が出るとFAILEDになる() throws Exception {
        String id = filedNow("期限の仕様", "slow");

        // 遅いハンドラが応答を保留している間（タイムアウト前）に詳細を読む
        awaitPost("/slow");
        assertThat(slackStatusOf(id)).isEqualTo("PENDING");
        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("FAILED");
    }

    @Test
    void システム別の設定だけがある場合_投稿先のない別システムは既定のURLが使われPOSTEDになる() throws Exception {
        String id = filedNow("期限の仕様", "reports");

        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("POSTED");
    }

    @Test
    void システム別のURLがあればそこへ届き既定のURLには届かない() throws Exception {
        String id = filedNow("期限の仕様", "job-admin");

        String body = awaitPost("/job-admin");
        awaitRecorded(id);
        assertThat(received.stream().map(r -> r[0])).doesNotContain("/default");
        String text = JsonPath.read(body, "$.text");
        assertThat(text).contains("種類: 仕組みを知りたい", "システム: 求人管理画面", "期限の仕様", "「今すぐ送る」による送付");
        // 通常の緊急度は載せない
        assertThat(text).doesNotContain("緊急度");
        assertThat(text).contains("https://example.com/operator/" + id);
        assertThat(jdbc.queryForObject("select slack_notified from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    @Test
    void 個別のURLがないシステムと対象未定は既定のURLへ届く() throws Exception {
        String noUrl = filedNow("期限の仕様", "reports");
        awaitPost("/default");
        awaitRecorded(noUrl);
        received.clear();

        String unknown = filedNow("期限の仕様", null);

        String text = JsonPath.read(awaitPost("/default"), "$.text");
        assertThat(text).contains("システム: （未定）");
        awaitRecorded(unknown);
    }

    @Test
    void 本文は契約書の項目を持ち調査内容の全文を含まない() throws Exception {
        String id = toAnswering("期限の仕様", "job-admin");
        stream(id);

        resolve(id, false).andExpect(jsonPath("$.awaiting").value("HANDLING"));

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");
        assertThat(text)
                .contains("種類: 仕組みを知りたい")
                .contains("システム: 求人管理画面")
                .contains("求人管理画面について、いまの仕組みがどうなっているかを知りたい。 書かれた内容: 期限の仕様")
                .contains("送付: 通常の送付")
                .contains("https://example.com/operator/" + id);
        // 技術メモ・回答の説明・コードの抜粋は載せない
        assertThat(text)
                .doesNotContain("isExpired")
                .doesNotContain("JobPostingService")
                .doesNotContain("新しい求人として登録し直す")
                .doesNotContain("isBefore");
    }

    @Test
    void 本文に機能の名前と通常以外の緊急度が載る() throws Exception {
        String id = idOf(create("求人管理画面の求人の掲載がおかしい", "HURRY"));
        finishIntake(id, null);
        confirm(id).andExpect(status().isOk());
        stream(id);

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");

        assertThat(text)
                .contains("種類: おかしいかもしれない")
                .contains("システム: 求人管理画面 / 求人の掲載")
                .contains("緊急度: 急ぎ");
    }

    @Test
    void 業務が止まっているときは入口を終えた時点で投稿される() throws Exception {
        String id = idOf(create("求人管理画面が使えない", "BLOCKED"));

        finishIntake(id, null);

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");
        assertThat(text).contains("緊急度: 業務が止まっている").contains("「今すぐ送る」による送付");
        awaitRecorded(id);
    }

    @Test
    void 本文中のSlackの特殊文字は無害化される() throws Exception {
        filedNow("<!channel> &amp;", "job-admin");

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");
        assertThat(text).contains("&lt;!channel&gt;").doesNotContain("<!channel>");
    }

    @Test
    void Slackが500を返しても問い合わせは担当者に送付済みのままで失敗が記録される() throws Exception {
        String id = filedNow("期限の仕様", "billing");

        awaitPost("/error");
        awaitRecorded(id);
        var row = jdbc.queryForMap("select awaiting, slack_notified, slack_error from inquiries where id = ?::uuid", id);
        assertThat(row.get("awaiting")).isEqualTo("HANDLING");
        assertThat(row.get("slack_notified")).isEqualTo(false);
        assertThat((String) row.get("slack_error")).contains("500").doesNotContain("127.0.0.1");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.slackStatus").value("FAILED"))
                .andExpect(jsonPath("$.slackNotified").doesNotExist());
    }

    @Test
    void Slackがタイムアウトしても送付は取り消されず失敗が記録される() throws Exception {
        String id = toAnswering("[遅延] 期限の仕様", "slow");
        long start = System.nanoTime();

        mvc.perform(post("/api/inquiries/" + id + "/send-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"));

        // 投稿の完了を待たずに応答が返る（タイムアウトは2秒）
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(900));
        awaitInvestigationIdle(id);
        awaitRecorded(id);
        var row = jdbc.queryForMap("select awaiting, slack_notified, slack_error from inquiries where id = ?::uuid", id);
        assertThat(row.get("awaiting")).isEqualTo("HANDLING");
        assertThat(row.get("slack_notified")).isEqualTo(false);
        assertThat((String) row.get("slack_error")).contains("Timeout");
        assertThat(notifier.notices()).anySatisfy(n -> assertThat(n.inquiryId().toString()).isEqualTo(id));
    }
}
