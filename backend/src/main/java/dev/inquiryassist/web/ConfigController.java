package dev.inquiryassist.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
     */
    public record ConfigResponse(boolean mock, String filedGuidance) {}

    private final Environment environment;
    private final String filedGuidance;

    /**
     * @param environment 有効なプロファイルの判定に使う
     * @param filedGuidance 担当者に送った後の案内文
     */
    public ConfigController(
            Environment environment, @Value("${inquiry.filed-guidance}") String filedGuidance) {
        this.environment = environment;
        this.filedGuidance = filedGuidance;
    }

    /**
     * @return モック動作中かどうかと、起票後の案内文
     */
    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(environment.acceptsProfiles(Profiles.of("mock")), filedGuidance);
    }
}
