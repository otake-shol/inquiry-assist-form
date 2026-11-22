package dev.inquiryassist.inquiry;

/** 仕様の持ち主への相談の状況（契約書「仕様の持ち主への相談」）。 */
public enum ConsultationStatus {
    /** 決定を待っている。同時に1つだけ。 */
    OPEN,
    /** 決まった。先例ができた。 */
    DECIDED,
    /** 取り下げた（担当者が取り下げた、または本人が問い合わせを取り下げた）。 */
    CANCELED
}
