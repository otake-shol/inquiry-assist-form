package dev.inquiryassist.inquiry;

/** 問い合わせ1件の進行位置（契約書の「段階」）。 */
public enum InquiryStep {
    /** 入口の質問に答えている。 */
    INTAKE,
    /** 整形後の問いが出た。 */
    STRUCTURED,
    /** 0次回答の生成待ち。 */
    ANSWERING,
    /** 0次回答が出て、本人の解決判定待ち。 */
    ANSWERED,
    /** 終了。結果は {@link InquiryOutcome} が示す。 */
    CLOSED
}
