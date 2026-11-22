package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.skill.Skill;
import java.util.List;

/** 保存した利用量から、スキルの実行記録（trace）を作る。本人向けとオペレータ向けで同じ内容を返すため、1か所に置く。 */
final class TraceViews {

    private TraceViews() {}

    /**
     * 実行記録を作る。無効にしている場合は null を返す。
     *
     * @param entity 問い合わせ記録
     * @param properties 実行記録の有効・無効の設定
     * @param implementations スキルごとの実装
     * @param forOperator 担当者向けなら true。本人向け（false）は、助言、下書き、訳の呼び出しを含めない
     * @return 呼び出しを実行した順の記録。無効なら null
     */
    static List<TraceEntry> of(
            InquiryEntity entity, InquiryProperties properties, SkillImplementations implementations, boolean forOperator) {
        if (!properties.trace().enabled()) {
            return null;
        }
        return entity.usages.stream()
                // 助言、下書き、訳は、担当者だけに見せる（担当者だけに見せる案があることを、本人のページに出さない）
                .filter(u -> forOperator || !isOperatorSkill(u.step()))
                .map(u -> new TraceEntry(
                        u.step(),
                        providerOf(u, implementations),
                        u.model(),
                        u.promptVersion(),
                        u.inputTokens(),
                        u.outputTokens(),
                        u.durationMillis(),
                        u.failed()))
                .toList();
    }

    private static boolean isOperatorSkill(String step) {
        return Skill.ADVISOR.id().equals(step) || Skill.DRAFTER.id().equals(step) || Skill.TRANSLATOR.id().equals(step);
    }

    /** その呼び出しがモックなら mock、そうでなければ設定上の実装名。 */
    private static String providerOf(InquiryEntity.StoredUsage usage, SkillImplementations implementations) {
        if (usage.mock()) {
            return SkillImplementations.MOCK;
        }
        for (Skill skill : Skill.values()) {
            if (skill.id().equals(usage.step())) {
                return implementations.providerOf(skill);
            }
        }
        return "unknown";
    }
}
