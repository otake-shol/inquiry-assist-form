package dev.inquiryassist.mock;

import dev.inquiryassist.core.InquiryType;
import java.util.List;
import java.util.Locale;

/**
 * モックのシナリオを選ぶ、発話のキーワードとタグの解釈。契約書「モックのシナリオ」に対応する。
 *
 * <p>種類はキーワードで決まり、振る舞いは {@code [質問なし]} などの角括弧のタグで切り替える。
 */
final class MockScenario {

    /** 追加質問を出さない（聞き手）。 */
    static final String TAG_NO_QUESTIONS = "[質問なし]";
    /** 聞き手が失敗する。 */
    static final String TAG_QUESTION_ERROR = "[質問エラー]";
    /** 事実を1件も返さない（調査）。 */
    static final String TAG_NO_GROUNDS = "[根拠なし]";
    /** すべての事実の根拠が実在しない（調査）。 */
    static final String TAG_GHOST_CITATION = "[幽霊出典]";
    /** 事実2件のうち1件の根拠が実在しない（調査）。 */
    static final String TAG_PARTIAL_GHOST = "[一部幽霊]";
    /** 進捗の各段階で待つ（調査）。 */
    static final String TAG_SLOW = "[遅延]";
    /** 調査が AI_UNAVAILABLE になる。 */
    static final String TAG_ERROR = "[エラー]";
    /** 調査が AI_REFUSED になる。 */
    static final String TAG_REFUSED = "[拒否]";
    /** 説明が AI_UNAVAILABLE になる。 */
    static final String TAG_EXPLAIN_ERROR = "[説明エラー]";
    /** 説明が「確かめられた事実は問いに答えていない」と判定する。 */
    static final String TAG_OFF_TOPIC = "[的外れ]";
    /** 受付が AI_UNAVAILABLE になる。 */
    static final String TAG_RECEPTION_ERROR = "[判定エラー]";
    /** 言い直し（問いの組み立て）が AI_UNAVAILABLE になる。 */
    static final String TAG_RESTATE_ERROR = "[言い直しエラー]";

    /** 助言の判断の案で、仕様の持ち主への相談が要るとする。 */
    static final String TAG_CONSULT = "[相談]";
    /** 助言が、種類にかかわらず手順の案を返す。 */
    static final String TAG_PROCEDURE = "[手順]";
    /** 助言の判断の案が、存在しない事実の番号だけに基づく（コードが案を捨てる）。 */
    static final String TAG_UNGROUNDED_ADVICE = "[根拠なしの案]";
    /** 助言が AI_UNAVAILABLE になる。 */
    static final String TAG_ADVICE_ERROR = "[案エラー]";
    /** 下書きが AI_UNAVAILABLE になる。 */
    static final String TAG_DRAFT_ERROR = "[下書きエラー]";

    private static final List<String> ALL_TAGS = List.of(
            TAG_CONSULT,
            TAG_PROCEDURE,
            TAG_UNGROUNDED_ADVICE,
            TAG_ADVICE_ERROR,
            TAG_DRAFT_ERROR,
            TAG_NO_QUESTIONS,
            TAG_QUESTION_ERROR,
            TAG_NO_GROUNDS,
            TAG_GHOST_CITATION,
            TAG_PARTIAL_GHOST,
            TAG_SLOW,
            TAG_ERROR,
            TAG_REFUSED,
            TAG_EXPLAIN_ERROR,
            TAG_OFF_TOPIC,
            TAG_RECEPTION_ERROR,
            TAG_RESTATE_ERROR);
    // 英語の語は、大文字と小文字を区別せずに探す。優先順位は日本語の語と同じ
    private static final List<String> CHANGE_REQUEST_WORDS =
            List.of("変えてほしい", "要望", "改善", "feature request", "improve");
    private static final List<String> DO_FOR_ME_WORDS =
            List.of("依頼", "してほしい", "お願いします", "してください", "please", "could you");
    private static final List<String> LOOKS_WRONG_WORDS = List.of("不具合", "バグ", "おかしい", "bug", "wrong", "broken");
    private static final List<String> HOW_TO_WORDS = List.of("使い方", "やり方", "手順", "どうやって", "how to", "how do i");

    private MockScenario() {}

    /**
     * 文章のキーワードから種類を決める。優先順位は、変えてほしい、やってほしい、おかしい、使い方、それ以外の順。
     *
     * @param text 本人が書いた文章
     * @return 種類
     */
    static InquiryType classify(String text) {
        if (containsAny(text, CHANGE_REQUEST_WORDS)) {
            return InquiryType.CHANGE_REQUEST;
        }
        if (containsAny(text, DO_FOR_ME_WORDS)) {
            return InquiryType.DO_FOR_ME;
        }
        if (containsAny(text, LOOKS_WRONG_WORDS)) {
            return InquiryType.LOOKS_WRONG;
        }
        if (containsAny(text, HOW_TO_WORDS)) {
            return InquiryType.HOW_TO;
        }
        return InquiryType.HOW_IT_WORKS;
    }

    /**
     * @param text 調べる文章。書かれていなければ {@code null}
     * @param tag タグ
     * @return 文章にそのタグが含まれる場合は true
     */
    static boolean has(String text, String tag) {
        return text != null && text.contains(tag);
    }

    /**
     * 既知のタグをすべて取り除く。
     *
     * @param text 文章
     * @return タグを除き、前後の空白を取った文章
     */
    static String stripTags(String text) {
        String result = text;
        for (String tag : ALL_TAGS) {
            result = result.replace(tag, "");
        }
        return result.strip();
    }

    private static boolean containsAny(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(w -> lower.contains(w.toLowerCase(Locale.ROOT)));
    }

    /**
     * 名前が文章に含まれているかを、大文字と小文字を区別せずに調べる。どの言語の名前でも照合する。
     *
     * @param text 文章
     * @param names 名前（どの言語のものも）
     * @return どれかが含まれていれば true
     */
    static boolean mentionsAny(String text, List<String> names) {
        return text != null && containsAny(text, names);
    }

    /**
     * @param language 本人の言語
     * @return 英語なら true。モックは、英語のときだけ英語で返し、それ以外は日本語で返す
     */
    static boolean english(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
