package dev.inquiryassist.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inquiryassist.core.InquiryType;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 採点の各条件について、通る場合と落ちる場合を確かめる。 */
class ScorerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode expect(String json) {
        return JSON.readTree(json);
    }

    private static List<String> names(List<Failure> failures) {
        return failures.stream().map(Failure::condition).toList();
    }

    private static Scorer.Question q(String text, String... options) {
        return new Scorer.Question(text, List.of(options));
    }

    // ---- 文章の条件 ----

    @Test
    void mustIncludeはすべての語を含む場合だけ通る() {
        var e = expect("{\"mustInclude\":[\"A社\",\"10月\"]}");

        assertThat(Scorer.scoreRestater(e, "A社の10月分である。")).isEmpty();
        assertThat(names(Scorer.scoreRestater(e, "A社の請求書である。"))).containsExactly("mustInclude");
    }

    @Test
    void mustIncludeAnyは語の組ごとにどれか1つを含めば通る() {
        var e = expect("{\"mustIncludeAny\":[[\"今日\",\"今朝\"],[\"1件\",\"一件\"]]}");

        assertThat(Scorer.scoreRestater(e, "今朝から一件だけ消えた。")).isEmpty();
        // 2組目だけ満たさない
        assertThat(Scorer.scoreRestater(e, "今日から消えた。")).hasSize(1);
        assertThat(Scorer.scoreRestater(e, "昨夜から消えた。")).hasSize(2);
    }

    @Test
    void mustNotIncludeはどの語も含まない場合だけ通る() {
        var e = expect("{\"mustNotInclude\":[\"修正\",\"復旧\"]}");

        assertThat(Scorer.scoreRestater(e, "一覧に出ない。")).isEmpty();
        assertThat(names(Scorer.scoreRestater(e, "復旧してほしい。"))).containsExactly("mustNotInclude");
    }

    @Test
    void mustNotMatchは一昨日を通し昨日を落とす() {
        var e = expect("{\"mustNotMatch\":[\"(?<!一)昨日\"]}");

        assertThat(Scorer.scoreRestater(e, "消えたのは一昨日からである。")).isEmpty();
        assertThat(names(Scorer.scoreRestater(e, "消えたのは昨日からである。"))).containsExactly("mustNotMatch");
        // 「一昨日」と「昨日」が両方あれば落ちる
        assertThat(names(Scorer.scoreRestater(e, "一昨日ではなく昨日である。"))).containsExactly("mustNotMatch");
    }

    @Test
    void 文の数は句点で数え空の部分や末尾の句点なしも1文として扱う() {
        var e = expect("{\"minSentences\":2,\"maxSentences\":3}");

        assertThat(Scorer.scoreRestater(e, "一つ目である。二つ目である。")).isEmpty();
        assertThat(Scorer.scoreRestater(e, "一つ目である。二つ目である。三つ目である")).isEmpty();
        assertThat(names(Scorer.scoreRestater(e, "一つだけである。"))).containsExactly("minSentences");
        assertThat(names(Scorer.scoreRestater(e, "一。二。三。四。"))).containsExactly("maxSentences");
        assertThat(Scorer.sentences("あ。。い。")).containsExactly("あ", "い");
    }

    @Test
    void 言い直しのmustNotIncludeとmustNotMatchはgoalなどの項目にも適用され落ちた理由に項目名が出る() {
        var e = expect("{\"mustNotInclude\":[\"復旧\"],\"mustNotMatch\":[\"(?<!一)昨日\"]}");

        // summary はきれいで、goal にだけ本人が言っていないことが足されている
        var goalOnly = Scorer.scoreRestater(e, "一覧に出ない。", "復旧方法を知りたい", null, null);
        assertThat(names(goalOnly)).containsExactly("mustNotInclude");
        assertThat(goalOnly.get(0).detail()).contains("[goal]").contains("復旧");

        var whatHappened = Scorer.scoreRestater(e, "一覧に出ない。", "理由を知りたい", "昨日から出ない", null);
        assertThat(names(whatHappened)).containsExactly("mustNotMatch");
        assertThat(whatHappened.get(0).detail()).contains("[whatHappened]");

        var expected = Scorer.scoreRestater(e, "一覧に出ない。", null, null, "復旧してほしい");
        assertThat(expected.get(0).detail()).contains("[expectedResult]");

        // summary で当たった場合は summary と分かる
        assertThat(Scorer.scoreRestater(e, "復旧したい。", "理由", null, null).get(0).detail()).contains("[summary]");
        // 全項目がきれいなら通る。null の項目は見ない
        assertThat(Scorer.scoreRestater(e, "一覧に出ない。", "理由を知りたい", null, null)).isEmpty();
    }

    @Test
    void summaryだけを見る条件はgoalに語があっても通らない() {
        var mustInclude = expect("{\"mustInclude\":[\"A社\"]}");
        var style = expect("{\"style\":\"plain\"}");
        var sentences = expect("{\"maxSentences\":1}");

        // 「A社」は goal にしかない。mustInclude は summary だけを見るので落ちる
        assertThat(names(Scorer.scoreRestater(mustInclude, "請求書の金額が違う。", "A社の請求書を知りたい", null, null)))
                .containsExactly("mustInclude");
        // goal の文末や文の数は見ない
        assertThat(Scorer.scoreRestater(style, "一覧に出ない。", "知りたいです。見たいです。", null, null)).isEmpty();
        assertThat(Scorer.scoreRestater(sentences, "一覧に出ない。", "一。二。三。", null, null)).isEmpty();
    }

    @Test
    void styleのplainは文末がです_ます系でないことを確かめる() {
        var e = expect("{\"style\":\"plain\"}");

        assertThat(Scorer.scoreRestater(e, "一覧に出ない。知りたい。")).isEmpty();
        for (String polite : List.of("出ません。", "出ます。", "出ました。", "出ないです。", "出ないでしょう。", "確認してください。")) {
            assertThat(names(Scorer.scoreRestater(e, "一覧に出ない。" + polite))).as(polite).containsExactly("style");
        }
        // 閉じ括弧の前の文末も判定する
        assertThat(names(Scorer.scoreRestater(e, "出ます）。"))).containsExactly("style");
    }

    @Test
    void styleのpoliteはすべての文末がです_ます系かでしただと通る() {
        var e = expect("{\"style\":\"polite\"}");

        assertThat(Scorer.scoreRestater(e, "出ません。昨日は出ていました。原因は不明でした。確認してください。")).isEmpty();
        assertThat(names(Scorer.scoreRestater(e, "出ません。昨日は出ていた。"))).containsExactly("style");
    }

    // ---- 受付 ----

    @Test
    void 受付のtypeとtypeAnyOf() {
        assertThat(Scorer.scoreReception(expect("{\"type\":\"HOW_IT_WORKS\"}"), InquiryType.HOW_IT_WORKS, null)).isEmpty();
        assertThat(names(Scorer.scoreReception(expect("{\"type\":\"HOW_IT_WORKS\"}"), InquiryType.LOOKS_WRONG, null)))
                .containsExactly("type");
        // 種類を読み取れなかった（null）出力は、期待と合わない
        assertThat(names(Scorer.scoreReception(expect("{\"type\":\"HOW_IT_WORKS\"}"), null, null)))
                .containsExactly("type");
        var any = expect("{\"typeAnyOf\":[\"HOW_IT_WORKS\",\"LOOKS_WRONG\"]}");
        assertThat(Scorer.scoreReception(any, InquiryType.LOOKS_WRONG, null)).isEmpty();
        assertThat(names(Scorer.scoreReception(any, InquiryType.DO_FOR_ME, null))).containsExactly("typeAnyOf");
    }

    @Test
    void 受付のsystemIdのnullは推測しないが正解であることを表す() {
        var none = expect("{\"systemId\":null}");
        var billing = expect("{\"systemId\":\"billing\"}");

        assertThat(Scorer.scoreReception(none, InquiryType.HOW_IT_WORKS, null)).isEmpty();
        assertThat(names(Scorer.scoreReception(none, InquiryType.HOW_IT_WORKS, "billing"))).containsExactly("systemId");
        assertThat(Scorer.scoreReception(billing, InquiryType.HOW_IT_WORKS, "billing")).isEmpty();
        assertThat(names(Scorer.scoreReception(billing, InquiryType.HOW_IT_WORKS, null))).containsExactly("systemId");
    }

    @Test
    void 受付のsystemIdAnyOfはnullを含められ含めば推測しないも正解() {
        var withNull = expect("{\"systemIdAnyOf\":[\"job-admin\",null]}");
        var withoutNull = expect("{\"systemIdAnyOf\":[\"job-admin\",\"billing\"]}");

        assertThat(Scorer.scoreReception(withNull, InquiryType.HOW_IT_WORKS, "job-admin")).isEmpty();
        assertThat(Scorer.scoreReception(withNull, InquiryType.HOW_IT_WORKS, null)).isEmpty();
        assertThat(names(Scorer.scoreReception(withNull, InquiryType.HOW_IT_WORKS, "billing"))).containsExactly("systemIdAnyOf");
        assertThat(names(Scorer.scoreReception(withoutNull, InquiryType.HOW_IT_WORKS, null))).containsExactly("systemIdAnyOf");
    }

    // ---- 聞き手 ----

    @Test
    void 聞き手の質問数() {
        var e = expect("{\"minQuestions\":1,\"maxQuestions\":2}");

        assertThat(Scorer.scoreInterviewer(e, List.of(q("いつからですか")))).isEmpty();
        assertThat(names(Scorer.scoreInterviewer(e, List.of()))).containsExactly("minQuestions");
        assertThat(names(Scorer.scoreInterviewer(e, List.of(q("a"), q("b"), q("c"))))).containsExactly("maxQuestions");
    }

    @Test
    void 聞き手の選択肢の数は0個か2から4個でありすべての例で確かめる() {
        var e = expect("{\"minQuestions\":0,\"maxQuestions\":5}"); // 選択肢の条件は書かなくても確かめる

        assertThat(Scorer.scoreInterviewer(e, List.of(q("自由記述"), q("二択", "はい", "いいえ"), q("四択", "a", "b", "c", "d")))).isEmpty();
        assertThat(names(Scorer.scoreInterviewer(e, List.of(q("一択", "はい"))))).containsExactly("optionCount");
        assertThat(names(Scorer.scoreInterviewer(e, List.of(q("五択", "a", "b", "c", "d", "e"))))).containsExactly("optionCount");
    }

    @Test
    void forbidLocationQuestionは場所を尋ねる質問を落とす() {
        var e = expect("{\"forbidLocationQuestion\":true}");

        assertThat(Scorer.scoreInterviewer(e, List.of(q("いつから表示されなくなりましたか")))).isEmpty();
        for (String place : List.of("画面", "機能", "メニュー", "システム", "ページ", "サービス")) {
            assertThat(names(Scorer.scoreInterviewer(e, List.of(q("どの" + place + "の話ですか")))))
                    .as(place)
                    .containsExactly("forbidLocationQuestion");
        }
        // 条件が書かれていなければ確かめない
        assertThat(Scorer.scoreInterviewer(expect("{\"minQuestions\":0}"), List.of(q("どの画面の話ですか")))).isEmpty();
    }

    @Test
    void forbidEngineerTermsはエンジニア向けの語を落としログインは落とさない() {
        var e = expect("{\"forbidEngineerTerms\":true}");

        assertThat(Scorer.scoreInterviewer(e, List.of(q("ログインできない人は何人ですか")))).isEmpty();
        for (String term : List.of("ログ", "API", "データベース", "DB", "SQL", "ソースコード", "スタックトレース", "リクエスト", "レスポンス", "ステータスコード", "開発者ツール")) {
            assertThat(names(Scorer.scoreInterviewer(e, List.of(q(term + "を確認できますか")))))
                    .as(term)
                    .containsExactly("forbidEngineerTerms");
        }
        // 選択肢に入っていても落ちる
        assertThat(names(Scorer.scoreInterviewer(e, List.of(q("どちらですか", "エラーログがある", "ない"))))).containsExactly("forbidEngineerTerms");
    }

    @Test
    void 聞き手のmustNotMatchは質問文に当てはまると落ちる() {
        var e = expect("{\"mustNotMatch\":[\"いつから\"]}");

        assertThat(Scorer.scoreInterviewer(e, List.of(q("何件ですか")))).isEmpty();
        assertThat(names(Scorer.scoreInterviewer(e, List.of(q("いつからですか"))))).containsExactly("mustNotMatch");
    }

    // ---- 説明 ----

    @Test
    void 説明のanswersQuestion() {
        var e = expect("{\"answersQuestion\":false}");

        assertThat(Scorer.scoreExplainer(e, "確認できません。", false)).isEmpty();
        assertThat(names(Scorer.scoreExplainer(e, "確認できません。", true))).containsExactly("answersQuestion");
    }

    @Test
    void 説明のmustAcknowledgeUnknownは確認できません_分かりません_わかりませんのどれかを含めば通る() {
        var e = expect("{\"mustAcknowledgeUnknown\":true}");

        for (String ok : List.of("日数は確認できません。", "日数は分かりません。", "日数はわかりません。", "確認できませんでした。")) {
            assertThat(Scorer.scoreExplainer(e, ok, false)).as(ok).isEmpty();
        }
        assertThat(names(Scorer.scoreExplainer(e, "延長はできます。", false))).containsExactly("mustAcknowledgeUnknown");
    }

    @Test
    void 説明にコード上の名前が出ていると全部の例で落ちる() {
        var e = expect("{\"minSentences\":1}");

        assertThat(Scorer.scoreExplainer(e, "掲載期限を過ぎると、一覧に表示されません。", true)).isEmpty();
        for (String code : List.of("expiresAt で判定します。", "JobPostingService.java を見ます。", "isExpired() を呼びます。", "JobPosting#extend です。", "job_posting を見ます。")) {
            assertThat(names(Scorer.scoreExplainer(e, code, true))).as(code).containsExactly("noCodeNames");
        }
        // 日本語に隣り合う識別子も拾う。全角の括弧は対象にしない
        assertThat(names(Scorer.scoreExplainer(e, "期限はexpiresAtです。", true))).containsExactly("noCodeNames");
        assertThat(Scorer.scoreExplainer(e, "期限（掲載期限）を過ぎると表示されません。", true)).isEmpty();
    }

    @Test
    void 落ちた理由には条件の名前と詳細が入る() {
        var failures = Scorer.scoreRestater(expect("{\"mustInclude\":[\"A社\"]}"), "B社である。");

        assertThat(failures).singleElement().satisfies(f -> {
            assertThat(f.condition()).isEqualTo("mustInclude");
            assertThat(f.detail()).contains("A社");
        });
    }
}
