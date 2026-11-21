package dev.inquiryassist.core;

/**
 * AI処理1回の実行条件と利用量。問い合わせ記録に保存する。
 *
 * @param model 使ったモデル。モックでは {@code "mock"}
 * @param promptVersion プロンプトの版
 * @param inputTokens 入力トークン数
 * @param outputTokens 出力トークン数
 * @param cacheReadTokens キャッシュから読んだ入力トークン数
 * @param mock モック実装による結果か
 */
public record Usage(
        String model,
        String promptVersion,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        boolean mock) {

    /**
     * モック実装用の利用量を返す。トークンは消費しない。
     *
     * @return モック由来の利用量
     */
    public static Usage ofMock() {
        return new Usage("mock", "mock", 0, 0, 0, true);
    }
}
