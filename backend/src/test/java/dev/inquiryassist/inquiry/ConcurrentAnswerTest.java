package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.TestcontainersConfiguration;
import java.util.List;
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
        var id = service.create(utterance, null).id();
        service.answerQuestions(id, List.of());
        service.confirmStructured(id, null);

        CountDownLatch firstProgress = new CountDownLatch(1);
        CompletableFuture<InquiryView> first =
                CompletableFuture.supplyAsync(() -> service.generateAnswer(id, m -> firstProgress.countDown()));
        assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.generateAnswer(id, m -> {})).isInstanceOf(InvalidStateException.class);

        assertThat(first.get(10, TimeUnit.SECONDS).step()).isEqualTo(InquiryStep.ANSWERED);
        // 完了後は段階が変わっているため、再度の生成も拒否される
        assertThatThrownBy(() -> service.generateAnswer(id, m -> {})).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void 生成中に今すぐ送ると生成側の保存は競合として拒否され起票は1件だけになる() throws Exception {
        String utterance = "[遅延] 求人の掲載期限の仕様";
        var id = service.create(utterance, null).id();
        service.answerQuestions(id, List.of());
        service.confirmStructured(id, null);

        CountDownLatch firstProgress = new CountDownLatch(1);
        CompletableFuture<InquiryView> generating =
                CompletableFuture.supplyAsync(() -> service.generateAnswer(id, m -> firstProgress.countDown()));
        assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();

        InquiryView sent = service.sendNow(id);

        assertThat(sent.outcome()).isEqualTo(InquiryOutcome.FILED);
        assertThatThrownBy(() -> generating.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidStateException.class);
        assertThat(service.get(id).sentImmediately()).isTrue();
        assertThat(service.get(id).answer()).isNull();
    }
}
