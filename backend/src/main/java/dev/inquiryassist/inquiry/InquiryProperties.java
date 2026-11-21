package dev.inquiryassist.inquiry;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 問い合わせフォームの設定（{@code inquiry.*}）。
 *
 * @param systems 対象システムの一覧
 * @param slack Slack への投稿の設定
 * @param publicBaseUrl 投稿に載せるオペレータ画面へのリンクの基点。未設定ならリンクを載せない
 */
@ConfigurationProperties("inquiry")
public record InquiryProperties(
        @DefaultValue List<SystemEntry> systems, @DefaultValue Slack slack, String publicBaseUrl) {

    /**
     * 問い合わせの投稿先を決める。対象システムに個別の URL があればそれ、なければ既定の URL。
     *
     * @param systemId 対象システムの識別子。未定なら null
     * @return 投稿先の Webhook の URL。どちらも未設定なら null
     */
    public String webhookUrlFor(String systemId) {
        if (systemId != null) {
            String specific = systems.stream()
                    .filter(s -> s.id().equals(systemId))
                    .map(SystemEntry::slackWebhookUrl)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst()
                    .orElse(null);
            if (specific != null) {
                return specific;
            }
        }
        String fallback = slack.defaultWebhookUrl();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    /**
     * 対象システム1件。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     * @param slackWebhookUrl このシステムの問い合わせを投稿する Incoming Webhook の URL。未設定なら既定の投稿先
     */
    public record SystemEntry(String id, String label, String slackWebhookUrl) {}

    /**
     * Slack の設定。
     *
     * @param defaultWebhookUrl 対象システムが未定、または個別の設定がない場合の投稿先
     * @param timeout 投稿の接続・応答のタイムアウト
     */
    public record Slack(String defaultWebhookUrl, @DefaultValue("5s") Duration timeout) {}
}
