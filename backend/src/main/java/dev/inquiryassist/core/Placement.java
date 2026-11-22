package dev.inquiryassist.core;

/**
 * 問い合わせの置き場所（種類、システム、機能）。決まっていない項目は {@code null}。
 *
 * @param type 種類
 * @param systemId システムの識別子
 * @param functionId 機能の識別子
 */
public record Placement(InquiryType type, String systemId, String functionId) {

    /** どれも決まっていない置き場所。 */
    public static final Placement NONE = new Placement(null, null, null);
}
