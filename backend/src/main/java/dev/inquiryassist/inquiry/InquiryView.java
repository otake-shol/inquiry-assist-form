package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 問い合わせ1件の表現（契約書の InquiryView）。
 *
 * @param id 識別子
 * @param step 段階
 * @param outcome 結果
 * @param utterance 本人が書いた元の発話
 * @param categoryByAi AI が判定した種別
 * @param category 本人が確認・変更した後の種別
 * @param questions 現在の追加質問
 * @param revisionsLeft 言い直しを作り直せる残り回数
 * @param system 対象システム。決まっていなければ null
 * @param systemGuessed 対象システムを AI が推測した値か
 * @param structured 整形後の問い。{@code STRUCTURED} 以降で非 null
 * @param answer 0次回答。完了後に非 null
 * @param incidentGuidance 障害の案内文。{@code INCIDENT_REDIRECTED} のときだけ非 null
 * @param sentImmediately 「今すぐ担当者に送る」による起票か
 * @param mock モック実装による記録か
 * @param createdAt 作成日時
 * @param handlingStatus 対応状況。担当者に送られていなければ null
 * @param messages 担当者の返信と本人の追記（古い順）。なければ空
 * @param submittedAnswers 本人が送信した追加質問への回答。まだ送信していなければ null
 * @param trace スキルの実行記録。無効にしている場合は null
 */
public record InquiryView(
        UUID id,
        InquiryStep step,
        InquiryOutcome outcome,
        String utterance,
        InquiryCategory categoryByAi,
        InquiryCategory category,
        List<QuestionView> questions,
        int revisionsLeft,
        SystemView system,
        boolean systemGuessed,
        StructuredView structured,
        AnswerView answer,
        String incidentGuidance,
        boolean sentImmediately,
        boolean mock,
        Instant createdAt,
        HandlingStatus handlingStatus,
        List<MessageView> messages,
        List<SubmittedAnswer> submittedAnswers,
        List<TraceEntry> trace) {

    /**
     * 本人が送信した追加質問への回答1件。
     *
     * @param questionId 質問の識別子
     * @param answer 回答。「わからない」は null
     */
    public record SubmittedAnswer(String questionId, String answer) {}

    /**
     * 対象システム。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public record SystemView(String id, String label) {}

    /**
     * 追加質問。
     *
     * @param id 質問の識別子
     * @param text 質問文
     * @param options 選択肢。空なら自由記述
     */
    public record QuestionView(String id, String text, List<String> options) {}

    /**
     * 整形後の問い。
     *
     * @param summary 本人に見せる言い直しの文章
     * @param targetSystem 対象システム。不明なら null
     * @param goal やりたいこと・知りたいこと
     * @param whatHappened 起きたこと
     * @param expectedResult 期待した結果
     * @param urgency 緊急度
     */
    public record StructuredView(
            String summary,
            String targetSystem,
            String goal,
            String whatHappened,
            String expectedResult,
            StructuredInquiry.Urgency urgency) {}

    /**
     * 0次回答。{@code citations} には照合できた出典だけを入れる。
     *
     * @param grounded 照合できた出典に根拠があるか
     * @param answered 確かめられた事実が本人の問いに答えているか。grounded でなければ常に false
     * @param askerExplanation 本人向けの説明。根拠なしなら null
     * @param memo 担当者向けの技術メモ
     * @param citations 照合できた出典
     */
    public record AnswerView(
            boolean grounded,
            boolean answered,
            String askerExplanation,
            TechnicalMemo memo,
            List<Citation> citations) {}
}
