package dev.inquiryassist.notification;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.inquiry.InquiryFiledEvent;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.OperatorItem;
import dev.inquiryassist.inquiry.OperatorService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 起票を Slack の Incoming Webhook へ投稿する。
 *
 * <p>投稿先は対象システムごとの URL、なければ既定の URL。どちらも未設定なら投稿せずログに出す。
 * 投稿は仮想スレッドで行い、起票の応答を待たせない。失敗しても起票は取り消さず、結果を記録に残す。
 * 本文には種類・システムと機能・緊急度（通常以外のとき）・言い直し・送付の方法・リンクだけを載せ、調査内容の全文は載せない。
 * Webhook の URL は秘密情報のため、ログや記録に出さない。
 */
@Component
public class SlackFilingNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackFilingNotifier.class);
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final InquiryProperties properties;
    private final IntakeCatalog catalog;
    private final OperatorService operators;
    private final JsonMapper json;
    private final HttpClient client;

    /**
     * @param properties 投稿先などの設定
     * @param catalog 緊急度の名前
     * @param operators 投稿結果の記録先
     * @param json 本文の JSON 化
     */
    public SlackFilingNotifier(
            InquiryProperties properties, IntakeCatalog catalog, OperatorService operators, JsonMapper json) {
        this.properties = properties;
        this.catalog = catalog;
        this.operators = operators;
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.slack().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 起票の確定後に呼ばれ、投稿先があれば投稿を始める。
     *
     * @param event 送付のイベント
     */
    @EventListener
    public void onFiled(InquiryFiledEvent event) {
        OperatorItem item = event.item();
        String url = properties.webhookUrlFor(item.system() == null ? null : item.system().id());
        if (url == null) {
            log.info("Slack の投稿先が未設定のため投稿しません id={}", item.id());
            return;
        }
        Thread.ofVirtual().name("slack-" + item.id()).start(() -> post(item, url));
    }

    /** 本文を作って投稿し、結果を記録する。 */
    private void post(OperatorItem item, String url) {
        boolean notified = false;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.slack().timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(payload(item)), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                notified = true;
            } else {
                error = "Slack が HTTP " + response.statusCode() + " を返した";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Slack への投稿が中断された";
        } catch (Exception e) {
            // 例外の本文に URL が含まれうるため、種類だけを残す
            error = "Slack への投稿に失敗した（" + e.getClass().getSimpleName() + "）";
        }
        if (error != null) {
            log.warn("{} id={}", error, item.id());
        }
        try {
            operators.recordSlackResult(item.id(), notified, error);
        } catch (RuntimeException e) {
            log.error("Slack への投稿結果の記録に失敗 id={}", item.id(), e);
        }
    }

    private Map<String, Object> payload(OperatorItem item) {
        StringBuilder text = new StringBuilder("担当者に問い合わせが届きました\n");
        text.append("種類: ").append(item.type() == null ? "（未定）" : escape(item.type().label())).append('\n');
        text.append("システム: ").append(placeOf(item)).append('\n');
        if (item.urgency() != Urgency.NORMAL) {
            text.append("緊急度: ").append(escape(catalog.urgencyLabel(item.urgency()))).append('\n');
        }
        text.append("内容: ").append(escape(truncate(item.summary()))).append('\n');
        text.append("送付: ").append(item.sentImmediately() ? "「今すぐ送る」による送付" : "通常の送付").append('\n');
        String base = properties.publicBaseUrl();
        if (base != null && !base.isBlank()) {
            text.append("詳細: ").append(base.replaceAll("/+$", "")).append("/operator/").append(item.id());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text.toString().stripTrailing());
        return body;
    }

    /** システムと機能の名前。機能が決まっていれば「システム / 機能」の形にする。 */
    private static String placeOf(OperatorItem item) {
        if (item.system() == null) {
            return "（未定）";
        }
        String system = escape(item.system().label());
        return item.function() == null ? system : system + " / " + escape(item.function().label());
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SUMMARY_LENGTH ? text : text.substring(0, MAX_SUMMARY_LENGTH) + "…";
    }

    /** Slack の書式で特別な意味を持つ文字を無害化する（メンションやリンクの混入を防ぐ）。 */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
