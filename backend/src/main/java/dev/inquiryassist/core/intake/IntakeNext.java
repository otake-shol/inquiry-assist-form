package dev.inquiryassist.core.intake;

/** 入口で次にすること。 */
public sealed interface IntakeNext {

    /**
     * 本人に質問する。
     *
     * @param question 次の質問
     */
    record Ask(IntakeQuestion question) implements IntakeNext {}

    /** 聞き手に詳しい質問を作らせてから、もう一度決める。 */
    record NeedsInterviewer() implements IntakeNext {}

    /** 入口を終えた。問いを組み立てる。 */
    record Complete() implements IntakeNext {}
}
