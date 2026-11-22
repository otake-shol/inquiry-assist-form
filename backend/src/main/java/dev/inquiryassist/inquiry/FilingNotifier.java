package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.Urgency;
import java.util.UUID;

/** 起票を担当者へ通知するポート。起票先が確定したら実装を差し替える。 */
public interface FilingNotifier {

    /**
     * 起票を通知する。例外を投げた場合、起票は完了扱いにならない。
     *
     * @param notice 通知の内容
     */
    void notifyFiled(FilingNotice notice);

    /**
     * 起票の通知内容。
     *
     * @param inquiryId 問い合わせの識別子
     * @param type 種類。決まっていなければ {@code null}
     * @param urgency 緊急度
     * @param utterance 本人が書いた文章。書いていなければ {@code null}
     * @param systemLabel システムの名前。決まっていなければ {@code null}
     * @param functionLabel 機能の名前。決まっていなければ {@code null}
     * @param structured 整形後の問い。組み立てる前に送る場合は {@code null}
     * @param memo 技術メモ。0次回答が出る前は {@code null}
     * @param sentImmediately 答えを待たずに送られたか
     */
    record FilingNotice(
            UUID inquiryId,
            InquiryType type,
            Urgency urgency,
            String utterance,
            String systemLabel,
            String functionLabel,
            StructuredInquiry structured,
            TechnicalMemo memo,
            boolean sentImmediately) {}
}
