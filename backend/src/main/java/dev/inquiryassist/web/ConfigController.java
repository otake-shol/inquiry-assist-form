package dev.inquiryassist.web;

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
     * @param systems 対象システムの一覧
     * @param skills スキルの識別子から、動いている実装の名前（mock または ollama）への対応
     */
    public record ConfigResponse(
            boolean mock, String filedGuidance, List<SystemView> systems, Map<String, String> skills) {}

    /**
     * 対象システム。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public record SystemView(String id, String label) {}

    private final SkillImplementations implementations;
    private final String filedGuidance;
    private final InquiryProperties properties;

    /**
     * @param implementations スキルごとの実装
     * @param filedGuidance 担当者に送った後の案内文
     * @param properties 対象システムの一覧
     */
    public ConfigController(
            SkillImplementations implementations,
            @Value("${inquiry.filed-guidance}") String filedGuidance,
            InquiryProperties properties) {
        this.implementations = implementations;
        this.properties = properties;
        this.filedGuidance = filedGuidance;
    }

    /**
     * @return モック動作中かどうかと、起票後の案内文
     */
    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(implementations.includesMock(),
                filedGuidance,
                properties.systems().stream()
                        .map(s -> new SystemView(s.id(), s.label()))
                        .toList(),
                implementations.byId());
    }
}
