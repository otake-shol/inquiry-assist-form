package dev.inquiryassist.core;

import java.util.List;

/**
 * システムの機能の候補。
 *
 * @param id 識別子
 * @param label 画面に出す名前（本人の言語）
 * @param aliases 文章から読み取るために使う、ほかの言語の名前も含めたすべての名前
 */
public record FunctionOption(String id, String label, List<String> aliases) {

    /**
     * 名前が1つだけの機能を作る。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public FunctionOption(String id, String label) {
        this(id, label, List.of(label));
    }

    public FunctionOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
        aliases = aliases == null || aliases.isEmpty() ? List.of(label) : List.copyOf(aliases);
    }
}
