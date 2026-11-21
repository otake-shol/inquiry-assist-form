package dev.inquiryassist.core;

/**
 * 問い合わせの対象システムの候補。受付が発話から対象を推測するときに渡す。
 *
 * @param id 識別子
 * @param label 画面に出す名前
 */
public record SystemOption(String id, String label) {

    public SystemOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
    }
}
