package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * オペレータ向けの問い合わせの全内容。
 *
 * @param id 識別子
 * @param filedAt 担当者に送られた時刻
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
 * @param handlingStatus 対応状況
 * @param messages 担当者の返信と本人の追記（古い順）
 * @param trace スキルの実行記録。無効にしている場合は null
 */
public record OperatorDetail(
        UUID id,
        Instant filedAt,
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
        HandlingStatus handlingStatus,
        List<MessageView> messages,
        List<TraceEntry> trace) {

    /**
     * 0次回答。
     *
     * @param grounded 照合できた出典に根拠があるか
     * @param answered 確かめられた事実が本人の問いに答えているか。grounded でなければ常に false
     * @param askerExplanation 本人向けの説明
     * @param memo 技術メモ
     * @param citations 出典。照合結果つき
     */
    public record AnswerDetail(
            boolean grounded,
            boolean answered,
            String askerExplanation,
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
