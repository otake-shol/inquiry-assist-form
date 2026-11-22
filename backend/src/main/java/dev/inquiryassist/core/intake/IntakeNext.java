package dev.inquiryassist.core.intake;

import java.util.List;

/** 入口で次にすること。 */
public sealed interface IntakeNext {

    /**
     * 本人に質問する。
     *
     * @param question 次の質問
     */
    record Ask(IntakeQuestion question) implements IntakeNext {}

    /**
     * 聞き手に、設定の質問の答えが文章に書かれているかを読み取らせてから、もう一度決める。
     *
     * @param questions 読み取る質問（まだ答えていない、読み取りを済ませていない設定の質問）
     */
    record NeedsPrefill(List<IntakeQuestion> questions) implements IntakeNext {}

    /** 聞き手に詳しい質問を作らせてから、もう一度決める。 */
    record NeedsInterviewer() implements IntakeNext {}

    /** 入口を終えた。問いを組み立てる。 */
    record Complete() implements IntakeNext {}
}
