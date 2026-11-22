package dev.inquiryassist.inquiry;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 時間で動く決まりを、設定の間隔（{@code inquiry.rules.sweep-interval}）ごとに判定する。
 * {@code inquiry.rules.sweep-enabled=false} なら作らない（テストは、判定を直接呼ぶ）。
 */
@Component
@ConditionalOnProperty(name = "inquiry.rules.sweep-enabled", havingValue = "true", matchIfMissing = true)
class TimeRuleSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeRuleSweeper.class);

    private final TimeRuleService rules;

    /**
     * @param rules 決まりの判定
     */
    TimeRuleSweeper(TimeRuleService rules) {
        this.rules = rules;
    }

    /** 判定を1回行う。失敗しても、次の回に実行され続けるよう、例外は記録だけして握る。 */
    @Scheduled(fixedDelayString = "${inquiry.rules.sweep-interval:15m}")
    void sweep() {
        try {
            TimeRuleService.Result result = rules.run(Instant.now());
            if (result.reminded() > 0 || result.closed() > 0) {
                log.info("返事がないときの決まりを適用 reminded={} closed={}", result.reminded(), result.closed());
            }
        } catch (RuntimeException e) {
            log.error("返事がないときの決まりの判定に失敗", e);
        }
    }
}
