package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 入口の質問（契約書「入口の質問」）を、API から1問ずつ確かめる。聞く順、マスの設定、「わからない」、
 * 文章で書く道、戻る、緊急度、入力の誤りを扱う。次の質問を決める規則そのものは {@code IntakePlannerTest}。
 */
class IntakeApiTest extends ApiSupport {

    @BeforeEach
    void clearNotices() {
        notifier.notices().clear();
    }

    private static String next(String view, String field) {
        return JsonPath.read(view, "$.intake.next." + field);
    }

    private static List<String> entryIds(String view) {
        return JsonPath.read(view, "$.intake.entries[*].questionId");
    }

    // ---- 押して答える道 ----

    @Test
    void 押して答えるだけで_種類_システム_機能_詳しい質問_補足の順にSTRUCTUREDまで進み_受付は呼ばれない() throws Exception {
        String created = start();
        String id = idOf(created);

        assertThat((String) JsonPath.read(created, "$.step")).isEqualTo("INTAKE");
        assertThat((Object) JsonPath.read(created, "$.type")).isNull();
        assertThat((Object) JsonPath.read(created, "$.utterance")).isNull();
        assertThat((Boolean) JsonPath.read(created, "$.intake.complete")).isFalse();
        assertThat(entryIds(created)).isEmpty();
        assertThat(next(created, "questionId")).isEqualTo("type");
        assertThat(next(created, "kind")).isEqualTo("TYPE");
        assertThat(next(created, "text")).isEqualTo("何をしたいですか");
        assertThat((List<String>) JsonPath.read(created, "$.intake.next.options[*].id"))
                .containsExactly("HOW_TO", "HOW_IT_WORKS", "LOOKS_WRONG", "DO_FOR_ME", "CHANGE_REQUEST");
        assertThat((Boolean) JsonPath.read(created, "$.intake.next.allowsText")).isFalse();
        assertThat((Boolean) JsonPath.read(created, "$.intake.next.canSkip")).isFalse();

        pick(id, "type", "LOOKS_WRONG")
                .andExpect(jsonPath("$.type.id").value("LOOKS_WRONG"))
                .andExpect(jsonPath("$.type.label").value("おかしいかもしれない"))
                .andExpect(jsonPath("$.type.by").value("ASKER"))
                .andExpect(jsonPath("$.intake.next.questionId").value("system"))
                .andExpect(jsonPath("$.intake.next.kind").value("SYSTEM"))
                .andExpect(jsonPath("$.intake.next.text").value("どのシステムですか"))
                .andExpect(jsonPath("$.intake.next.options[*].id").value(contains("job-admin", "billing", "reports")))
                .andExpect(jsonPath("$.intake.next.options[0].label").value("求人管理画面"));
        pick(id, "system", "job-admin")
                .andExpect(jsonPath("$.system.id").value("job-admin"))
                .andExpect(jsonPath("$.system.by").value("ASKER"))
                .andExpect(jsonPath("$.intake.next.questionId").value("function"))
                .andExpect(jsonPath("$.intake.next.text").value("どの機能ですか"))
                .andExpect(jsonPath("$.intake.next.options[*].id").value(contains("posting", "applicants")));
        pick(id, "function", "posting")
                .andExpect(jsonPath("$.function.id").value("posting"))
                .andExpect(jsonPath("$.function.label").value("求人の掲載"))
                .andExpect(jsonPath("$.function.by").value("ASKER"))
                // 設定にこの置き場所の詳しい質問がある
                .andExpect(jsonPath("$.intake.next.questionId").value("since"))
                .andExpect(jsonPath("$.intake.next.kind").value("DETAIL"))
                .andExpect(jsonPath("$.intake.next.text").value("いつから起きていますか"))
                .andExpect(jsonPath("$.intake.next.options[*].id").value(contains("today", "this-week", "earlier")))
                .andExpect(jsonPath("$.intake.next.allowsText").value(false));
        pick(id, "since", "this-week")
                .andExpect(jsonPath("$.intake.next.questionId").value("note"))
                .andExpect(jsonPath("$.intake.next.kind").value("NOTE"))
                .andExpect(jsonPath("$.intake.next.text").value("補足があれば書いてください"))
                .andExpect(jsonPath("$.intake.next.options").isEmpty())
                .andExpect(jsonPath("$.intake.next.allowsText").value(true))
                .andExpect(jsonPath("$.intake.next.canSkip").value(true));

        String last = text(skip(id, "note").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(last, "$.step")).isEqualTo("STRUCTURED");
        assertThat((Object) JsonPath.read(last, "$.intake.next")).isNull();
        assertThat((Boolean) JsonPath.read(last, "$.intake.complete")).isTrue();
        assertThat(entryIds(last)).containsExactly("type", "system", "function", "since", "note");
        assertThat((List<String>) JsonPath.read(last, "$.intake.entries[*].kind"))
                .containsExactly("TYPE", "SYSTEM", "FUNCTION", "DETAIL", "NOTE");
        assertThat((List<String>) JsonPath.read(last, "$.intake.entries[*].answer.kind"))
                .containsExactly("OPTION", "OPTION", "OPTION", "OPTION", "SKIPPED");
        assertThat((String) JsonPath.read(last, "$.intake.entries[3].text")).isEqualTo("いつから起きていますか");
        assertThat((String) JsonPath.read(last, "$.intake.entries[3].answer.optionId")).isEqualTo("this-week");
        assertThat((String) JsonPath.read(last, "$.intake.entries[3].answer.label")).isEqualTo("今週から");
        assertThat((Object) JsonPath.read(last, "$.utterance")).isNull();
        // 受付も聞き手も呼ばれず、言い直しだけが利用量に現れる
        assertThat(usageSteps(id)).containsExactly("restater");
    }

    @Test
    void 機能は_システムが決まっていない場合と_機能の設定がないシステムでは聞かれない() throws Exception {
        String unknownSystem = idOf(start());
        pick(unknownSystem, "type", "HOW_TO");
        unknown(unknownSystem, "system")
                .andExpect(jsonPath("$.system").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.kind").value("DETAIL"));

        String reports = idOf(start());
        pick(reports, "type", "HOW_TO");
        pick(reports, "system", "reports")
                .andExpect(jsonPath("$.function").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.kind").value("DETAIL"));

        String billing = idOf(start());
        pick(billing, "type", "HOW_TO");
        pick(billing, "system", "billing")
                .andExpect(jsonPath("$.intake.next.kind").value("FUNCTION"))
                .andExpect(jsonPath("$.intake.next.options[*].id").value(contains("invoice")));
    }

    @Test
    void 詳しい質問はマスの設定を使い_設定に質問がなければ聞き手の1問_文章で答える質問もある() throws Exception {
        // 請求管理の請求書でやってほしい: 文章で答える質問
        String partner = idOf(start());
        pick(partner, "type", "DO_FOR_ME");
        pick(partner, "system", "billing");
        pick(partner, "function", "invoice")
                .andExpect(jsonPath("$.intake.next.questionId").value("partner"))
                .andExpect(jsonPath("$.intake.next.text").value("対象の取引先はどこですか"))
                .andExpect(jsonPath("$.intake.next.options").isEmpty())
                .andExpect(jsonPath("$.intake.next.allowsText").value(true));
        // 選択肢のない質問に選択肢では答えられない
        pick(partner, "partner", "x").andExpect(status().isBadRequest());
        write(partner, "partner", "A社")
                .andExpect(jsonPath("$.intake.entries[3].answer.kind").value("TEXT"))
                .andExpect(jsonPath("$.intake.entries[3].answer.text").value("A社"))
                .andExpect(jsonPath("$.intake.next.questionId").value("note"));

        // 同じ請求書でも、種類が違えば設定の質問はなく、聞き手が質問を作る
        String interviewer = idOf(start());
        pick(interviewer, "type", "HOW_TO");
        pick(interviewer, "system", "billing");
        pick(interviewer, "function", "invoice")
                .andExpect(jsonPath("$.intake.next.questionId").value("q1"))
                .andExpect(jsonPath("$.intake.next.kind").value("DETAIL"))
                .andExpect(jsonPath("$.intake.next.text").value("どの場面での話ですか"))
                .andExpect(jsonPath("$.intake.next.options[*].id").value(contains("o1", "o2", "o3")))
                .andExpect(jsonPath("$.intake.next.options[1].label").value("保存したとき"));
    }

    @Test
    void 聞き手が失敗しても詳しい質問なしで先へ進む() throws Exception {
        // 聞き手のモックの失敗は、文章に書いたタグで起こす
        String id = idOf(create("[質問エラー] " + "使い方"));

        pick(id, "system", "reports")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type", "system")));
        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater");
    }

    // ---- わからない ----

    @Test
    void わからないは補足以外のすべての質問で選べる() throws Exception {
        String id = idOf(start());

        unknown(id, "type").andExpect(status().isOk()).andExpect(jsonPath("$.type").value(nullValue()));
        // 種類が決まっていなければ、種類を指定した設定は当てはまらない
        pick(id, "system", "job-admin").andExpect(jsonPath("$.intake.next.questionId").value("function"));
        unknown(id, "function")
                .andExpect(jsonPath("$.function").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.questionId").value("q1"));
        unknown(id, "q1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.entries[3].answer.kind").value("UNKNOWN"))
                .andExpect(jsonPath("$.intake.next.questionId").value("note"));
        // 補足に「わからない」はない
        unknown(id, "note").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void すべてにわからないと答えて文章もなければ補足は省けず_省くと400で_書けば進める() throws Exception {
        String id = idOf(start());
        unknown(id, "type");
        unknown(id, "system");
        unknown(id, "q1")
                .andExpect(jsonPath("$.intake.next.questionId").value("note"))
                .andExpect(jsonPath("$.intake.next.text").value("状況を一言で教えてください"))
                .andExpect(jsonPath("$.intake.next.canSkip").value(false));

        skip(id, "note").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        write(id, "note", "画面が真っ白")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.utterance").value("画面が真っ白"));
    }

    @Test
    void 何かひとつでも選んでいれば補足は省ける() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        unknown(id, "system");
        unknown(id, "q1").andExpect(jsonPath("$.intake.next.canSkip").value(true));
    }

    // ---- 文章で書く道 ----

    @Test
    void 文章で始めると置けた置き場所は受付が置いたものとして入り_補足は聞かれない() throws Exception {
        String created = create("求人管理画面の求人の掲載がおかしい");
        String id = idOf(created);

        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("LOOKS_WRONG");
        assertThat((String) JsonPath.read(created, "$.type.by")).isEqualTo("AI");
        assertThat((String) JsonPath.read(created, "$.system.id")).isEqualTo("job-admin");
        assertThat((String) JsonPath.read(created, "$.system.by")).isEqualTo("AI");
        assertThat((String) JsonPath.read(created, "$.function.id")).isEqualTo("posting");
        assertThat((String) JsonPath.read(created, "$.function.by")).isEqualTo("AI");
        assertThat((String) JsonPath.read(created, "$.utterance")).isEqualTo("求人管理画面の求人の掲載がおかしい");
        assertThat(entryIds(created)).containsExactly("type", "system", "function");
        assertThat((List<String>) JsonPath.read(created, "$.intake.entries[*].answer.kind"))
                .containsOnly("INFERRED");
        assertThat((String) JsonPath.read(created, "$.intake.entries[0].answer.optionId")).isEqualTo("LOOKS_WRONG");
        assertThat((String) JsonPath.read(created, "$.intake.entries[0].answer.label")).isEqualTo("おかしいかもしれない");
        assertThat(next(created, "questionId")).isEqualTo("since");

        // 詳しい質問に答えると、補足は聞かずに問いが組み立てられる
        pick(id, "since", "today")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.intake.complete").value(true));
        assertThat(usageSteps(id)).containsExactly("reception", "restater");
    }

    @Test
    void 置けなかった置き場所は質問として聞かれる() throws Exception {
        String created = create("請求書の仕様を教えて");

        // 種類（キーワードの該当なし）は置かれ、システムは文章に名前がないので置かれない
        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("HOW_IT_WORKS");
        assertThat((Object) JsonPath.read(created, "$.system")).isNull();
        assertThat(next(created, "questionId")).isEqualTo("system");
    }

    @Test
    void 途中で文章に切り替えると本人が選んだ置き場所は変えず_決まっていない置き場所を置く() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");

        // 文章は「おかしい」と読めるが、本人が選んだ種類は変えない
        String view = text(intakeText(id, "求人管理画面の応募者の一覧がおかしい").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(view, "$.type.id")).isEqualTo("HOW_TO");
        assertThat((String) JsonPath.read(view, "$.type.by")).isEqualTo("ASKER");
        assertThat((String) JsonPath.read(view, "$.system.id")).isEqualTo("job-admin");
        assertThat((String) JsonPath.read(view, "$.system.by")).isEqualTo("AI");
        assertThat((String) JsonPath.read(view, "$.function.id")).isEqualTo("applicants");
        assertThat((String) JsonPath.read(view, "$.utterance")).isEqualTo("求人管理画面の応募者の一覧がおかしい");
        assertThat((List<String>) JsonPath.read(view, "$.intake.entries[*].answer.kind"))
                .containsExactly("OPTION", "INFERRED", "INFERRED");
        // 種類は HOW_TO のまま。設定の詳しい質問（おかしいかもしれない用）は当てはまらず、聞き手の質問になる
        assertThat(next(view, "questionId")).isEqualTo("q1");
        // 文章を書いたので、最後に補足は聞かれない
        pick(id, "q1", "o1").andExpect(jsonPath("$.step").value("STRUCTURED"));
    }

    @Test
    void 答えかけの質問は_置けなければ改めて聞かれ_文章を足しても補足は聞かれない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports").andExpect(jsonPath("$.intake.next.questionId").value("q1"));

        intakeText(id, "ちょっと補足です")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.next.questionId").value("q1"))
                .andExpect(jsonPath("$.utterance").value("ちょっと補足です"));

        pick(id, "q1", "o3")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type", "system", "q1")));
    }

    @Test
    void 文章は書いた順に空行でつながれ_補足も続く() throws Exception {
        String id = idOf(create("最初の文章"));
        intakeText(id, "切り替えた文章").andExpect(jsonPath("$.utterance").value("最初の文章\n\n切り替えた文章"));
        // 種類、システムは置けなかったので聞かれる。わからないと答えても、文章があるので補足は聞かれない
        String last = finishIntake(id, null);

        assertThat((String) JsonPath.read(last, "$.utterance")).isEqualTo("最初の文章\n\n切り替えた文章");
        assertThat((String) JsonPath.read(last, "$.step")).isEqualTo("STRUCTURED");
    }

    @Test
    void 受付が失敗してもエラーにならず質問として聞かれ_文章を書いたので補足は聞かれない() throws Exception {
        String created = create("[判定エラー] 求人管理画面のことです");
        String id = idOf(created);

        // 何も置かれず、種類から順に聞く
        assertThat((Object) JsonPath.read(created, "$.type")).isNull();
        assertThat((Object) JsonPath.read(created, "$.system")).isNull();
        assertThat(entryIds(created)).isEmpty();
        assertThat(next(created, "questionId")).isEqualTo("type");
        assertThat(usageSteps(id)).containsExactly("reception");
        assertThat(jdbc.queryForObject(
                        "select (usages->0->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();

        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o1")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.utterance").value("[判定エラー] 求人管理画面のことです"));
    }

    @Test
    void 途中で文章に切り替えたときの受付の失敗もエラーにならない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");

        intakeText(id, "[判定エラー] 補足")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type.by").value("ASKER"))
                .andExpect(jsonPath("$.system").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.questionId").value("system"));
    }

    @Test
    void 文章に書かれたシステムの質問に答えれば受付が置かなかった機能も聞かれる() throws Exception {
        String id = idOf(create("請求管理について教えて"));

        // システムは置かれたが、機能の名前は文章にないので聞かれる
        assertThat((String) JsonPath.read(view(id), "$.system.id")).isEqualTo("billing");
        assertThat(next(view(id), "questionId")).isEqualTo("function");
        // この置き場所に設定の質問はなく、聞き手の質問に進む
        pick(id, "function", "invoice").andExpect(jsonPath("$.intake.next.questionId").value("q1"));
        pick(id, "q1", "o1").andExpect(jsonPath("$.step").value("STRUCTURED"));
    }

    // ---- 戻る ----

    @Test
    void 戻ると指定した質問以降の答えと_それで決まっていた置き場所が消え_文章は消えない() throws Exception {
        String created = create("求人管理画面の求人の掲載がおかしい");
        String id = idOf(created);
        assertThat(entryIds(view(id))).containsExactly("type", "system", "function");

        back(id, "system")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("INTAKE"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type")))
                .andExpect(jsonPath("$.type.id").value("LOOKS_WRONG"))
                .andExpect(jsonPath("$.system").value(nullValue()))
                .andExpect(jsonPath("$.function").value(nullValue()))
                .andExpect(jsonPath("$.intake.next.questionId").value("system"))
                .andExpect(jsonPath("$.utterance").value("求人管理画面の求人の掲載がおかしい"));
        // 戻った先から選び直して、最後まで進める。受付は呼び直されず、文章があるので補足も聞かれない
        pick(id, "system", "billing").andExpect(jsonPath("$.intake.next.questionId").value("function"));
        pick(id, "function", "invoice").andExpect(jsonPath("$.intake.next.questionId").value("q1"));
        pick(id, "q1", "o1")
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.system.by").value("ASKER"));
        assertThat(usageSteps(id).stream().filter(s -> s.equals("reception")).count()).isEqualTo(1);
    }

    @Test
    void 受付が置いた答えへ戻ると置き場所は決まっていない状態に戻る() throws Exception {
        String id = idOf(create("求人管理画面の求人の掲載がおかしい"));

        back(id, "type")
                .andExpect(jsonPath("$.type").value(nullValue()))
                .andExpect(jsonPath("$.system").value(nullValue()))
                .andExpect(jsonPath("$.function").value(nullValue()))
                .andExpect(jsonPath("$.intake.entries").isEmpty())
                .andExpect(jsonPath("$.intake.next.questionId").value("type"))
                .andExpect(jsonPath("$.utterance").value("求人管理画面の求人の掲載がおかしい"));
    }

    @Test
    void 詳しい質問と補足へ戻ると置き場所は残り_その質問が次の質問になる() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o2");
        write(id, "note", "メモ").andExpect(jsonPath("$.step").value("STRUCTURED"));
        // STRUCTURED では戻れない。reopen で補足へ戻ってから、詳しい質問へ戻る
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/inquiries/" + id + "/reopen"));

        back(id, "q1")
                .andExpect(jsonPath("$.intake.next.questionId").value("q1"))
                .andExpect(jsonPath("$.type.id").value("HOW_TO"))
                .andExpect(jsonPath("$.system.id").value("reports"))
                .andExpect(jsonPath("$.intake.entries[*].questionId").value(contains("type", "system")));
    }

    @Test
    void 答え終えていない質問へは戻れず_別の問い合わせの状態では戻れない() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");

        back(id, "function").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        back(id, "nothing").andExpect(status().isBadRequest());
        postJson("/api/inquiries/" + id + "/intake/back", "{}").andExpect(status().isBadRequest());
        assertThat(entryIds(view(id))).containsExactly("type");
    }

    // ---- 緊急度 BLOCKED ----

    @Test
    void 業務が止まっているときは詳しい質問を聞かず_文章がなければ補足は省けず_入口を終えた時点で担当者に送られる() throws Exception {
        String id = idOf(create(null, "BLOCKED"));
        pick(id, "type", "LOOKS_WRONG");
        pick(id, "system", "job-admin");
        // 設定に詳しい質問がある置き場所でも、聞かない
        pick(id, "function", "posting")
                .andExpect(jsonPath("$.intake.next.questionId").value("note"))
                .andExpect(jsonPath("$.intake.next.text").value("状況を一言で教えてください"))
                .andExpect(jsonPath("$.intake.next.canSkip").value(false));

        skip(id, "note").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        write(id, "note", "ログインできない")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true))
                .andExpect(jsonPath("$.urgency").value("BLOCKED"))
                .andExpect(jsonPath("$.handlingStatus").value("OPEN"))
                .andExpect(jsonPath("$.canSendNow").value(false))
                // 言い直しは組み立てて残す
                .andExpect(jsonPath("$.structured.summary").isNotEmpty());

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.urgency().name()).isEqualTo("BLOCKED");
            assertThat(n.sentImmediately()).isTrue();
            assertThat(n.structured()).isNotNull();
        });
        assertThat(usageSteps(id)).containsExactly("restater");
    }

    @Test
    void 業務が止まっていて文章を書いていれば補足は聞かれず_最後の置き場所の答えで担当者に送られる() throws Exception {
        String id = idOf(create("求人管理画面が使えない", "BLOCKED"));

        // 種類は置かれ、システムは置かれる。機能は文章にないので聞かれる
        assertThat(next(view(id), "questionId")).isEqualTo("function");
        unknown(id, "function")
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true));
        assertThat(notifier.notices()).hasSize(1);
    }

    @Test
    void 業務が止まっているときは言い直しが失敗しても担当者に送られる() throws Exception {
        String id = idOf(create("[言い直しエラー] 画面が真っ白", "BLOCKED"));

        unknown(id, "system")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true))
                .andExpect(jsonPath("$.structured").value(nullValue()));

        assertThat(notifier.notices()).singleElement().satisfies(n -> assertThat(n.structured()).isNull());
        assertThat(usageSteps(id)).containsExactly("reception", "restater");
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    @Test
    void 入口の途中で業務が止まっているに変えると_まだ答えていない詳しい質問は聞かれず補足に進む() throws Exception {
        String id = idOf(start());
        pick(id, "type", "LOOKS_WRONG");
        pick(id, "system", "job-admin");
        pick(id, "function", "posting").andExpect(jsonPath("$.intake.next.questionId").value("since"));

        urgency(id, "BLOCKED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgency").value("BLOCKED"))
                .andExpect(jsonPath("$.intake.next.questionId").value("note"))
                .andExpect(jsonPath("$.intake.next.canSkip").value(false));
        // 通常に戻せば、詳しい質問が聞かれる
        urgency(id, "NORMAL").andExpect(jsonPath("$.intake.next.questionId").value("since"));
        urgency(id, "HURRY").andExpect(jsonPath("$.urgency").value("HURRY"));
    }

    @Test
    void 入口の途中で業務が止まっているに変えて文章があれば_その場で担当者に送られる() throws Exception {
        String id = idOf(create("求人管理画面の求人の掲載がおかしい"));
        assertThat(next(view(id), "questionId")).isEqualTo("since");

        urgency(id, "BLOCKED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true));
    }

    @Test
    void 言い直しの確認中に業務が止まっているに変えると_その時点の言い直しで担当者に送られる() throws Exception {
        String id = toStructured("期限の仕様", null);
        String summary = JsonPath.read(view(id), "$.structured.summary");

        urgency(id, "BLOCKED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("CLOSED"))
                .andExpect(jsonPath("$.outcome").value("FILED"))
                .andExpect(jsonPath("$.sentImmediately").value(true))
                .andExpect(jsonPath("$.structured.summary").value(summary));

        assertThat(notifier.notices()).singleElement().satisfies(n -> {
            assertThat(n.sentImmediately()).isTrue();
            assertThat(n.structured().summary()).isEqualTo(summary);
        });
    }

    @Test
    void 通常以外への変更は入口と確認の間でだけ許され_不正な値は400() throws Exception {
        String id = toStructured("期限の仕様", null);

        urgency(id, "HURRY").andExpect(jsonPath("$.step").value("STRUCTURED")).andExpect(jsonPath("$.urgency").value("HURRY"));
        urgency(id, "URGENT").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        postJson("/api/inquiries/" + id + "/intake/answers", "{}").andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/inquiries/" + id + "/urgency")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 作成時に指定した緊急度は問い合わせに残る() throws Exception {
        String id = idOf(create("期限の仕様", "HURRY"));

        assertThat((String) JsonPath.read(view(id), "$.urgency")).isEqualTo("HURRY");
        assertThat(row(id).get("urgency")).isEqualTo("HURRY");
    }

    // ---- 不正な入力 ----

    @Test
    void 違うquestionIdは409_選択肢にないoptionIdは400_選択肢のある質問への文章は400() throws Exception {
        String id = idOf(start());

        pick(id, "system", "billing").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        pick(id, "type", "INCIDENT").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        write(id, "type", "仕組み").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 検証に落ちた入力は何も残さない
        assertThat(entryIds(view(id))).isEmpty();
    }

    @Test
    void 答え方が0個か2個以上_questionIdがない_補足の長さの超過は400() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "reports");
        pick(id, "q1", "o1");

        intakeReply(id, "{\"questionId\":\"note\"}").andExpect(status().isBadRequest());
        intakeReply(id, "{\"questionId\":\"note\",\"text\":\"a\",\"skipped\":true}").andExpect(status().isBadRequest());
        intakeReply(id, "{\"text\":\"a\"}").andExpect(status().isBadRequest());
        write(id, "note", "   ").andExpect(status().isBadRequest());
        write(id, "note", "あ".repeat(4001)).andExpect(status().isBadRequest());
        write(id, "note", "あ".repeat(4000)).andExpect(status().isOk()).andExpect(jsonPath("$.step").value("STRUCTURED"));
    }

    @Test
    void 詳しい質問への文章は1000文字まで() throws Exception {
        String id = idOf(start());
        pick(id, "type", "DO_FOR_ME");
        pick(id, "system", "billing");
        pick(id, "function", "invoice");

        write(id, "partner", "あ".repeat(1001)).andExpect(status().isBadRequest());
        write(id, "partner", "あ".repeat(1000)).andExpect(status().isOk());
    }

    @Test
    void 入口が終わった問い合わせへの答えとtextとbackは段階に応じて409になる() throws Exception {
        String id = toStructured("期限の仕様", null);

        pick(id, "type", "HOW_TO").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        intakeText(id, "x").andExpect(status().isConflict());
        back(id, "type").andExpect(status().isConflict());
        compose(id).andExpect(status().isConflict());
    }

    @Test
    void composeは入口が終わっていないと409() throws Exception {
        String id = idOf(create("期限の仕様"));

        compose(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 文章の検証はintake_textでも行う() throws Exception {
        String id = idOf(start());

        intakeText(id, "   ").andExpect(status().isBadRequest());
        intakeText(id, "あ".repeat(4001)).andExpect(status().isBadRequest());
        postJson("/api/inquiries/" + id + "/intake/text", "{}").andExpect(status().isBadRequest());
        assertThat((Object) JsonPath.read(view(id), "$.utterance")).isNull();
    }
}
