package dev.inquiryassist.core.intake;

import java.util.List;
import java.util.Locale;

/**
 * API が組み込みで持つ、入口の決まった質問の文言と、要約がないときの文言。言語ごとに、ここ1か所に集める
 * （契約書「見る人の言語」の表）。設定の名前と違い、設定では差し替えない。
 *
 * @param type 種類の質問
 * @param system システムの質問
 * @param function 機能の質問
 * @param note 補足の質問（書かずに進める）
 * @param noteRequired 補足の質問（書かずには進めない）
 * @param noContent 一覧の要約にできる内容がないときの文言
 * @param nameSeparator 置き場所の名前をつないで要約にするときの区切り
 */
public record IntakeTexts(
        String type,
        String system,
        String function,
        String note,
        String noteRequired,
        String noContent,
        String nameSeparator) {

    /** 日本語。 */
    public static final IntakeTexts JA = new IntakeTexts(
            "何をしたいですか",
            "どのシステムですか",
            "どの機能ですか",
            "補足があれば書いてください",
            "状況を一言で教えてください",
            "（内容なし）",
            "・");

    /** 英語。 */
    public static final IntakeTexts EN = new IntakeTexts(
            "What can we help you with?",
            "Which system is it about?",
            "Which function is it about?",
            "Add anything else you would like to tell us",
            "Tell us briefly what is happening",
            "(No content)",
            " / ");

    private static final List<IntakeTexts> ALL = List.of(JA, EN);

    /**
     * 言語の文言を返す。組み込みの文言がない言語は、日本語。
     *
     * @param language 言語（{@code ja}、{@code en} など）
     * @return その言語の文言
     */
    public static IntakeTexts of(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en") ? EN : JA;
    }

    /**
     * 保存した補足の質問文が、書かずには進めない質問のものかを返す。質問文は、聞いた時点の言語で保存してあるため、
     * どの言語のものとも比べる。
     *
     * @param storedText 保存した質問文
     * @return 書かずには進めない質問の文言なら true
     */
    public static boolean isNoteRequired(String storedText) {
        return ALL.stream().anyMatch(t -> t.noteRequired().equals(storedText));
    }
}
