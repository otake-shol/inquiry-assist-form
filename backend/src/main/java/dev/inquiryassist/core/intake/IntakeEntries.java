package dev.inquiryassist.core.intake;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import java.util.List;

/** 答え終えた質問の一覧から、置き場所などを読み取る補助。置き場所は、保存せず答えから導く。 */
public final class IntakeEntries {

    private IntakeEntries() {}

    /**
     * @param entries 答え終えた質問
     * @param kind 質問の種類
     * @return その種類の質問（置き場所と補足は1つだけ）。なければ {@code null}
     */
    public static IntakeEntry find(List<IntakeEntry> entries, QuestionKind kind) {
        return entries.stream().filter(e -> e.kind() == kind).findFirst().orElse(null);
    }

    /**
     * 置き場所を返す。本人が選んだものと、受付が置いたものの両方を含む。
     *
     * @param entries 答え終えた質問
     * @return 置き場所。決まっていない項目は {@code null}
     */
    public static Placement placement(List<IntakeEntry> entries) {
        return new Placement(typeOf(entries, false), idOf(entries, QuestionKind.SYSTEM, false), idOf(entries, QuestionKind.FUNCTION, false));
    }

    /**
     * 本人が選んだ置き場所だけを返す。受付に渡す「既に決まっていること」で、受付はこれを変えない。
     *
     * @param entries 答え終えた質問
     * @return 本人が選んだ置き場所。選んでいない項目は {@code null}
     */
    public static Placement chosen(List<IntakeEntry> entries) {
        return new Placement(typeOf(entries, true), idOf(entries, QuestionKind.SYSTEM, true), idOf(entries, QuestionKind.FUNCTION, true));
    }

    /**
     * 置き場所の項目を決めたのが誰かを返す。
     *
     * @param entries 答え終えた質問
     * @param kind {@code TYPE}、{@code SYSTEM}、{@code FUNCTION} のいずれか
     * @return 決まっていなければ {@code null}
     */
    public static PlacedBy placedBy(List<IntakeEntry> entries, QuestionKind kind) {
        IntakeEntry entry = find(entries, kind);
        if (entry == null || !entry.answer().decides()) {
            return null;
        }
        return entry.answer().kind() == AnswerKind.OPTION ? PlacedBy.ASKER : PlacedBy.AI;
    }

    /**
     * 補足に書かれた文章を返す。
     *
     * @param entries 答え終えた質問
     * @return 補足の文章。書かれていなければ {@code null}
     */
    public static String noteText(List<IntakeEntry> entries) {
        IntakeEntry note = find(entries, QuestionKind.NOTE);
        return note != null && note.answer().kind() == AnswerKind.TEXT ? note.answer().text() : null;
    }

    private static InquiryType typeOf(List<IntakeEntry> entries, boolean chosenOnly) {
        String id = idOf(entries, QuestionKind.TYPE, chosenOnly);
        return id == null ? null : InquiryType.valueOf(id);
    }

    private static String idOf(List<IntakeEntry> entries, QuestionKind kind, boolean chosenOnly) {
        IntakeEntry entry = find(entries, kind);
        if (entry == null || !entry.answer().decides()) {
            return null;
        }
        if (chosenOnly && entry.answer().kind() != AnswerKind.OPTION) {
            return null;
        }
        return entry.answer().optionId();
    }
}
