package dev.inquiryassist.core;

import java.util.List;

/**
 * 問い合わせの対象システムの候補。受付が文章から置き場所を読み取るときに渡す。
 *
 * @param id 識別子
 * @param label 画面に出す名前（本人の言語）
 * @param aliases 文章から読み取るために使う、ほかの言語の名前も含めたすべての名前
 * @param functions このシステムの機能。機能の設定がなければ空
 */
public record SystemOption(String id, String label, List<String> aliases, List<FunctionOption> functions) {

    /**
     * 機能の設定がなく、名前が1つだけのシステムを作る。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public SystemOption(String id, String label) {
        this(id, label, List.of(label), List.of());
    }

    /**
     * 名前が1つだけのシステムを作る。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     * @param functions このシステムの機能
     */
    public SystemOption(String id, String label, List<FunctionOption> functions) {
        this(id, label, List.of(label), functions);
    }

    public SystemOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
        aliases = aliases == null || aliases.isEmpty() ? List.of(label) : List.copyOf(aliases);
        functions = functions == null ? List.of() : List.copyOf(functions);
    }
}
