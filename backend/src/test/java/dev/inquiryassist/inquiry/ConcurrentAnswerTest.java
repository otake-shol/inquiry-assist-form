package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.TestcontainersConfiguration;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.IllegalTransitionException;
import dev.inquiryassist.core.lifecycle.Turn;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 同じ問い合わせの0次回答生成が並行して走らないことを確認する。 */
@SpringBootTest(properties = "inquiry.mock.slow-step-delay=100ms")
@Import(TestcontainersConfiguration.class)
class ConcurrentAnswerTest {

    @Autowired
    InquiryService service;

    @Test
    void 生成中の問い合わせの2回目の生成はINVALID_STATEで拒否され1回目は完了する() throws Exception {
        String utterance = "[遅延] 求人の掲載期限の仕様";
        var id = IntakeSteps.toStructured(service, utterance).id();
        service.confirmStructured(id, null, "ja");

        CountDownLatch firstProgress = new CountDownLatch(1);
        CompletableFuture<InquiryView> first =
                CompletableFuture.supplyAsync(() -> service.generateAnswer(id, m -> firstProgress.countDown(), "ja"));
        assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.generateAnswer(id, m -> {}, "ja")).isInstanceOf(InvalidStateException.class);

        assertThat(first.get(10, TimeUnit.SECONDS).awaiting()).isEqualTo(Awaiting.RESULT_CONFIRMATION);
        // 完了後は待っていることが変わっているため、再度の生成も拒否される
        assertThatThrownBy(() -> service.generateAnswer(id, m -> {}, "ja")).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void 生成中に今すぐ送ると生成は止まらず_結果は担当者だけに保存され_起票は1件だけになる() throws Exception {
        String utterance = "[遅延] 求人の掲載期限の仕様";
        var id = IntakeSteps.toStructured(service, utterance).id();
        service.confirmStructured(id, null, "ja");

        CountDownLatch firstProgress = new CountDownLatch(1);
        CompletableFuture<InquiryView> generating =
                CompletableFuture.supplyAsync(() -> service.generateAnswer(id, m -> firstProgress.countDown(), "ja"));
        assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();

        InquiryView sent = service.sendNow(id, "ja");

        assertThat(sent.awaiting()).isEqualTo(Awaiting.HANDLING);
        assertThat(sent.turn()).isEqualTo(Turn.OPERATOR);
        assertThat(sent.handoverReason()).isEqualTo(dev.inquiryassist.core.lifecycle.HandoverReason.SENT_NOW);
        // 走っている調査はそのまま続き、終わった時点の状態（担当者の番）で、裏の結果として保存される。待っていた側には本人向けの答えがない
        InquiryView finished = generating.get(15, TimeUnit.SECONDS);
        assertThat(finished.awaiting()).isEqualTo(Awaiting.HANDLING);
        assertThat(finished.answer()).isNull();
        InquiryView view = service.get(id, "ja");
        assertThat(view.sentImmediately()).isTrue();
        assertThat(view.answer()).isNull();
    }
}
