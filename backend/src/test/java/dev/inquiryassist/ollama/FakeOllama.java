package dev.inquiryassist.ollama;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** テスト内で立てる、Ollama の代わりの HTTP サーバー。決まった応答を順に返し、受け取ったリクエストを記録する。 */
public final class FakeOllama implements AutoCloseable {

    private record Response(int status, String body, long delayMillis) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Deque<Response> queue = new ArrayDeque<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    /** サーバーを空きポートで起動する。 */
    public FakeOllama() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response response;
            synchronized (this) {
                requests.add(body);
                paths.add(exchange.getRequestURI().getPath());
                response = queue.poll();
            }
            if (response == null) {
                response = new Response(500, "{\"error\":\"no response queued\"}", 0);
            }
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(response.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // クライアントが時間切れで切断済み
            }
            exchange.close();
        });
        server.start();
    }

    /**
     * @return サーバーの URL
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * モデルの出力（content）を返す応答を積む。
     *
     * @param content モデルの出力
     * @param inputTokens 入力のトークン数
     * @param outputTokens 出力のトークン数
     * @return このサーバー
     */
    public FakeOllama reply(String content, int inputTokens, int outputTokens) {
        return enqueue(200, chatBody(content, inputTokens, outputTokens), 0);
    }

    /**
     * 本文をそのまま返す応答を積む（{@code /api/tags} など）。
     *
     * @param json 応答の本文
     * @return このサーバー
     */
    public FakeOllama raw(String json) {
        return enqueue(200, json, 0);
    }

    /**
     * HTTP エラーの応答を積む。
     *
     * @param status ステータス
     * @return このサーバー
     */
    public FakeOllama status(int status) {
        return enqueue(status, "{\"error\":\"fake\"}", 0);
    }

    /**
     * 返すまでに待つ応答を積む（時間切れの確認に使う）。
     *
     * @param content モデルの出力
     * @param delayMillis 待つ時間
     * @return このサーバー
     */
    public FakeOllama slowReply(String content, long delayMillis) {
        return enqueue(200, chatBody(content, 1, 1), delayMillis);
    }

    private synchronized FakeOllama enqueue(int status, String body, long delay) {
        queue.add(new Response(status, body, delay));
        return this;
    }

    private static String chatBody(String content, int in, int out) {
        return JSON.writeValueAsString(Map.of(
                "message", Map.of("role", "assistant", "content", content),
                "prompt_eval_count", in,
                "eval_count", out));
    }

    /**
     * @return 受け取ったリクエストの本文（受け取った順）
     */
    public synchronized List<String> requests() {
        return List.copyOf(requests);
    }

    /**
     * @return 受け取ったリクエストのパス（受け取った順）
     */
    public synchronized List<String> paths() {
        return List.copyOf(paths);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
