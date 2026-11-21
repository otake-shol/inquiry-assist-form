package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
        Map<String, String> systems = new java.util.LinkedHashMap<>();
        systems.put("job-admin", "求人管理画面");
        systems.put("applicant-list", "応募者向けの一覧");
        systems.put("billing", "請求管理");
        systems.put("slow", "遅いシステム");
        int i = 0;
        for (var e : systems.entrySet()) {
            registry.add("inquiry.systems[" + i + "].id", e::getKey);
            registry.add("inquiry.systems[" + i + "].label", e::getValue);
            i++;
        }
        registry.add("inquiry.systems[0].slack-webhook-url", () -> base + "/job-admin");
        registry.add("inquiry.systems[2].slack-webhook-url", () -> base + "/error");
        registry.add("inquiry.systems[3].slack-webhook-url", () -> base + "/slow");
        registry.add("inquiry.slack.default-webhook-url", () -> base + "/default");
        registry.add("inquiry.slack.timeout", () -> "2s");
        registry.add("inquiry.public-base-url", () -> "https://example.com/");
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
        String id = idOf(create("期限の仕様", "job-admin"));

        sendNow(id);

        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("POSTED");
    }

    @Test
    void サーバーエラーとタイムアウトはFAILEDになる() throws Exception {
        String serverError = idOf(create("期限の仕様", "billing"));
        sendNow(serverError);
        awaitRecorded(serverError);
        String timeout = idOf(create("期限の仕様", "slow"));
        sendNow(timeout);
        awaitRecorded(timeout);

        assertThat(slackStatusOf(serverError)).isEqualTo("FAILED");
        assertThat(slackStatusOf(timeout)).isEqualTo("FAILED");
    }

    @Test
    void 投稿先はあるが結果がまだ出ていない間はPENDINGで結果が出るとFAILEDになる() throws Exception {
        String id = idOf(create("期限の仕様", "slow"));

        sendNow(id);

        // 遅いハンドラが応答を保留している間（タイムアウト前）に詳細を読む
        awaitPost("/slow");
        assertThat(slackStatusOf(id)).isEqualTo("PENDING");
        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("FAILED");
    }

    @Test
    void システム別の設定だけがある場合_投稿先のない別システムは既定のURLが使われPOSTEDになる() throws Exception {
        String id = idOf(create("期限の仕様", "applicant-list"));

        sendNow(id);

        awaitRecorded(id);
        assertThat(slackStatusOf(id)).isEqualTo("POSTED");
    }

    @Test
    void システム別のURLがあればそこへ届き既定のURLには届かない() throws Exception {
        String id = idOf(create("期限の仕様", "job-admin"));

        sendNow(id).andExpect(status().isOk());

        String body = awaitPost("/job-admin");
        awaitRecorded(id);
        assertThat(received.stream().map(r -> r[0])).doesNotContain("/default");
        String text = JsonPath.read(body, "$.text");
        assertThat(text).contains("仕様の確認・相談", "求人管理画面", "期限の仕様", "「今すぐ送る」による送付");
        assertThat(text).contains("https://example.com/operator/" + id);
        assertThat(jdbc.queryForObject("select slack_notified from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    @Test
    void 個別のURLがないシステムと対象未定は既定のURLへ届く() throws Exception {
        String noUrl = idOf(create("期限の仕様", "applicant-list"));
        sendNow(noUrl);
        awaitPost("/default");
        awaitRecorded(noUrl);
        received.clear();

        String unknown = idOf(create("期限の仕様", null));
        sendNow(unknown);

        String text = JsonPath.read(awaitPost("/default"), "$.text");
        assertThat(text).contains("対象システム: （未定）");
        awaitRecorded(unknown);
    }

    @Test
    void 本文は契約書の項目を持ち調査内容の全文を含まない() throws Exception {
        String id = toAnswering("期限の仕様", "job-admin");
        stream(id);

        resolve(id, false).andExpect(jsonPath("$.outcome").value("FILED"));

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");
        assertThat(text)
                .contains("種別: 仕様の確認・相談")
                .contains("対象システム: 求人管理画面")
                .contains("「期限の仕様」について、いまの仕組みがどうなっているかを知りたい。")
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
    void 本文中のSlackの特殊文字は無害化される() throws Exception {
        String id = idOf(create("<!channel> &amp;", "job-admin"));

        sendNow(id);

        String text = JsonPath.read(awaitPost("/job-admin"), "$.text");
        assertThat(text).contains("&lt;!channel&gt;").doesNotContain("<!channel>");
    }

    @Test
    void Slackが500を返しても問い合わせは担当者に送付済みのままで失敗が記録される() throws Exception {
        String id = idOf(create("期限の仕様", "billing"));

        sendNow(id).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FILED"));

        awaitPost("/error");
        awaitRecorded(id);
        var row = jdbc.queryForMap("select outcome, step, slack_notified, slack_error from inquiries where id = ?::uuid", id);
        assertThat(row.get("outcome")).isEqualTo("FILED");
        assertThat(row.get("step")).isEqualTo("CLOSED");
        assertThat(row.get("slack_notified")).isEqualTo(false);
        assertThat((String) row.get("slack_error")).contains("500").doesNotContain("127.0.0.1");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.slackStatus").value("FAILED"))
                .andExpect(jsonPath("$.slackNotified").doesNotExist());
    }

    @Test
    void Slackがタイムアウトしても送付は取り消されず失敗が記録される() throws Exception {
        String id = idOf(create("期限の仕様", "slow"));
        long start = System.nanoTime();

        sendNow(id).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FILED"));

        // 投稿の完了を待たずに応答が返る（タイムアウトは2秒）
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(900));
        awaitRecorded(id);
        var row = jdbc.queryForMap("select outcome, slack_notified, slack_error from inquiries where id = ?::uuid", id);
        assertThat(row.get("outcome")).isEqualTo("FILED");
        assertThat(row.get("slack_notified")).isEqualTo(false);
        assertThat((String) row.get("slack_error")).contains("Timeout");
        assertThat(notifier.notices()).anySatisfy(n -> assertThat(n.inquiryId().toString()).isEqualTo(id));
    }
}
