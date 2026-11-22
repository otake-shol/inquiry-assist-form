package dev.inquiryassist.inquiry;

/**
 * スキルの呼び出し1回の実行記録（契約書の trace）。開発者向けの情報。
 *
 * @param skill スキルの識別子
 * @param provider 実装の名前。記録した時点のそのスキルの {@code provider} の値（保存した値をそのまま出す）。この項目を加える前の記録は、モックなら {@code mock}、そうでなければ {@code ollama}
 * @param model 使ったモデル
 * @param promptVersion プロンプトの版
 * @param inputTokens 入力トークン数
 * @param outputTokens 出力トークン数
 * @param durationMillis 呼び出しにかかった時間（ミリ秒）。この項目を加える前の記録では null
 * @param failed 失敗した呼び出しか
 */
public record TraceEntry(
        String skill,
        String provider,
        String model,
        String promptVersion,
        long inputTokens,
        long outputTokens,
        Long durationMillis,
        boolean failed) {}
