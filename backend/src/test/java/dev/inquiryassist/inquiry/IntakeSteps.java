package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.intake.IntakeReply;

/** サービスを直接呼ぶテストで、入口の質問を「わからない」で最後まで進める補助。 */
final class IntakeSteps {

    private IntakeSteps() {}

    /**
     * 文章で始めて、残りの質問に「わからない」と答え（補足は、書かずに進めるなら進み、進めなければ短い文章を書く）、
     * 問いを組み立てた（{@code STRUCTURED} の）問い合わせを返す。
     */
    static InquiryView toStructured(InquiryService service, String text) {
        InquiryView view = service.create(text, null);
        for (int guard = 0; guard < 20 && view.intake().next() != null; guard++) {
            InquiryView.QuestionView next = view.intake().next();
            IntakeReply reply = next.kind() == QuestionKind.NOTE
                    ? (next.canSkip()
                            ? new IntakeReply(next.questionId(), null, null, false, true)
                            : new IntakeReply(next.questionId(), null, "状況", false, false))
                    : new IntakeReply(next.questionId(), null, null, true, false);
            view = service.answerIntake(view.id(), reply);
        }
        return view;
    }
}
