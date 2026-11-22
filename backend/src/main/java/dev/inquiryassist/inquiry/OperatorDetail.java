package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.lifecycle.Actor;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.skill.Explanation;
import dev.inquiryassist.core.lifecycle.Declined;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.HandoverReason;
import dev.inquiryassist.core.lifecycle.HistoryEntry;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.Turn;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * オペレータ向けの問い合わせの全内容。
 *
 * @param id 識別子
 * @param filedAt 担当者に初めて渡した時刻
 * @param language 本人の言語
 * @param askerId 投稿者
 * @param askerName 投稿者の表示用の名前。認証を加えるまでは設定の固定値
 * @param utterance 本人が書いた文章（最初の文章、切り替えたときの文章、補足を、書いた順に空行でつないだもの）
 * @param urgency 緊急度
 * @param type 種類
 * @param system システム
 * @param function 機能
 * @param intake 入口の答え（聞いた順）。本人が何を選び、何に「わからない」と答えたかを示す
 * @param structured 整形後の問い
 * @param revisionNotes 本人が「少し違います」で伝えた一言
 * @param answer 0次回答。出典は照合に落ちたものも含む
 * @param sentImmediately 答えを待たずに担当者へ送られたか
 * @param read 既読か
 * @param slackStatus Slack への投稿の状態
 * @param phase 区間
 * @param turn 誰の番か。終わった件は null
 * @param awaiting 待っていること。終わった件は null
 * @param ending 終わり方。終わっていない件は null
 * @param closedBy 終わらせた人（{@code ASKER}、{@code OPERATOR}、{@code TIME}）。終わっていない件は null
 * @param declined 見送りの内容。見送りで終わった件だけ非 null
 * @param handoverReason 直近に担当者へ渡した理由
 * @param investigation 調査の状況
 * @param advice AI の案（判断の案と手順の案）
 * @param defaultConsultants 既定の相談先（置き場所から決まる）。なければ空
 * @param consultations 仕様の持ち主への相談（古い順）
 * @param decisions この問い合わせから生まれた先例（古い順）
 * @param precedents 同じ置き場所の先例（機能が同じものが先、新しい順、最大5件。その問い合わせ自身の先例は含めない）
 * @param noReply 返事がないときの決まりの予定。本人の返事（結果の確かめ、聞き返しへの返事）を待っている間だけ非 null
 * @param messages 担当者の発言と本人の追記（古い順）
 * @param history 状態の履歴（起きた順）
 * @param trace スキルの実行記録。無効にしている場合は null
 */
public record OperatorDetail(
        UUID id,
        Instant filedAt,
        String language,
        String askerId,
        String askerName,
        String utterance,
        Urgency urgency,
        InquiryView.PlacedView type,
        InquiryView.PlacedView system,
        InquiryView.PlacedView function,
        List<InquiryView.EntryView> intake,
        InquiryView.StructuredView structured,
        List<String> revisionNotes,
        AnswerDetail answer,
        boolean sentImmediately,
        boolean read,
        SlackStatus slackStatus,
        Phase phase,
        Turn turn,
        Awaiting awaiting,
        Ending ending,
        Actor closedBy,
        Declined declined,
        HandoverReason handoverReason,
        InvestigationView investigation,
        AdviceView advice,
        List<String> defaultConsultants,
        List<Consultation> consultations,
        List<PrecedentView> decisions,
        List<PrecedentView> precedents,
        InquiryView.NoReplyView noReply,
        List<MessageView> messages,
        List<HistoryEntry> history,
        List<TraceEntry> trace) {

    /**
     * AI の案（契約書「AI の案（advice）」）。
     *
     * @param status 案の状況
     * @param judgment 判断の案。なければ null（できていない場合を含む）
     * @param procedure 手順の案。なければ null（できていない場合を含む）
     */
    public record AdviceView(AdviceStatus status, AdviceContent.Judgment judgment, AdviceContent.Procedure procedure) {}

    /**
     * 調査の状況（契約書「調査の状況と、裏での調査」）。
     *
     * @param status 調査の状況
     * @param audience 結果を誰に見せるか。{@code DONE} のときだけ非 null
     * @param startedAt 直近の調査を始めた時刻。調べていなければ null
     * @param finishedAt 直近の調査が終わった（または失敗した）時刻。調べている最中と、調べていなければ null
     * @param unconfirmedQuestion 調査のもとにした問い（言い直し）を、本人が確かめていないか
     */
    public record InvestigationView(
            InvestigationStatus status,
            InvestigationAudience audience,
            Instant startedAt,
            Instant finishedAt,
            boolean unconfirmedQuestion) {}

    /**
     * 0次回答。
     *
     * @param grounded 照合できた出典に根拠があるか
     * @param answered 確かめられた事実が本人の問いに答えているか。grounded でなければ常に false
     * @param askerExplanation 本人向けの説明
     * @param steps 本人が自分で行える手順（行う順）。なければ空
     * @param memo 技術メモ
     * @param citations 出典。照合結果つき
     */
    public record AnswerDetail(
            boolean grounded,
            boolean answered,
            String askerExplanation,
            List<Explanation.Step> steps,
            TechnicalMemo memo,
            List<CitationDetail> citations) {}

    /**
     * 照合結果つきの出典。
     *
     * @param citation 出典
     * @param verified 照合できたか
     */
    public record CitationDetail(
            Citation.SourceType sourceType,
            String repository,
            String path,
            int startLine,
            int endLine,
            String quote,
            String revision,
            boolean verified) {}
}
