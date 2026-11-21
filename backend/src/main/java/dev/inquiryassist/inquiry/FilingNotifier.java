package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
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
     * @param category 確認済みの種別
     * @param utterance 本人が書いた元の発話
     * @param systemLabel 対象システムの名前。決まっていなければ {@code null}
     * @param structured 整形後の問い。整形前に「今すぐ送る」を実行した場合は {@code null}
     * @param memo 技術メモ。0次回答が出る前は {@code null}
     * @param sentImmediately 「今すぐ担当者に送る」による起票か
     */
    record FilingNotice(
            UUID inquiryId,
            InquiryCategory category,
            String utterance,
            String systemLabel,
            StructuredInquiry structured,
            TechnicalMemo memo,
            boolean sentImmediately) {}
}
