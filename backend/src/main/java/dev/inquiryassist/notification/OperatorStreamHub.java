package dev.inquiryassist.notification;

import dev.inquiryassist.inquiry.InquiryFiledEvent;
import dev.inquiryassist.inquiry.InquiryUpdatedEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * オペレータ画面への、ツール内の通知。接続中の購読者へ問い合わせの送付を SSE の {@code filed}、送付後の更新を {@code updated} で配る（プロセス内の配信）。
 *
 * <p>接続を保つためコメント行を定期的に送り、送信に失敗した（切断済みの）購読者はそのとき取り除く。
 */
@Component
public class OperatorStreamHub {

    private static final Logger log = LoggerFactory.getLogger(OperatorStreamHub.class);
    /** 接続を維持する時間の上限。切れても画面が再接続する。 */
    private static final long TIMEOUT_MILLIS = 60L * 60 * 1000;

    /** 購読者と、その接続の言語（空は既定の言語）。 */
    private final Map<SseEmitter, String> subscribers = new ConcurrentHashMap<>();

    /**
     * 購読者を登録する。接続の完了・タイムアウト・エラーで自動的に取り除く。
     *
     * @return 購読者の SSE
     */
    public SseEmitter subscribe() {
        return subscribe("");
    }

    /**
     * 言語つきで購読者を登録する。送る問い合わせの一覧の1件は、この言語の名前にする。
     *
     * @param language 接続の言語（クエリの {@code lang} で決めた、見る人の言語）
     * @return 購読者の SSE
     */
    public SseEmitter subscribe(String language) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        register(emitter, language);
        return emitter;
    }

    /**
     * 既存の SSE を購読者として登録する。
     *
     * @param emitter 登録する SSE
     */
    void register(SseEmitter emitter) {
        register(emitter, "");
    }

    /**
     * 既存の SSE を、言語つきで購読者として登録する。
     *
     * @param emitter 登録する SSE
     * @param language 接続の言語
     */
    void register(SseEmitter emitter, String language) {
        subscribers.put(emitter, language == null ? "" : language);
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
        subscribers.forEach((emitter, language) -> send(
                emitter, SseEmitter.event().name("filed").data(event.itemFor(language), MediaType.APPLICATION_JSON)));
    }

    /**
     * 送付後の問い合わせの更新（本人の追記、対応状況の変更、担当者の返信）を全購読者へ配る。別の担当者の画面を揃えるため。
     *
     * @param event 更新のイベント
     */
    @EventListener
    public void onUpdated(InquiryUpdatedEvent event) {
        subscribers.forEach((emitter, language) -> send(
                emitter, SseEmitter.event().name("updated").data(event.itemFor(language), MediaType.APPLICATION_JSON)));
    }

    /** 接続維持のコメント行を全購読者へ送り、切断した購読者を掃除する。 */
    @Scheduled(fixedDelayString = "${inquiry.operator.heartbeat-interval:PT15S}")
    public void heartbeat() {
        for (SseEmitter emitter : subscribers.keySet()) {
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
