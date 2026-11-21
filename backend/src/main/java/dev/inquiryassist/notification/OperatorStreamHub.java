package dev.inquiryassist.notification;

import dev.inquiryassist.inquiry.InquiryFiledEvent;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * オペレータ画面への、ツール内の通知。接続中の購読者へ問い合わせの送付を SSE の {@code filed} で配る（プロセス内の配信）。
 *
 * <p>接続を保つためコメント行を定期的に送り、送信に失敗した（切断済みの）購読者はそのとき取り除く。
 */
@Component
public class OperatorStreamHub {

    private static final Logger log = LoggerFactory.getLogger(OperatorStreamHub.class);
    /** 接続を維持する時間の上限。切れても画面が再接続する。 */
    private static final long TIMEOUT_MILLIS = 60L * 60 * 1000;

    private final Set<SseEmitter> subscribers = new CopyOnWriteArraySet<>();

    /**
     * 購読者を登録する。接続の完了・タイムアウト・エラーで自動的に取り除く。
     *
     * @return 購読者の SSE
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        register(emitter);
        return emitter;
    }

    /**
     * 既存の SSE を購読者として登録する。
     *
     * @param emitter 登録する SSE
     */
    void register(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        // 登録直後に1回送り、接続が確立したことをクライアントに伝える
        send(emitter, SseEmitter.event().comment("connected"));
    }

    /**
     * 問い合わせの送付を全購読者へ配る。
     *
     * @param event 送付のイベント
     */
    @EventListener
    public void onFiled(InquiryFiledEvent event) {
        for (SseEmitter emitter : subscribers) {
            send(emitter, SseEmitter.event().name("filed").data(event.item(), MediaType.APPLICATION_JSON));
        }
    }

    /** 接続維持のコメント行を全購読者へ送り、切断した購読者を掃除する。 */
    @Scheduled(fixedDelayString = "${inquiry.operator.heartbeat-interval:PT15S}")
    public void heartbeat() {
        for (SseEmitter emitter : subscribers) {
            send(emitter, SseEmitter.event().comment("keepalive"));
        }
    }

    /**
     * @return 現在の購読者数
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
            try {
                emitter.completeWithError(e);
            } catch (RuntimeException ignored) {
                log.debug("切断済みの購読者を取り除いた");
            }
        }
    }
}
