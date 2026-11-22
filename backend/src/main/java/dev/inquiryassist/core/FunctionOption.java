package dev.inquiryassist.core;

/**
 * システムの機能の候補。
 *
 * @param id 識別子
 * @param label 画面に出す名前
 */
public record FunctionOption(String id, String label) {

    public FunctionOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
    }
}
