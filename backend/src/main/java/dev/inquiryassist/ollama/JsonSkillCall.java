package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.inquiry.InquiryProperties.SkillSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * モデルを呼び、出力を JSON として読んで型に入れる共通の処理。
 *
 * <p>読む順は、そのまま JSON として読む、最初の {@code {} から最後の {@code }} までを取り出して読む（コードブロックの
 * 記号や前置きがついた場合のため）、の2段。型の不変条件に反する場合も、読めなかった場合と同じに扱う。
 * 読めなければ、前回の出力と「指定の形の JSON だけを出力してください」を会話に足して1回だけ呼び直す。
 * それでも読めなければ、利用量（呼び直しの合計）つきの {@link AiUnavailableException} にする。
 */
final class JsonSkillCall {

    /** 呼び直しのときに足す指示。 */
    static final String RETRY_INSTRUCTION = "指定の形の JSON だけを出力してください";

    private final OllamaClient client;
    private final JsonMapper json;
    private final SkillSettings settings;
    private final String promptVersion;

    JsonSkillCall(OllamaClient client, JsonMapper json, SkillSettings settings, String promptVersion) {
        this.client = client;
        this.json = json;
        this.settings = settings;
        this.promptVersion = promptVersion;
    }

    /**
     * モデルを呼んで結果を型に入れる。
     *
     * @param system system プロンプト
     * @param user 入力
     * @param mapper JSON から型への変換。不変条件に反する場合は {@link RuntimeException} を投げる
     * @param <T> 結果の型
     * @return 結果と利用量
     * @throws AiUnavailableException 接続できない、時間切れ、HTTP のエラー、2回とも読めない場合
     */
    <T> AiResult<T> call(String system, String user, Function<JsonNode, T> mapper) {
        List<OllamaClient.Message> messages = new ArrayList<>();
        messages.add(new OllamaClient.Message("system", system));
        messages.add(new OllamaClient.Message("user", user));

        long input = 0;
        long output = 0;
        List<String> reasons = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> rawOutputs = new ArrayList<>();
        for (int attempt = 1; attempt <= 2; attempt++) {
            OllamaClient.Reply reply;
            try {
                reply = client.chat(settings.model(), messages, settings.temperature(), settings.think(), settings.timeout());
            } catch (AiUnavailableException e) {
                // 1回目の失敗の費用は、2回目の失敗にも引き継ぐ
                throw new AiUnavailableException(e.getMessage(), e.getCause(), usage(input, output, e));
            }
            input += reply.inputTokens();
            output += reply.outputTokens();
            Parsed<T> parsed = tryParse(reply.content(), mapper);
            if (parsed.value() != null) {
                return new AiResult<>(parsed.value(), usage(input, output, null));
            }
            // 理由を捨てると、なぜ読めなかったのかを後から調べられない。試行ごとに残す
            reasons.add(parsed.reason());
            details.add(parsed.detail());
            rawOutputs.add(reply.content());
            messages.add(new OllamaClient.Message("assistant", reply.content()));
            messages.add(new OllamaClient.Message("user", RETRY_INSTRUCTION));
        }
        throw new UnreadableOutputException(reasons, details, rawOutputs, usage(input, output, null));
    }

    /**
     * 読み取りの結果。読めた場合は {@code value}、読めなかった場合は {@code reason} と {@code detail} を持つ。
     *
     * @param value 型に入れた結果。読めなかった場合は null
     * @param reason 読めなかった理由の種類。モデルの出力を含めない（例外のメッセージに入るため）
     * @param detail 理由の詳しい内容。不正な値など、モデルの出力の一部を含むことがある
     * @param <T> 結果の型
     */
    private record Parsed<T>(T value, String reason, String detail) {

        static <T> Parsed<T> ok(T value) {
            return new Parsed<>(value, null, null);
        }

        static <T> Parsed<T> unreadable(String reason) {
            return new Parsed<>(null, reason, reason);
        }

        static <T> Parsed<T> unreadable(String reason, String detail) {
            return new Parsed<>(null, reason, reason + "（" + detail + "）");
        }
    }

    private Usage usage(long input, long output, AiUnavailableException cause) {
        if (cause != null && cause.usage() != null) {
            return cause.usage();
        }
        return new Usage(settings.model(), promptVersion, input, output, 0, false);
    }

    /** 読めて、型の不変条件も満たす場合だけ結果を返す。そうでなければ、読めなかった理由を返す。 */
    private <T> Parsed<T> tryParse(String content, Function<JsonNode, T> mapper) {
        if (content == null || content.isBlank()) {
            return Parsed.unreadable("出力が空");
        }
        JsonNode node = readJson(json, content);
        if (node == null) {
            return Parsed.unreadable("JSON として読めない");
        }
        if (!node.isObject()) {
            return Parsed.unreadable("JSON のオブジェクトでない");
        }
        try {
            T value = mapper.apply(node);
            return value == null ? Parsed.unreadable("型に入れた結果が空") : Parsed.ok(value);
        } catch (RuntimeException e) {
            // 例外のメッセージには、不正な値そのもの（モデルの出力の一部）が入ることがある。
            // 理由の種類とは分けて持ち、例外のメッセージには入れない
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Parsed.unreadable("形が指定と違う", detail);
        }
    }

    /**
     * 出力を JSON として読む。そのまま読めなければ、最初の {@code {} から最後の {@code }} までを取り出して読む。
     *
     * @param json JSON の読み書き
     * @param content モデルの出力
     * @return 読めた JSON。読めなければ null
     */
    static JsonNode readJson(JsonMapper json, String content) {
        String text = content.strip();
        try {
            return json.readTree(text);
        } catch (JacksonException ignored) {
            // 前置きやコードブロックの記号がついた場合
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return json.readTree(text.substring(start, end + 1));
        } catch (JacksonException e) {
            return null;
        }
    }
}
