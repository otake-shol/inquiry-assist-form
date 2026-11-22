package dev.inquiryassist.inquiry;

/**
 * スキルの呼び出し1回の実行記録（契約書の trace）。開発者向けの情報。
 *
 * @param skill スキルの識別子
 * @param provider 実装の名前。その呼び出しがモックなら {@code mock}、そうでなければ設定上の実装名。保存済みの記録に実装名がないモックでない呼び出しは、手元のモデルの実装を外す前の記録なので {@code ollama}
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
