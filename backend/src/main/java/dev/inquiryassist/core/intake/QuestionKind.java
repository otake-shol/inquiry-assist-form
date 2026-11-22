package dev.inquiryassist.core.intake;

/** 入口の質問の種類。聞く順に並べている。 */
public enum QuestionKind {
    /** 種類を尋ねる。 */
    TYPE,
    /** システムを尋ねる。 */
    SYSTEM,
    /** 機能を尋ねる。 */
    FUNCTION,
    /** 詳しい質問。マスの設定か聞き手が決める。 */
    DETAIL,
    /** 補足を尋ねる。 */
    NOTE;

    /**
     * 置き場所（種類、システム、機能）の質問かを返す。
     *
     * @return 置き場所の質問なら true
     */
    public boolean isPlacement() {
        return this == TYPE || this == SYSTEM || this == FUNCTION;
    }
}
