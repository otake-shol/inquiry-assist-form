package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.skill.Skill;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 問い合わせフォームの設定（{@code inquiry.*}）。
 *
 * @param languages 名前を持つ言語。先頭が既定の言語で、API が返す名前はこの言語のもの
 * @param types 種類の名前と説明。書かれていない種類は、識別子を名前にする
 * @param urgencies 緊急度の名前。書かれていない緊急度は、識別子を名前にする
 * @param systems システムと、その機能の一覧
 * @param cells マスの設定。置き場所ごとの詳しい質問。同じ指定の数なら、書かれた順で先のものを使う
 * @param slack Slack への投稿の設定
 * @param publicBaseUrl 投稿に載せるオペレータ画面へのリンクの基点。未設定ならリンクを載せない
 * @param skills スキルごとの実装の設定（キーはスキルの識別子）。書かれていないスキルは {@code mock}
 * @param ollama 手元のモデル（Ollama）の共通の設定
 * @param trace スキルの実行記録の設定
 * @param dev 認証を加えるまでの、利用者と担当者の表示名の仮の設定
 */
@ConfigurationProperties("inquiry")
public record InquiryProperties(
        @DefaultValue({"ja"}) List<String> languages,
        @DefaultValue List<NamedEntry> types,
        @DefaultValue List<NamedEntry> urgencies,
        @DefaultValue List<SystemEntry> systems,
        @DefaultValue List<CellEntry> cells,
        @DefaultValue Slack slack,
        String publicBaseUrl,
        @DefaultValue Map<String, SkillSettings> skills,
        @DefaultValue Ollama ollama,
        @DefaultValue Trace trace,
        @DefaultValue Dev dev) {

    /**
     * スキルの実行記録の設定。
     *
     * @param enabled 記録を画面に見せるか。既定は true。本番では、内部の言葉とモデルの名前を本人に見せないため false にする
     */
    public record Trace(@DefaultValue("true") boolean enabled) {}

    /**
     * 認証を加えるまでの、表示名の仮の設定。
     *
     * @param userName 利用者の表示名
     * @param operatorName 担当者の表示名
     */
    public record Dev(
            @DefaultValue("開発用ユーザー") String userName, @DefaultValue("担当者（開発用）") String operatorName) {}

    /**
     * スキルの設定を返す。書かれていなければ、既定（モック）の設定を返す。
     *
     * @param skill スキル
     * @return スキルの設定
     */
    public SkillSettings skill(Skill skill) {
        SkillSettings settings = skills.get(skill.id());
        if (settings == null) {
            settings = new SkillSettings("mock", null, 0, false, Duration.ofSeconds(60), null);
        }
        if (settings.promptVersion() == null || settings.promptVersion().isBlank()) {
            settings = new SkillSettings(
                    settings.provider(),
                    settings.model(),
                    settings.temperature(),
                    settings.think(),
                    settings.timeout(),
                    defaultPromptVersion(skill));
        }
        return settings;
    }

    /**
     * スキルのプロンプトの版の既定。入口の作り替えで指示文を新しくした4つのスキルは v2、調査は v1 のまま。
     *
     * @param skill スキル
     * @return 版（{@code skills/<識別子>/system-<版>.md}）
     */
    public static String defaultPromptVersion(Skill skill) {
        return skill == Skill.INVESTIGATOR ? "v1" : "v2";
    }

    /**
     * スキルごとの実装の設定。
     *
     * @param provider 実装の種類（{@code mock} または {@code ollama}）。既定は {@code mock}
     * @param model モデルの名前（ollama のとき）
     * @param temperature 出力のばらつき。0 で毎回同じ出力になる
     * @param think 考える過程を出力させるか。質は上がるが、応答が遅くなる
     * @param timeout 1回の呼び出しの時間切れ
     * @param promptVersion プロンプトの版（{@code skills/<識別子>/system-<版>.md}）。省くと {@link #defaultPromptVersion}
     */
    public record SkillSettings(
            @DefaultValue("mock") String provider,
            String model,
            @DefaultValue("0") double temperature,
            @DefaultValue("false") boolean think,
            @DefaultValue("60s") Duration timeout,
            String promptVersion) {}

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
     * 言語ごとの名前のうち、既定の言語の名前を返す。
     *
     * @param names 言語から名前への対応（{@code { ja: …, en: … }}）
     * @param fallback 名前が1つもないときの値
     * @return 既定の言語の名前。なければ最初に書かれた名前、それもなければ fallback
     */
    public String nameOf(Map<String, String> names, String fallback) {
        if (names != null) {
            String preferred = names.get(defaultLanguage());
            if (preferred != null && !preferred.isBlank()) {
                return preferred;
            }
            for (String value : names.values()) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /**
     * @return 既定の言語（{@code languages} の先頭。書かれていなければ {@code ja}）
     */
    public String defaultLanguage() {
        return languages == null || languages.isEmpty() ? "ja" : languages.get(0);
    }

    /**
     * 設定から、入口の質問を決める処理に渡す一覧を作る。名前は既定の言語に解決する。
     *
     * @return 種類、緊急度、システム、機能、マスの設定
     */
    public IntakeCatalog catalog() {
        List<IntakeCatalog.TypeInfo> typeInfos = java.util.Arrays.stream(InquiryType.values())
                .map(t -> {
                    NamedEntry entry = types.stream().filter(e -> t.name().equals(e.id())).findFirst().orElse(null);
                    return new IntakeCatalog.TypeInfo(
                            t,
                            entry == null ? t.name() : nameOf(entry.label(), t.name()),
                            entry == null ? "" : nameOf(entry.hint(), ""));
                })
                .toList();
        List<IntakeCatalog.UrgencyInfo> urgencyInfos = java.util.Arrays.stream(Urgency.values())
                .map(u -> {
                    NamedEntry entry = urgencies.stream().filter(e -> u.name().equals(e.id())).findFirst().orElse(null);
                    return new IntakeCatalog.UrgencyInfo(u, entry == null ? u.name() : nameOf(entry.label(), u.name()));
                })
                .toList();
        List<IntakeCatalog.SystemInfo> systemInfos = systems.stream()
                .map(s -> new IntakeCatalog.SystemInfo(
                        s.id(),
                        nameOf(s.label(), s.id()),
                        s.functions().stream()
                                .map(f -> new Choice(f.id(), nameOf(f.label(), f.id())))
                                .toList()))
                .toList();
        List<IntakeCatalog.Cell> cellInfos = cells.stream()
                .map(c -> new IntakeCatalog.Cell(
                        blankToNull(c.system()),
                        blankToNull(c.function()),
                        c.type(),
                        c.questions().stream().map(this::question).toList()))
                .toList();
        return new IntakeCatalog(typeInfos, urgencyInfos, systemInfos, cellInfos);
    }

    private IntakeQuestion question(QuestionEntry q) {
        return new IntakeQuestion(
                q.id(),
                QuestionKind.DETAIL,
                nameOf(q.text(), q.id()),
                q.options().stream().map(o -> new Choice(o.id(), nameOf(o.label(), o.id()))).toList(),
                false,
                false);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 種類や緊急度の名前。
     *
     * @param id 識別子（{@code InquiryType} または {@code Urgency} の名前）
     * @param label 言語ごとの名前
     * @param hint 言語ごとの短い説明。緊急度では使わない
     */
    public record NamedEntry(
            String id, @DefaultValue Map<String, String> label, @DefaultValue Map<String, String> hint) {}

    /**
     * システム1件。
     *
     * @param id 識別子
     * @param label 言語ごとの名前
     * @param functions このシステムの機能。機能の設定がなければ空（機能は聞かない）
     * @param slackWebhookUrl このシステムの問い合わせを投稿する Incoming Webhook の URL。未設定なら既定の投稿先
     */
    public record SystemEntry(
            String id,
            @DefaultValue Map<String, String> label,
            @DefaultValue List<FunctionEntry> functions,
            String slackWebhookUrl) {}

    /**
     * 機能1件。
     *
     * @param id 識別子
     * @param label 言語ごとの名前
     */
    public record FunctionEntry(String id, @DefaultValue Map<String, String> label) {}

    /**
     * マスの設定1件。指定した項目がすべて当てはまる問い合わせに、詳しい質問を出す。
     *
     * @param system システムの識別子。省くと、すべてのシステム
     * @param function 機能の識別子。省くと、すべての機能
     * @param type 種類。省くと、すべての種類
     * @param questions 詳しい質問（設定の順。3問を超える分は使わない）
     */
    public record CellEntry(
            String system, String function, InquiryType type, @DefaultValue List<QuestionEntry> questions) {}

    /**
     * 詳しい質問1問。
     *
     * @param id 質問の識別子
     * @param text 言語ごとの質問文
     * @param options 選択肢。省くと、文章で答える
     */
    public record QuestionEntry(
            String id, @DefaultValue Map<String, String> text, @DefaultValue List<OptionEntry> options) {}

    /**
     * 選択肢1つ。
     *
     * @param id 識別子
     * @param label 言語ごとの名前
     */
    public record OptionEntry(String id, @DefaultValue Map<String, String> label) {}

    /**
     * Slack の設定。
     *
     * @param defaultWebhookUrl 対象システムが未定、または個別の設定がない場合の投稿先
     * @param timeout 投稿の接続・応答のタイムアウト
     */
    public record Slack(String defaultWebhookUrl, @DefaultValue("5s") Duration timeout) {}
}
