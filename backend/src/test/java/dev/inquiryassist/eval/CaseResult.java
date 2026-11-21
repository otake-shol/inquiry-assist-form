package dev.inquiryassist.eval;

import java.util.List;
import java.util.Map;

/**
 * 例1回分の実行結果。
 *
 * @param skill スキルの識別子
 * @param caseId 例の識別子
 * @param split 区分
 * @param run 繰り返しの何回目か（1から）
 * @param callFailed スキルの呼び出しが失敗したか（{@code AiUnavailableException} など）
 * @param failures 落ちた条件（呼び出しが失敗した場合は {@code callFailed}）
 * @param input 入力の要約
 * @param output 出力（呼び出しが失敗した場合は空）
 * @param millis 応答時間（ミリ秒）
 * @param inputTokens 入力のトークン数
 * @param outputTokens 出力のトークン数
 */
record CaseResult(
        String skill,
        String caseId,
        String split,
        int run,
        boolean callFailed,
        List<Failure> failures,
        String input,
        Map<String, Object> output,
        long millis,
        long inputTokens,
        long outputTokens) {

    boolean passed() {
        return failures.isEmpty();
    }

    boolean isHoldout() {
        return EvalCase.HOLDOUT.equals(split);
    }

    /** 例を一意に表す鍵（結果の比較に使う）。 */
    String key() {
        return skill + "/" + caseId + "#" + run;
    }
}
