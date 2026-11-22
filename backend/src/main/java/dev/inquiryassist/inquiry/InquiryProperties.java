package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.NoReplyRule;
import dev.inquiryassist.core.skill.Skill;
import java.time.Duration;
import java.time.ZoneId;
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
 * @param filedGuidance 担当者に渡した後に画面が見せる案内文。言語ごと
 * @param rules 時間で動く決まり（返事がないときの知らせと回答済み）の設定
 * @param consultants 全体の相談先（仕様の持ち主への相談）。名前の一覧。既定は空
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
        @DefaultValue Dev dev,
        @DefaultValue Map<String, String> filedGuidance,
        @DefaultValue Rules rules,
        @DefaultValue List<String> consultants) {

    /**
     * 時間で動く決まりの設定（{@code inquiry.rules}）。
     *
     * @param zone 営業日を判定する時間帯
     * @param noReply 返事がないときの期間
     * @param sweepEnabled 決まりの判定を、定期的に行うか
     * @param sweepInterval 判定の間隔
     * @param answerTimeout 本人が待つ調査を、担当者に引き継ぐまでの時間
     */
    public record Rules(
            @DefaultValue("Asia/Tokyo") ZoneId zone,
            @DefaultValue NoReply noReply,
            @DefaultValue("true") boolean sweepEnabled,
            @DefaultValue("15m") Duration sweepInterval,
            @DefaultValue("60s") Duration answerTimeout) {

        /**
         * 既定の設定。
         *
         * @return 東京の時間帯、5営業日で知らせて、さらに5営業日で終える、15分ごとに判定する、調査は60秒で引き継ぐ設定
         */
        public static Rules defaults() {
            return new Rules(ZoneId.of("Asia/Tokyo"), new NoReply(5, 5), true, Duration.ofMinutes(15), Duration.ofSeconds(60));
        }

        /**
         * @return 返事がないときの期間（営業日）
         */
        public NoReplyRule.Periods periods() {
            return new NoReplyRule.Periods(noReply.remindAfterBusinessDays(), noReply.closeAfterBusinessDays(), zone);
        }
    }

    /**
     * 返事がないときの期間。
     *
     * @param remindAfterBusinessDays 結果を確かめる状態になってから、知らせるまでの営業日の数
     * @param closeAfterBusinessDays 知らせてから、終えるまでの営業日の数
     */
    public record NoReply(
            @DefaultValue("5") int remindAfterBusinessDays, @DefaultValue("5") int closeAfterBusinessDays) {}

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
     * 置き場所の相談先を返す。当てはまるマスの設定（詳しい質問と同じ選び方: 指定した項目が最も多いもの。同数なら、書かれた順で先のもの）の
     * {@code consultants}。なければ、システムの {@code consultants}。なければ、全体の {@code consultants}。
     *
     * @param placement 置き場所
     * @return 相談先の名前。どれも設定がなければ空
     */
    public List<String> consultantsFor(Placement placement) {
        CellEntry best = null;
        for (CellEntry cell : cells) {
            if (cell.appliesTo(placement) && (best == null || cell.specificity() > best.specificity())) {
                best = cell;
            }
        }
        if (best != null && !best.consultants().isEmpty()) {
            return best.consultants();
        }
        if (placement.systemId() != null) {
            List<String> system = systems.stream()
                    .filter(s -> s.id().equals(placement.systemId()))
                    .map(SystemEntry::consultants)
                    .filter(c -> !c.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            if (!system.isEmpty()) {
                return system;
            }
        }
        return consultants == null ? List.of() : consultants;
    }

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
        return nameOf(names, defaultLanguage(), fallback);
    }

    /**
     * 言語ごとの名前のうち、指定した言語の名前を返す。その言語の名前がなければ、項目ごとに既定の言語の名前に落とす。
     *
     * @param names 言語から名前への対応
     * @param language 見る人の言語
     * @param fallback 名前が1つもないときの値
     * @return 指定した言語の名前。なければ既定の言語の名前、最初に書かれた名前、fallback の順
     */
    public String nameOf(Map<String, String> names, String language, String fallback) {
        if (names != null) {
            for (String lang : List.of(language, defaultLanguage())) {
                String preferred = names.get(lang);
                if (preferred != null && !preferred.isBlank()) {
                    return preferred;
                }
            }
            for (String value : names.values()) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /** どの言語の名前も（空でないものを書かれた順に、重複なく）集める。文章から名前を読み取るために使う。 */
    private static List<String> allNames(Map<String, String> names, String label) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add(label);
        if (names != null) {
            names.values().stream().filter(v -> v != null && !v.isBlank()).forEach(all::add);
        }
        return List.copyOf(all);
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
        return catalog(defaultLanguage());
    }

    /**
     * 設定から、入口の質問を決める処理に渡す一覧を作る。名前は指定した言語に解決する（なければ項目ごとに既定の言語）。
     * 文章から読み取るためのほかの言語の名前も、あわせて持たせる。
     *
     * @param language 見る人の言語
     * @return 種類、緊急度、システム、機能、マスの設定
     */
    public IntakeCatalog catalog(String language) {
        List<IntakeCatalog.TypeInfo> typeInfos = java.util.Arrays.stream(InquiryType.values())
                .map(t -> {
                    NamedEntry entry = types.stream().filter(e -> t.name().equals(e.id())).findFirst().orElse(null);
                    return new IntakeCatalog.TypeInfo(
                            t,
                            entry == null ? t.name() : nameOf(entry.label(), language, t.name()),
                            entry == null ? "" : nameOf(entry.hint(), language, ""));
                })
                .toList();
        List<IntakeCatalog.UrgencyInfo> urgencyInfos = java.util.Arrays.stream(Urgency.values())
                .map(u -> {
                    NamedEntry entry = urgencies.stream().filter(e -> u.name().equals(e.id())).findFirst().orElse(null);
                    return new IntakeCatalog.UrgencyInfo(u, entry == null ? u.name() : nameOf(entry.label(), language, u.name()));
                })
                .toList();
        List<IntakeCatalog.SystemInfo> systemInfos = systems.stream()
                .map(s -> {
                    String label = nameOf(s.label(), language, s.id());
                    return new IntakeCatalog.SystemInfo(
                            s.id(),
                            label,
                            allNames(s.label(), label),
                            s.functions().stream()
                                    .map(f -> {
                                        String functionLabel = nameOf(f.label(), language, f.id());
                                        return new Choice(f.id(), functionLabel, allNames(f.label(), functionLabel));
                                    })
                                    .toList());
                })
                .toList();
        List<IntakeCatalog.Cell> cellInfos = cells.stream()
                .map(c -> new IntakeCatalog.Cell(
                        blankToNull(c.system()),
                        blankToNull(c.function()),
                        c.type(),
                        c.questions().stream().map(q -> question(q, language)).toList()))
                .toList();
        return new IntakeCatalog(typeInfos, urgencyInfos, systemInfos, cellInfos);
    }

    private IntakeQuestion question(QuestionEntry q, String language) {
        return new IntakeQuestion(
                q.id(),
                QuestionKind.DETAIL,
                nameOf(q.text(), language, q.id()),
                q.options().stream()
                        .map(o -> {
                            String label = nameOf(o.label(), language, o.id());
                            return new Choice(o.id(), label, allNames(o.label(), label));
                        })
                        .toList(),
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
     * @param consultants このシステムの相談先。省くと、全体の相談先
     */
    public record SystemEntry(
            String id,
            @DefaultValue Map<String, String> label,
            @DefaultValue List<FunctionEntry> functions,
            String slackWebhookUrl,
            @DefaultValue List<String> consultants) {

        public SystemEntry {
            consultants = consultants == null ? List.of() : List.copyOf(consultants);
        }
    }

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
     * @param consultants このマスの相談先。省くと、システムの相談先
     */
    public record CellEntry(
            String system,
            String function,
            InquiryType type,
            @DefaultValue List<QuestionEntry> questions,
            @DefaultValue List<String> consultants) {

        public CellEntry {
            questions = questions == null ? List.of() : questions;
            consultants = consultants == null ? List.of() : List.copyOf(consultants);
        }

        /**
         * 指定した項目の数。多いほど具体的な設定として優先する。
         *
         * @return 指定した項目の数
         */
        int specificity() {
            return (system != null ? 1 : 0) + (function != null ? 1 : 0) + (type != null ? 1 : 0);
        }

        boolean appliesTo(Placement placement) {
            return (system == null || system.equals(placement.systemId()))
                    && (function == null || function.equals(placement.functionId()))
                    && (type == null || type == placement.type());
        }
    }

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
