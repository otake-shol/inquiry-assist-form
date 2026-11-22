package dev.inquiryassist.web;

import dev.inquiryassist.inquiry.TimeRuleService;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 開発用: 返事がないときの決まりを、時刻を指定して判定する口（契約書「開発用: 決まりの判定を、時刻を指定して行う」）。
 * 期間が過ぎるのを待たずに確かめるための口。{@code production} プロファイルでは作らない（404）。
 */
@RestController
@Profile("!production")
public class DevRulesController {

    /**
     * @param now 現在として扱う時刻。省くと現在時刻
     */
    public record RunRequest(Instant now) {}

    /**
     * @param reminded 知らせた件数
     * @param closed 終えた件数
     */
    public record RunResponse(int reminded, int closed) {}

    private final TimeRuleService rules;

    /**
     * @param rules 決まりの判定
     */
    public DevRulesController(TimeRuleService rules) {
        this.rules = rules;
    }

    /**
     * 与えた時刻を現在として、判定を1回行う。
     *
     * @param request 時刻。本文ごと省いてもよい
     * @return 知らせた件数と、終えた件数
     */
    @PostMapping("/api/dev/rules/run")
    public RunResponse run(@RequestBody(required = false) RunRequest request) {
        Instant now = request == null || request.now() == null ? Instant.now() : request.now();
        TimeRuleService.Result result = rules.run(now);
        return new RunResponse(result.reminded(), result.closed());
    }
}
