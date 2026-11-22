package dev.inquiryassist.core.skill;

/**
 * 助言に渡す先例（仕様の持ち主が下した決定の記録）のうち、案を作るのに要る部分。決めた人の名前と、元の問い合わせは渡さない。
 * 書かれた内容なので、書かれた言語のまま渡す。
 *
 * @param situation どんな状況についての決定か
 * @param decisionKind 決定の種類（{@code AS_INTENDED}、{@code DEFECT} など）
 * @param decision 決定の内容
 * @param reason 理由
 */
public record PrecedentNote(String situation, String decisionKind, String decision, String reason) {}
