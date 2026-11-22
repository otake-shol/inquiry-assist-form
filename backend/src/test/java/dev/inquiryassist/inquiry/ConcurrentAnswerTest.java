package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.TestcontainersConfiguration;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.HandoverReason;
import dev.inquiryassist.core.lifecycle.Turn;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 調査（0次回答の生成）は、問いを確かめた時点で API が1つだけ始め、画面（進捗ストリーム）は、それを見るだけであることを確かめる。
 * 同じ問い合わせに複数の画面がつないでも、調査は1つで、どの画面にも終わりが届く。
 */
@SpringBootTest(properties = "inquiry.mock.slow-step-delay=100ms")
@Import(TestcontainersConfiguration.class)
class ConcurrentAnswerTest {

    @Autowired
    InquiryService service;

    @Autowired
    JdbcTemplate jdbc;

    /** 進捗と終わりを記録する、画面の代わり。 */
    private static final class Recorder implements AnswerWatcher {
        final List<String> progress = new CopyOnWriteArrayList<>();
        final CompletableFuture<InquiryView> done = new CompletableFuture<>();
        final CompletableFuture<RuntimeException> failed = new CompletableFuture<>();
        final CountDownLatch firstProgress = new CountDownLatch(1);

        @Override
        public void progress(String message) {
            progress.add(message);
            firstProgress.countDown();
        }

        @Override
        public void done(InquiryView view) {
            done.complete(view);
        }

        @Override
        public void failed(RuntimeException failure) {
            failed.complete(failure);
        }
    }

    private int investigatorCalls(UUID id) {
        return jdbc.queryForObject(
                "select count(*) from inquiries, jsonb_array_elements(usages) u where id = ? and u->>'step' = 'investigator'",
                Integer.class,
                id);
    }

    @Test
    void 同じ問い合わせに2つつないでも調査は1つで_両方に終わりが届く() throws Exception {
        String utterance = "[遅延] 求人の掲載期限の仕様";
        var id = IntakeSteps.toStructured(service, utterance).id();

        // 問いを確かめると、API が調査を始める（つなぐのを待たない）
        service.confirmStructured(id, null, "ja");
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        service.watchAnswer(id, "ja", first);
        service.watchAnswer(id, "ja", second);

        assertThat(first.firstProgress.await(5, TimeUnit.SECONDS)).isTrue();
        InquiryView doneFirst = first.done.get(15, TimeUnit.SECONDS);
        InquiryView doneSecond = second.done.get(15, TimeUnit.SECONDS);

        assertThat(doneFirst.awaiting()).isEqualTo(Awaiting.RESULT_CONFIRMATION);
        assertThat(doneSecond.awaiting()).isEqualTo(Awaiting.RESULT_CONFIRMATION);
        assertThat(first.failed).isNotDone();
        assertThat(investigatorCalls(id)).isEqualTo(1);
        // つなぐ前に終わっていた調査には、すぐ done が届く（調査は増えない）
        Recorder late = new Recorder();
        service.watchAnswer(id, "ja", late);
        assertThat(late.done.get(5, TimeUnit.SECONDS).awaiting()).isEqualTo(Awaiting.RESULT_CONFIRMATION);
        assertThat(late.progress).isEmpty();
        assertThat(investigatorCalls(id)).isEqualTo(1);
    }

    @Test
    void 調べている間に今すぐ送ると調査は止まらず_結果は担当者だけに保存され_見ている画面にはdoneが届く() throws Exception {
        String utterance = "[遅延] 求人の掲載期限の仕様";
        var id = IntakeSteps.toStructured(service, utterance).id();
        service.confirmStructured(id, null, "ja");
        Recorder watcher = new Recorder();
        service.watchAnswer(id, "ja", watcher);
        assertThat(watcher.firstProgress.await(5, TimeUnit.SECONDS)).isTrue();

        InquiryView sent = service.sendNow(id, "ja");

        assertThat(sent.awaiting()).isEqualTo(Awaiting.HANDLING);
        assertThat(sent.turn()).isEqualTo(Turn.OPERATOR);
        assertThat(sent.handoverReason()).isEqualTo(HandoverReason.SENT_NOW);
        // 見ている画面には、調査の終わりを待たずに done が届く（担当者の番。本人向けの答えはない）
        InquiryView finished = watcher.done.get(2, TimeUnit.SECONDS);
        assertThat(finished.awaiting()).isEqualTo(Awaiting.HANDLING);
        assertThat(finished.answer()).isNull();
        InquiryView view = service.get(id, "ja");
        assertThat(view.sentImmediately()).isTrue();
        assertThat(view.answer()).isNull();
        // 走っている調査はそのまま続き、終わった時点の状態（担当者の番）で、裏の結果として保存される
        assertThat(ApiSupport.waitFor(() -> investigatorCalls(id) == 1 && "DONE".equals(investigationStatusOf(id)), 15_000)).isTrue();
        assertThat(jdbc.queryForObject("select investigation_audience from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("OPERATOR");
    }

    private String investigationStatusOf(UUID id) {
        return jdbc.queryForObject("select investigation_status from inquiries where id = ?::uuid", String.class, id);
    }
}
