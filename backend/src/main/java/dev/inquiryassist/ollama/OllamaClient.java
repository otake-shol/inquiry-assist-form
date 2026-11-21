package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ollama の {@code POST /api/chat} を、ストリーミングなしで呼ぶ最小のクライアント（JDK 標準の HTTP クライアント）。
 *
 * <p>リクエストに {@code format} は付けない（対象のモデルの版では 501 になるため）。出力の形は、プロンプトでの指示と
 * 受け取った側の検証で守る。{@code think}、{@code options.temperature}、{@code options.seed}、{@code keep_alive} は必ず送る。
 */
public class OllamaClient {

    /**
     * 会話の1メッセージ。
     *
     * @param role {@code system}、{@code user}、{@code assistant} のいずれか
     * @param content 本文
     */
    public record Message(String role, String content) {}

    /**
     * 呼び出しの結果。
     *
     * @param content モデルの出力
     * @param inputTokens 入力のトークン数
     * @param outputTokens 出力のトークン数
     */
    public record Reply(String content, long inputTokens, long outputTokens) {}

    private final String baseUrl;
    private final String keepAlive;
    private final int seed;
    private final JsonMapper json;
    private final HttpClient client;

    /**
     * @param baseUrl Ollama の URL（例: {@code http://localhost:11434}）
     * @param keepAlive モデルをメモリに残す時間（例: {@code 30m}）
     * @param seed 乱数の種（{@code options.seed} として必ず送る）
     * @param json JSON の読み書き
     */
    public OllamaClient(String baseUrl, String keepAlive, int seed, JsonMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.keepAlive = keepAlive;
        this.seed = seed;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 会話を送り、モデルの出力を受け取る。
     *
     * @param model モデルの名前
     * @param messages 会話
     * @param temperature 出力のばらつき（0 で毎回同じ出力）
     * @param think 考える過程を出力させるか
     * @param timeout 時間切れ
     * @return モデルの出力とトークン数
     * @throws AiUnavailableException 接続できない、時間切れ、HTTP のエラー、応答が読めない場合
     */
    public Reply chat(String model, List<Message> messages, double temperature, boolean think, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put(
                "messages",
                messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        body.put("stream", false);
        body.put("think", think);
        body.put("keep_alive", keepAlive);
        body.put("options", Map.of("temperature", temperature, "seed", seed));

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiUnavailableException("Ollama の応答が時間切れになりました", e);
        } catch (IOException e) {
            throw new AiUnavailableException("Ollama に接続できません", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("Ollama の呼び出しが中断されました", e);
        }
        if (response.statusCode() != 200) {
            throw new AiUnavailableException("Ollama が HTTP " + response.statusCode() + " を返しました", null);
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode content = root.path("message").path("content");
            if (!content.isString()) {
                throw new AiUnavailableException("Ollama の応答に message.content がありません", null);
            }
            return new Reply(
                    content.asString(), root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0));
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiUnavailableException("Ollama の応答を読めません", e);
        }
    }
}
