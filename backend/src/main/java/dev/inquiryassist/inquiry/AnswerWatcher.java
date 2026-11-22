package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;

/**
 * 調査（0次回答の生成）を見ている画面（進捗ストリーム）。進捗と、終わったこと・失敗したことを受け取る。
 * 終わったこと（{@link #done}）と失敗（{@link #failed}）は、あわせて1回だけ届く。
 */
public interface AnswerWatcher {

    /**
     * 進捗を受け取る。つないだ後の分だけが届く。
     *
     * @param message 本人に見せる進捗の文言
     */
    void progress(String message);

    /**
     * 調査を待つ状態が終わった（結果が出た、または担当者に渡した）。
     *
     * @param view いまの問い合わせ
     */
    void done(InquiryView view);

    /**
     * 調査が失敗した。待っていることは {@code INVESTIGATION} のままで、つなぎ直すと再試行になる。
     *
     * @param failure {@link AiRefusalException}、{@link AiUnavailableException}、または契約外の失敗
     */
    void failed(RuntimeException failure);
}
