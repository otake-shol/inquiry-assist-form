package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.skill.Skill;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 問い合わせフォームの設定（{@code inquiry.*}）。
 *
 * @param systems 対象システムの一覧
 * @param slack Slack への投稿の設定
 * @param publicBaseUrl 投稿に載せるオペレータ画面へのリンクの基点。未設定ならリンクを載せない
 * @param skills スキルごとの実装の設定（キーはスキルの識別子）。書かれていないスキルは {@code mock}
 * @param ollama 手元のモデル（Ollama）の共通の設定
 */
@ConfigurationProperties("inquiry")
public record InquiryProperties(
        @DefaultValue List<SystemEntry> systems,
        @DefaultValue Slack slack,
        String publicBaseUrl,
        @DefaultValue Map<String, SkillSettings> skills,
        @DefaultValue Ollama ollama) {

    /**
     * スキルの設定を返す。書かれていなければ、既定（モック）の設定を返す。
     *
     * @param skill スキル
     * @return スキルの設定
     */
    public SkillSettings skill(Skill skill) {
        SkillSettings settings = skills.get(skill.id());
        return settings != null ? settings : new SkillSettings("mock", null, 0, false, Duration.ofSeconds(60), "v1");
    }

    /**
     * スキルごとの実装の設定。
     *
     * @param provider 実装の種類（{@code mock} または {@code ollama}）。既定は {@code mock}
     * @param model モデルの名前（ollama のとき）
     * @param temperature 出力のばらつき。0 で毎回同じ出力になる
     * @param think 考える過程を出力させるか。質は上がるが、応答が遅くなる
     * @param timeout 1回の呼び出しの時間切れ
     * @param promptVersion プロンプトの版（{@code skills/<識別子>/system-<版>.md}）。既定は {@code v1}
     */
    public record SkillSettings(
            @DefaultValue("mock") String provider,
            String model,
            @DefaultValue("0") double temperature,
            @DefaultValue("false") boolean think,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("v1") String promptVersion) {}

    /**
     * Ollama の共通の設定。
     *
     * @param baseUrl Ollama の URL
     * @param keepAlive モデルをメモリに残す時間
     * @param seed 乱数の種。{@code options.seed} として必ず送り、出力を揃いやすくする（保証はない）
     */
    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("30m") String keepAlive,
            @DefaultValue("42") int seed) {}

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
