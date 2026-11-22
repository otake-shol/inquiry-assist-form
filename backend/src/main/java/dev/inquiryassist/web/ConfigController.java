package dev.inquiryassist.web;

import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.inquiry.CurrentUserProvider;
import dev.inquiryassist.inquiry.InquiryProperties;
import dev.inquiryassist.inquiry.SkillImplementations;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 画面が起動時に読む設定を返す（契約書 1）。 */
@RestController
public class ConfigController {

    /**
     * 画面向けの設定。
     *
     * @param mock モック動作中か
     * @param filedGuidance 担当者に送った後の案内文
     * @param languages 名前を持つ言語。先頭が既定の言語
     * @param types 種類（聞く順）
     * @param urgencies 緊急度
     * @param systems システムと、その機能の一覧
     * @param skills スキルの識別子から、動いている実装の名前（mock または ollama）への対応
     * @param trace スキルの実行記録を見せるか
     * @param user 現在の利用者
     * @param operatorName 担当者の表示名
     */
    public record ConfigResponse(
            boolean mock,
            String filedGuidance,
            List<String> languages,
            List<TypeView> types,
            List<UrgencyView> urgencies,
            List<SystemView> systems,
            Map<String, String> skills,
            boolean trace,
            UserView user,
            String operatorName) {}

    /**
     * 現在の利用者。認証を加えるまでは、固定の利用者と設定の表示名。
     *
     * @param id 識別子
     * @param name 表示名
     */
    public record UserView(String id, String name) {}

    /**
     * 種類。
     *
     * @param id 識別子
     * @param label 既定の言語の名前
     * @param hint 名前を補う短い説明
     */
    public record TypeView(String id, String label, String hint) {}

    /**
     * 緊急度。
     *
     * @param id 識別子
     * @param label 既定の言語の名前
     */
    public record UrgencyView(String id, String label) {}

    /**
     * システム。
     *
     * @param id 識別子
     * @param label 既定の言語の名前
     * @param functions このシステムの機能。機能の設定がなければ空
     */
    public record SystemView(String id, String label, List<Choice> functions) {}

    private final SkillImplementations implementations;
    private final String filedGuidance;
    private final InquiryProperties properties;
    private final IntakeCatalog catalog;
    private final CurrentUserProvider users;

    /**
     * @param implementations スキルごとの実装
     * @param filedGuidance 担当者に送った後の案内文
     * @param properties 言語、実行記録、表示名の設定
     * @param catalog 種類、緊急度、システム、機能の名前（既定の言語）
     * @param users 現在の利用者
     */
    public ConfigController(
            SkillImplementations implementations,
            @Value("${inquiry.filed-guidance}") String filedGuidance,
            InquiryProperties properties,
            IntakeCatalog catalog,
            CurrentUserProvider users) {
        this.users = users;
        this.implementations = implementations;
        this.properties = properties;
        this.catalog = catalog;
        this.filedGuidance = filedGuidance;
    }

    /**
     * @return モック動作中かどうか、案内文、言語、種類、緊急度、システムと機能、実装、実行記録の有無、利用者と担当者の表示名
     */
    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(
                implementations.includesMock(),
                filedGuidance,
                properties.languages(),
                catalog.types().stream()
                        .map(t -> new TypeView(t.type().name(), t.label(), t.hint()))
                        .toList(),
                catalog.urgencies().stream()
                        .map(u -> new UrgencyView(u.urgency().name(), u.label()))
                        .toList(),
                catalog.systems().stream()
                        .map(s -> new SystemView(s.id(), s.label(), s.functions()))
                        .toList(),
                implementations.byId(),
                properties.trace().enabled(),
                new UserView(users.currentScope().userId(), properties.dev().userName()),
                properties.dev().operatorName());
    }
}
