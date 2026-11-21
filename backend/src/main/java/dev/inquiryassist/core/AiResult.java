package dev.inquiryassist.core;

/**
 * AI処理の結果と、その実行条件・利用量の組。
 *
 * @param value 処理の結果
 * @param usage 実行条件と利用量
 * @param <T> 結果の型
 */
public record AiResult<T>(T value, Usage usage) {

    public AiResult {
        if (value == null || usage == null) {
            throw new IllegalArgumentException("value と usage は必須");
        }
    }
}
