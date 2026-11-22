package dev.inquiryassist.inquiry;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 調査（0次回答の生成）の進み具合を、見ている画面（進捗ストリーム）へ配る。プロセスの中の購読で、問い合わせの識別子ごとに、
 * 聞き手の一覧を持つ。聞き手がいなくても、調査は進む。聞き手への送信の失敗は、調査に影響させない。
 */
@Component
class AnswerStreamHub {

    private static final Logger log = LoggerFactory.getLogger(AnswerStreamHub.class);

    /** 進み具合の聞き手。 */
    interface Listener {

        /**
         * 進捗を受け取る。
         *
         * @param message 進捗の文言
         */
        void progress(String message);

        /** 調査を待つ状態が終わった（結果が出た、担当者に渡した）。 */
        void done();

        /**
         * 調査が失敗した。{@code awaiting} は {@code INVESTIGATION} のまま。
         *
         * @param failure 失敗
         */
        void failed(RuntimeException failure);
    }

    private final Map<UUID, Set<Listener>> listeners = new ConcurrentHashMap<>();

    /**
     * 聞き手を登録する。
     *
     * @param id 問い合わせの識別子
     * @param listener 聞き手
     * @return 登録を外す処理
     */
    Runnable subscribe(UUID id, Listener listener) {
        listeners.computeIfAbsent(id, k -> new CopyOnWriteArraySet<>()).add(listener);
        return () -> unsubscribe(id, listener);
    }

    private void unsubscribe(UUID id, Listener listener) {
        listeners.computeIfPresent(id, (k, set) -> {
            set.remove(listener);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * 進捗を配る。
     *
     * @param id 問い合わせの識別子
     * @param message 進捗の文言
     */
    void progress(UUID id, String message) {
        each(id, l -> l.progress(message));
    }

    /**
     * 調査を待つ状態が終わったことを配り、聞き手を外す。
     *
     * @param id 問い合わせの識別子
     */
    void done(UUID id) {
        Set<Listener> all = listeners.remove(id);
        if (all != null) {
            all.forEach(l -> safely(() -> l.done()));
        }
    }

    /**
     * 調査の失敗を配り、聞き手を外す（つなぎ直せば、再試行になる）。
     *
     * @param id 問い合わせの識別子
     * @param failure 失敗
     */
    void failed(UUID id, RuntimeException failure) {
        Set<Listener> all = listeners.remove(id);
        if (all != null) {
            all.forEach(l -> safely(() -> l.failed(failure)));
        }
    }

    private void each(UUID id, java.util.function.Consumer<Listener> action) {
        Set<Listener> all = listeners.get(id);
        if (all != null) {
            all.forEach(l -> safely(() -> action.accept(l)));
        }
    }

    /** 聞き手の失敗は、調査に影響させない。 */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("進捗の聞き手への送信に失敗しました cause={}", e.getClass().getSimpleName());
        }
    }
}
