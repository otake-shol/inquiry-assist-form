package dev.inquiryassist.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.inquiry.HandlingStatus;
import dev.inquiryassist.inquiry.InquiryFiledEvent;
import dev.inquiryassist.inquiry.OperatorItem;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class OperatorStreamHubTest {

    /** 送信の回数を数え、指定すると送信が IOException で失敗する（切断済みの購読者）。 */
    private static final class CountingEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();
        final boolean broken;

        CountingEmitter(boolean broken) {
            this.broken = broken;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            if (broken) {
                throw new IOException("切断済み");
            }
        }
    }

    private static InquiryFiledEvent event() {
        return new InquiryFiledEvent(
                new OperatorItem(UUID.randomUUID(), Instant.now(), InquiryCategory.SPEC_QUESTION, null, "要約", false, false, HandlingStatus.OPEN, 0));
    }

    @Test
    void 送付のイベントは全購読者に配られる() {
        OperatorStreamHub hub = new OperatorStreamHub();
        CountingEmitter a = new CountingEmitter(false);
        CountingEmitter b = new CountingEmitter(false);
        hub.register(a);
        hub.register(b);
        int afterConnect = a.sends.get();

        hub.onFiled(event());

        assertThat(a.sends.get()).isEqualTo(afterConnect + 1);
        assertThat(b.sends.get()).isEqualTo(afterConnect + 1);
        assertThat(hub.subscriberCount()).isEqualTo(2);
    }

    @Test
    void 切断した購読者は送信の失敗で取り除かれ他の購読者は影響を受けない() {
        OperatorStreamHub hub = new OperatorStreamHub();
        CountingEmitter alive = new CountingEmitter(false);
        hub.register(alive);
        CountingEmitter broken = new CountingEmitter(true);
        hub.register(broken); // 接続直後の送信で失敗する

        assertThat(hub.subscriberCount()).isEqualTo(1);
        int before = alive.sends.get();
        hub.heartbeat();
        hub.onFiled(event());

        assertThat(alive.sends.get()).isEqualTo(before + 2);
        assertThat(broken.sends.get()).isEqualTo(1);
    }

    @Test
    void 接続維持のコメント行を定期的に送る() {
        OperatorStreamHub hub = new OperatorStreamHub();
        CountingEmitter emitter = new CountingEmitter(false);
        hub.register(emitter);
        int before = emitter.sends.get();

        hub.heartbeat();
        hub.heartbeat();

        assertThat(emitter.sends.get()).isEqualTo(before + 2);
    }
}
