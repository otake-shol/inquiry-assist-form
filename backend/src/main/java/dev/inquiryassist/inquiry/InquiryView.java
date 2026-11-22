package dev.inquiryassist.inquiry;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.lifecycle.Actor;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Declined;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.HandoverReason;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.Turn;
import dev.inquiryassist.core.intake.AnswerKind;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.PlacedBy;
import dev.inquiryassist.core.intake.QuestionKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 問い合わせ1件の表現（契約書の InquiryView）。
 *
 * @param id 識別子
 * @param createdAt 作成日時
 * @param language 本人の言語。AI が書くものはこの言語で書く
 * @param phase 区間
 * @param turn 誰の番か。終わった件は null
 * @param awaiting 待っていること。終わった件は null
 * @param ending 終わり方。終わっていない件は null
 * @param closedBy 終わらせた人（{@code ASKER}、{@code OPERATOR}、{@code TIME}）。終わっていない件は null。開き直すと null に戻る
 * @param declined 見送りの内容。見送りで終わった件だけ非 null
 * @param urgency 緊急度
 * @param type 種類。決まっていなければ null
 * @param system システム。決まっていなければ null
 * @param function 機能。決まっていなければ null
 * @param utterance 本人が書いた文章（最初の文章、切り替えたときの文章、補足を、書いた順に空行でつないだもの）。書いていなければ null
 * @param intake 入口の質問の状態
 * @param revisionsLeft 言い直しを作り直せる残り回数
 * @param structured 整形後の問い。{@code STRUCTURED} 以降で非 null
 * @param answer 0次回答。完了後に非 null。裏での調査の結果（担当者だけに見せるもの）は、本人向けには null のまま
 * @param canSendNow いま「答えを待たずに担当者へ送る」を実行できるか
 * @param sentImmediately 答えを待たずに担当者へ送られたか
 * @param handedOverAt 担当者に初めて渡した時刻。渡していなければ null
 * @param handoverReason 直近に担当者へ渡した理由。渡したことがなければ null
 * @param unreadReply 本人がまだ読んでいない知らせがあるか（担当者の発言、返事がないことの知らせ、期間が過ぎて終えたこと）
 * @param noReply 返事がないときの決まりの予定。本人の返事（結果の確かめ、聞き返しへの返事）を待っている間だけ非 null
 * @param messages 担当者の発言と本人の追記（古い順）。なければ空
 * @param trace スキルの実行記録。無効にしている場合は null
 * @param mock モック実装による記録か
 */
public record InquiryView(
        UUID id,
        Instant createdAt,
        String language,
        Phase phase,
        Turn turn,
        Awaiting awaiting,
        Ending ending,
        Actor closedBy,
        Declined declined,
        Urgency urgency,
        PlacedView type,
        PlacedView system,
        PlacedView function,
        String utterance,
        IntakeView intake,
        int revisionsLeft,
        StructuredView structured,
        AnswerView answer,
        boolean canSendNow,
        boolean sentImmediately,
        Instant handedOverAt,
        HandoverReason handoverReason,
        boolean unreadReply,
        NoReplyView noReply,
        List<MessageView> messages,
        List<TraceEntry> trace,
        boolean mock) {

    /**
     * 置いた置き場所（種類、システム、機能のどれか）。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     * @param by 決めたのが本人（{@code ASKER}）か AI（{@code AI}）か
     */
    public record PlacedView(String id, String label, PlacedBy by) {}

    /**
     * 入口の質問の状態。
     *
     * @param entries 答え終えた質問（聞いた順）
     * @param next 次に答える質問。入口が終わっていれば null
     * @param complete 入口が終わっているか
     */
    public record IntakeView(List<EntryView> entries, QuestionView next, boolean complete) {}

    /**
     * 答え終えた質問1問。
     *
     * @param questionId 質問の識別子
     * @param kind 質問の種類
     * @param text 質問文
     * @param answer 答え
     */
    public record EntryView(String questionId, QuestionKind kind, String text, EntryAnswerView answer) {}

    /**
     * 質問への答え。答え方によって、使わない項目は出力しない。
     *
     * @param kind 答え方
     * @param optionId 選んだ選択肢（または受付が置いた値）の識別子
     * @param label 選んだ選択肢（または受付が置いた値）の名前
     * @param text 文章で答えた場合の文章
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryAnswerView(AnswerKind kind, String optionId, String label, String text) {}

    /**
     * 次に答える質問。
     *
     * @param questionId 質問の識別子
     * @param kind 質問の種類
     * @param text 質問文
     * @param options 選択肢。空なら文章で答える
     * @param allowsText 選択肢のほかに文章でも答えられるか
     * @param canSkip 書かずに進めるか
     */
    public record QuestionView(
            String questionId, QuestionKind kind, String text, List<Choice> options, boolean allowsText, boolean canSkip) {}

    /**
     * 返事がないときの決まりの予定（契約書「返事がないとき」）。
     *
     * @param endsAs 期間が過ぎたときの終わり方（{@code ANSWERED} か {@code WITHDRAWN}）
     * @param remindAt 知らせる予定の時刻
     * @param remindedAt 実際に知らせた時刻。まだなら null
     * @param closesAt 終わる予定の時刻。知らせた後だけ非 null
     */
    public record NoReplyView(Ending endsAs, Instant remindAt, Instant remindedAt, Instant closesAt) {}

    /**
     * 整形後の問い。緊急度は持たない（問い合わせの {@code urgency} に一本化している）。
     *
     * @param summary 本人に見せる言い直しの文章
     * @param targetSystem 対象システム。不明なら null
     * @param goal やりたいこと・知りたいこと
     * @param whatHappened 起きたこと
     * @param expectedResult 期待した結果
     */
    public record StructuredView(
            String summary, String targetSystem, String goal, String whatHappened, String expectedResult) {}

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
