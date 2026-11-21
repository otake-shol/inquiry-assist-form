package dev.inquiryassist.core.skill;

/** 調査の進捗を受け取る。画面の進捗表示に使う。 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * 進捗を1件通知する。
     *
     * @param message 本人に見せる進捗の文言
     */
    void onProgress(String message);
}
