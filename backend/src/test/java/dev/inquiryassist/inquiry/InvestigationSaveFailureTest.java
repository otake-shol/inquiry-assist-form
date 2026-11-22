package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 時間切れで担当者に渡した後に、調査の結果の保存が失敗しても、調査の状況が {@code RUNNING} のまま残らない（{@code FAILED} になる）
 * ことを確かめる。時間切れの後は、保存の失敗を伝える相手（待っている画面）がいないので、裏での調査と同じ後始末をする。
 */
@SpringBootTest(
        properties = {
            "inquiry.mock.slow-step-delay=300ms",
            "inquiry.rules.answer-timeout=200ms",
            "inquiry.operator.heartbeat-interval=PT0.2S"
        })
class InvestigationSaveFailureTest extends ApiSupport {

    @MockitoSpyBean
    InquiryCommitter committer;

    @Test
    void 時間切れの後に結果の保存が失敗しても_調査はRUNNINGのまま残らず_担当者に渡ったまま() throws Exception {
        AtomicBoolean failedOnce = new AtomicBoolean();
        // 調査のスレッドの最初の保存（結果の反映）だけを失敗させる。その後の保存（失敗の記録）は、本物を通す
        doAnswer(invocation -> {
                    boolean investigation = Thread.currentThread().getName().startsWith("investigation-")
                            && !Thread.currentThread().getName().startsWith("investigation-timeout-");
                    if (investigation && failedOnce.compareAndSet(false, true)) {
                        throw new IllegalStateException("保存できない");
                    }
                    return invocation.callRealMethod();
                })
                .when(committer)
                .update(any(), any());

        String id = toAnswering("[遅延] 使い方を教えて", null);

        // 時間切れで担当者に渡り、調査の結果の保存が失敗する。調査の状況は、RUNNING のまま残らず、FAILED になる
        assertThat(waitFor(() -> "FAILED".equals(row(id).get("investigation_status")), 15_000))
                .as("調査の状況が FAILED にならない")
                .isTrue();
        assertThat(failedOnce).isTrue();
        assertThat(row(id).get("awaiting")).isEqualTo("HANDLING");
        assertThat(row(id).get("handover_reason")).isEqualTo("TIMEOUT");
        assertThat(row(id).get("investigation_finished_at")).isNotNull();
    }
}
