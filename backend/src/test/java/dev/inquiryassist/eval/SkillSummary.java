package dev.inquiryassist.eval;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * スキル1つ分のまとめ。
 *
 * @param skill スキルの識別子
 * @param runs 実行した回数（例の数 × 繰り返し）
 * @param passed 通った数
 * @param callFailures 呼び出しが失敗した数（落ちた数に含む）
 * @param failedByCondition 条件ごとの落ちた数
 * @param medianMillis 応答時間の中央値
 * @param maxMillis 応答時間の最大
 * @param inputTokens 入力のトークン数の合計
 * @param outputTokens 出力のトークン数の合計
 * @param caseCount 例の数（繰り返しは1件と数える）
 * @param allPassedCases すべての回で通った例の数
 * @param anyFailedCases 1回でも落ちた例の数
 */
record SkillSummary(
        String skill,
        int runs,
        int passed,
        int callFailures,
        Map<String, Integer> failedByCondition,
        long medianMillis,
        long maxMillis,
        long inputTokens,
        long outputTokens,
        int caseCount,
        int allPassedCases,
        int anyFailedCases) {

    /** 通った割合（延べ。繰り返した回数も1回ずつ数える）。 */
    double passRate() {
        return runs == 0 ? 0 : (double) passed / runs;
    }

    String percent() {
        return String.format("%.0f%%", passRate() * 100);
    }

    static SkillSummary of(String skill, List<CaseResult> results) {
        Map<String, Integer> byCondition = new TreeMap<>();
        for (CaseResult r : results) {
            // 同じ条件が1回の実行で複数回落ちても、その実行では1回と数える
            r.failures().stream().map(Failure::condition).distinct().forEach(c -> byCondition.merge(c, 1, Integer::sum));
        }
        // 例ごとに、通った回数と実行した回数を数える（繰り返しがなければ1回ずつ）
        Map<String, int[]> perCase = perCase(results);
        int allPassed = (int) perCase.values().stream().filter(c -> c[0] == c[1]).count();
        List<Long> millis = results.stream().map(CaseResult::millis).sorted().toList();
        long median = millis.isEmpty() ? 0 : millis.get(millis.size() / 2);
        return new SkillSummary(
                skill,
                results.size(),
                (int) results.stream().filter(CaseResult::passed).count(),
                (int) results.stream().filter(CaseResult::callFailed).count(),
                byCondition,
                median,
                millis.isEmpty() ? 0 : millis.get(millis.size() - 1),
                results.stream().mapToLong(CaseResult::inputTokens).sum(),
                results.stream().mapToLong(CaseResult::outputTokens).sum(),
                perCase.size(),
                allPassed,
                perCase.size() - allPassed);
    }

    /**
     * 例ごとの {通った回数, 実行した回数}。
     *
     * @param results 結果
     * @return 例の識別子から回数への対応（出現順）
     */
    static Map<String, int[]> perCase(List<CaseResult> results) {
        Map<String, int[]> perCase = new java.util.LinkedHashMap<>();
        for (CaseResult r : results) {
            int[] counts = perCase.computeIfAbsent(r.caseId(), k -> new int[2]);
            counts[0] += r.passed() ? 1 : 0;
            counts[1]++;
        }
        return perCase;
    }
}
