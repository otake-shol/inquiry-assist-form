package dev.inquiryassist.mock;

import dev.inquiryassist.core.InquiryCategory;
import java.util.List;

/**
 * モックのシナリオを選ぶ、発話のキーワードとタグの解釈。契約書「モックのシナリオ」に対応する。
 *
 * <p>種別はキーワードで決まり、振る舞いは {@code [質問なし]} などの角括弧のタグで切り替える。
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
    /** 受付（種別の判定）が AI_UNAVAILABLE になる。 */
    static final String TAG_TRIAGE_ERROR = "[判定エラー]";

    private static final List<String> ALL_TAGS = List.of(
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
            TAG_TRIAGE_ERROR);
    private static final List<String> INCIDENT_WORDS = List.of("障害", "止まって", "落ちて", "つながらない");
    private static final List<String> WORK_REQUEST_WORDS = List.of("依頼", "してほしい", "お願いします", "してください");
    private static final List<String> BUG_WORDS = List.of("不具合", "バグ", "おかしい");

    private MockScenario() {}

    /**
     * 発話のキーワードから種別を決める。優先順位は障害、作業依頼、不具合の疑い、仕様確認。
     *
     * @param utterance 発話
     * @return 種別
     */
    static InquiryCategory classify(String utterance) {
        if (containsAny(utterance, INCIDENT_WORDS)) {
            return InquiryCategory.INCIDENT;
        }
        if (containsAny(utterance, WORK_REQUEST_WORDS)) {
            return InquiryCategory.WORK_REQUEST;
        }
        if (containsAny(utterance, BUG_WORDS)) {
            return InquiryCategory.SUSPECTED_BUG;
        }
        return InquiryCategory.SPEC_QUESTION;
    }

    /**
     * @param text 調べる文章
     * @param tag タグ
     * @return 文章にそのタグが含まれる場合は true
     */
    static boolean has(String text, String tag) {
        return text.contains(tag);
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
        return words.stream().anyMatch(text::contains);
    }
}
