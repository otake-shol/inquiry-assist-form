package dev.inquiryassist.inquiry;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 起動時に、調べている最中（{@code RUNNING}）のまま残っている調査と、作っている最中のまま残っている案を失敗にし、調査を待っていた（{@code INVESTIGATION}）件を担当者に渡す。裏での調査は API のプロセスの中で走るので、
 * 止まった後には、終わらないまま残る（契約書「調査の状況と、裏での調査」）。
 */
@Component
class InvestigationRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRecovery.class);

    private final InvestigationRunner runner;
    private final AdviceRunner adviceRunner;

    /**
     * @param runner 調査の実行役
     * @param adviceRunner 案の実行役
     */
    InvestigationRecovery(InvestigationRunner runner, AdviceRunner adviceRunner) {
        this.runner = runner;
        this.adviceRunner = adviceRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        int failed = runner.failRunning(Instant.now());
        if (failed > 0) {
            log.warn("止まった調査を失敗にしました count={}", failed);
        }
        // 走っていた調査は失われた。本人を待つ状態のまま残った件は、担当者に渡す（初めて渡すので、通知が働く）
        int handedOver = runner.handOverWaiting();
        if (handedOver > 0) {
            log.warn("調査を待っていた件を担当者に渡しました count={}", handedOver);
        }
        int failedAdvice = adviceRunner.failRunning();
        if (failedAdvice > 0) {
            log.warn("止まった案の作成を失敗にしました count={}", failedAdvice);
        }
    }
}
