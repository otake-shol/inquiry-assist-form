package dev.inquiryassist.core;

import java.util.List;

/**
 * 問い合わせの対象システムの候補。受付が文章から置き場所を読み取るときに渡す。
 *
 * @param id 識別子
 * @param label 画面に出す名前
 * @param functions このシステムの機能。機能の設定がなければ空
 */
public record SystemOption(String id, String label, List<FunctionOption> functions) {

    /**
     * 機能の設定がないシステムを作る。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public SystemOption(String id, String label) {
        this(id, label, List.of());
    }

    public SystemOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
        functions = functions == null ? List.of() : List.copyOf(functions);
    }
}
