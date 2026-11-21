package dev.inquiryassist.web;

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
     */
    public record ConfigResponse(boolean mock) {}

    private final Environment environment;

    /**
     * @param environment 有効なプロファイルの判定に使う
     */
    public ConfigController(Environment environment) {
        this.environment = environment;
    }

    /**
     * @return モック動作中かどうか
     */
    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(environment.acceptsProfiles(Profiles.of("mock")));
    }
}
