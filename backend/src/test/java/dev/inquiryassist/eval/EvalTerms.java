package dev.inquiryassist.eval;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 採点で使う語の一覧と正規表現。判定の基準をここ1か所にまとめる。変えるときは評価の結果の比較に影響するため、
 * 変えたことが分かるように記録する。
 */
final class EvalTerms {

    private EvalTerms() {}

    /**
     * 場所を尋ねる質問。対象のシステムが分かっているのに「どの画面ですか」と聞かないことを確かめる
     * （{@code forbidLocationQuestion}）。
     */
    static final Pattern LOCATION_QUESTION = Pattern.compile("どの(画面|機能|メニュー|システム|ページ|サービス)");

    /**
     * エンジニアでなければ答えられない語（{@code forbidEngineerTerms}）。聞き手の本人は営業やカスタマーサポートの担当者で、
     * これらの語を使った質問には答えられない。
     *
     * <p>「ログ」は「ログイン」「ログアウト」を含めない（業務の言葉として自然に使われるため）。英字の語は大文字小文字を区別しない。
     * 「など」とあるとおり網羅ではなく、よく混ざる語の一覧。足りない語は、気づいたときに足す。
     */
    static final List<Pattern> ENGINEER_TERMS = List.of(
            Pattern.compile("ログ(?!イン|アウト)"),
            Pattern.compile("API", Pattern.CASE_INSENSITIVE),
            Pattern.compile("データベース"),
            Pattern.compile("DB", Pattern.CASE_INSENSITIVE),
            Pattern.compile("SQL", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ソースコード"),
            Pattern.compile("スタックトレース"),
            Pattern.compile("リクエスト"),
            Pattern.compile("レスポンス"),
            Pattern.compile("ステータスコード"),
            Pattern.compile("開発者ツール"));

    /**
     * 説明に出てはいけないコード上の名前。本人は業務の言葉で説明を読むため、識別子やファイル名が出ていないことを
     * すべての説明の例で確かめる。英字の camelCase、スネークケース（前後が英数字の場合は対象にしない。日本語に隣り合う識別子も拾うため、\\b は使わない）、{@code .java}、{@code ()}、{@code #}。
     * 全角の括弧「（）」は普通の日本語なので対象にしない。
     */
    static final List<Pattern> CODE_NAMES = List.of(
            Pattern.compile("(?<![A-Za-z0-9])[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*(?![A-Za-z0-9])"),
            Pattern.compile("(?<![A-Za-z0-9_])[a-z][a-z0-9]*_[a-z0-9_]+(?![A-Za-z0-9_])"),
            Pattern.compile("\\.java(?![A-Za-z0-9])"),
            Pattern.compile("\\(\\)"),
            Pattern.compile("#"));

    /** 「確認できなかった」と述べている語（{@code mustAcknowledgeUnknown}）。 */
    static final List<String> UNKNOWN_ACKNOWLEDGEMENTS = List.of("確認できません", "分かりません", "わかりません");

    /** 「です・ます」調の文末（{@code style}）。 */
    static final List<String> POLITE_ENDINGS = List.of("です", "ます", "ました", "ません", "でしょう", "ください");

    /** 「です・ます」調として許す、過去形の文末。{@code polite} のときだけ許す。 */
    static final String POLITE_PAST_ENDING = "でした";

    /** 文末の判定で、文末の後ろにあっても無視する閉じ記号。 */
    static final String TRAILING_MARKS = "」』）) \t\n";

    /** 選択肢の数の上限と下限（0個、または2〜4個）。 */
    static final int MIN_OPTIONS = 2;

    static final int MAX_OPTIONS = 4;
}
