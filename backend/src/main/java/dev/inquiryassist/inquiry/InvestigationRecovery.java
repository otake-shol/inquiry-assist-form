package dev.inquiryassist.inquiry;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 起動時に、調べている最中（{@code RUNNING}）のまま残っている調査を失敗にする。裏での調査は API のプロセスの中で走るので、
 * 止まった後には、終わらないまま残る（契約書「調査の状況と、裏での調査」）。
 */
@Component
class InvestigationRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRecovery.class);

    private final InvestigationRunner runner;

    /**
     * @param runner 調査の実行役
     */
    InvestigationRecovery(InvestigationRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        int failed = runner.failRunning(Instant.now());
        if (failed > 0) {
            log.warn("止まった調査を失敗にしました count={}", failed);
        }
    }
}
