package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/** スキルの構成に伴う、API から見た動き（照合、説明の失敗、聞き手の失敗、入口の答えの記録、利用量）を確認する。 */
class SkillFlowTest extends ApiSupport {

    private static final String SPEC = "期限の仕様";
    private static final String CODE_FACT = "求人は、掲載期限の時刻ちょうどから期限切れとして扱われる";
    private static final String GHOST_FACT = "期限切れの求人は、管理画面の再掲載ボタンから元に戻せる";

    private String doneOf(String streamBody) {
        return streamBody.lines()
                .dropWhile(l -> !l.startsWith("event:done"))
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring("data:".length())
                .trim();
    }

    private String operatorDetail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    // ---- 照合と説明 ----

    @Test
    void 一部幽霊は確かめられた事実だけから説明を書き落ちた事実は未確認として残る() throws Exception {
        String id = toAnswering("[一部幽霊] " + SPEC, null);

        String streamBody = stream(id);
        String done = doneOf(streamBody);

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isTrue();
        String explanation = JsonPath.read(done, "$.answer.askerExplanation");
        assertThat(explanation).contains(CODE_FACT).doesNotContain(GHOST_FACT).doesNotContain("再掲載");
        assertThat((List<String>) JsonPath.read(done, "$.answer.memo.findings")).containsExactly(CODE_FACT);
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).hasSize(1);
        assertThat((String) JsonPath.read(done, "$.answer.citations[0].path")).contains("JobPostingService");

        // 本人向けの応答（done と最終の GET）は unconfirmed が空で、落ちた事実の文を本文のどこにも含まない
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.unconfirmed")).isEmpty();
        String view = text(mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isOk()));
        assertThat((List<?>) JsonPath.read(view, "$.answer.memo.unconfirmed")).isEmpty();
        assertThat(streamBody).doesNotContain(GHOST_FACT);
        assertThat(view).doesNotContain(GHOST_FACT);

        // 保存は変えず、オペレータの詳細には入っている
        resolve(id, false);
        String detail = operatorDetail(id);
        assertThat((List<String>) JsonPath.read(detail, "$.answer.memo.unconfirmed")).containsExactly(GHOST_FACT);
    }

    @Test
    void 幽霊出典は事実がすべて未確認として残り根拠なしになる() throws Exception {
        String id = toAnswering("[幽霊出典] " + SPEC, null);

        String streamBody = stream(id);
        String done = doneOf(streamBody);

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(done, "$.answer.askerExplanation")).isNull();
        // 説明は呼ばれず、利用量にも説明は現れない
        assertThat(usageSteps(id)).doesNotContain("explainer");

        // 本人向けの応答には確かめられなかった事実を含めない
        String ghostB = "夜間のバッチで自動的に延長される";
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.unconfirmed")).isEmpty();
        String view = text(mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isOk()));
        assertThat((List<?>) JsonPath.read(view, "$.answer.memo.unconfirmed")).isEmpty();
        for (String body : List.of(streamBody, view)) {
            assertThat(body).doesNotContain(GHOST_FACT).doesNotContain(ghostB);
        }

        // 担当者の詳細（取得と既読化）には入っている
        resolve(id, false);
        assertThat((List<?>) JsonPath.read(operatorDetail(id), "$.answer.memo.unconfirmed")).hasSize(2);
        String read = text(mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk()));
        assertThat((List<?>) JsonPath.read(read, "$.answer.memo.unconfirmed")).hasSize(2);
    }

    @Test
    void 説明エラーは失敗を流し段階はANSWERINGのまま本人向けの回答はnullで調査の内容が保存される() throws Exception {
        String id = toAnswering("[説明エラー] " + SPEC, null);

        String body = stream(id);

        assertThat(body).contains("event:failed").contains("AI_UNAVAILABLE").doesNotContain("event:done");
        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.awaiting").value("INVESTIGATION"))
                .andExpect(jsonPath("$.answer").value(Matchers.nullValue()));
        var row = row(id);
        assertThat(row.get("technical_memo")).isNotNull();
        assertThat(row.get("answer_grounded")).isNull();
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(citations) from inquiries where id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
        assertThat(usageSteps(id)).endsWith("investigator", "explainer");
        assertThat(jdbc.queryForObject(
                        "select (usages->-1->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        // 失敗した説明の利用量は、モック由来として残る（モデルが unknown のままにならない）
        assertThat(jdbc.queryForObject(
                        "select (usages->-1->>'mock')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select usages->-1->>'model' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("mock");
        // 担当者に送られるまで、オペレータからは見えない
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 説明エラーの後に今すぐ送るとオペレータ詳細に調査の内容が入る() throws Exception {
        String id = toAnswering("[説明エラー] " + SPEC, null);
        stream(id);

        sendNow(id)
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.answer").value(Matchers.nullValue()));

        String detail = operatorDetail(id);
        assertThat((Boolean) JsonPath.read(detail, "$.answer.grounded")).isFalse();
        assertThat((Object) JsonPath.read(detail, "$.answer.askerExplanation")).isNull();
        assertThat((List<?>) JsonPath.read(detail, "$.answer.memo.findings")).hasSize(2);
        assertThat((List<Boolean>) JsonPath.read(detail, "$.answer.citations[*].verified")).containsOnly(true).hasSize(2);
    }

    @Test
    void 説明エラーのまま再試行しても失敗し段階は変わらない() throws Exception {
        String id = toAnswering("[説明エラー] " + SPEC, null);

        stream(id);
        String second = stream(id);

        assertThat(second).contains("event:failed").doesNotContain("event:done");
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.awaiting").value("INVESTIGATION"));
    }

    // ---- 問いに答えられたか ----

    @Test
    void answeredは根拠あり_答えている_と_根拠あり_的外れ_と_根拠なしの3通りで本人向けにもオペレータ詳細にも正しい() throws Exception {
        String answers = toAnswering(SPEC, null);
        String offTopic = toAnswering("[的外れ] " + SPEC, null);
        String ungrounded = toAnswering("[根拠なし] " + SPEC, null);
        String ghost = toAnswering("[幽霊出典] " + SPEC, null);

        record Expected(String id, boolean grounded, boolean answered) {}
        for (Expected e : List.of(
                new Expected(answers, true, true),
                new Expected(offTopic, true, false),
                new Expected(ungrounded, false, false),
                new Expected(ghost, false, false))) {
            String done = doneOf(stream(e.id()));
            assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isEqualTo(e.grounded());
            assertThat((Boolean) JsonPath.read(done, "$.answer.answered")).isEqualTo(e.answered());
            String view = text(mvc.perform(get("/api/inquiries/" + e.id())).andExpect(status().isOk()));
            assertThat((Boolean) JsonPath.read(view, "$.answer.answered")).isEqualTo(e.answered());

            resolve(e.id(), false);
            String detail = operatorDetail(e.id());
            assertThat((Boolean) JsonPath.read(detail, "$.answer.grounded")).isEqualTo(e.grounded());
            assertThat((Boolean) JsonPath.read(detail, "$.answer.answered")).isEqualTo(e.answered());
        }
    }

    @Test
    void 判定が記録されていない記録は根拠の有無に従い_答えていないと判定したことにしない() throws Exception {
        // answered の列を足す前に作られた記録を再現する。当時は判定そのものがなかった
        String grounded = toAnswering(SPEC, null);
        String ungrounded = toAnswering("[根拠なし] " + SPEC, null);
        for (String id : List.of(grounded, ungrounded)) {
            stream(id);
            resolve(id, false);
            jdbc.update("update inquiries set answer_answered = null where id = ?::uuid", id);
        }

        String groundedView = text(mvc.perform(get("/api/inquiries/" + grounded)).andExpect(status().isOk()));
        assertThat((Boolean) JsonPath.read(groundedView, "$.answer.answered")).isTrue();
        assertThat((Boolean) JsonPath.read(operatorDetail(grounded), "$.answer.answered")).isTrue();

        String ungroundedView = text(mvc.perform(get("/api/inquiries/" + ungrounded)).andExpect(status().isOk()));
        assertThat((Boolean) JsonPath.read(ungroundedView, "$.answer.answered")).isFalse();
        assertThat((Boolean) JsonPath.read(operatorDetail(ungrounded), "$.answer.answered")).isFalse();
    }

    @Test
    void 的外れでも根拠ありなら説明と出典と技術メモは返し_担当者に渡る() throws Exception {
        String id = toAnswering("[的外れ] " + SPEC, null);

        String done = doneOf(stream(id));

        assertThat((String) JsonPath.read(done, "$.answer.askerExplanation")).isNotBlank();
        assertThat((List<?>) JsonPath.read(done, "$.answer.citations")).hasSize(2);
        assertThat((List<?>) JsonPath.read(done, "$.answer.memo.findings")).hasSize(2);
        // 的外れ（問いに答えていない）なので、本人に選ばせず担当者に渡す。調べた結果は、本人にも見せる
        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(done, "$.handoverReason")).isEqualTo("NO_ANSWER");
        assertThat((Boolean) JsonPath.read(done, "$.answer.answered")).isFalse();
    }

    @Test
    void おかしいかもしれないは的外れでも担当者に送られる() throws Exception {
        String id = toAnswering("[的外れ] 表示がおかしい", null);

        String done = doneOf(stream(id));

        assertThat((String) JsonPath.read(done, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((Boolean) JsonPath.read(done, "$.answer.answered")).isFalse();
    }

    // ---- 聞き手 ----

    @Test
    void 質問エラーでも質問なしで言い直しまで進め_失敗した呼び出しとして利用量に残る() throws Exception {
        String id = idOf(create("[質問エラー] " + SPEC));

        String last = finishIntake(id, null);

        assertThat((String) JsonPath.read(last, "$.awaiting")).isEqualTo("QUESTION_CONFIRMATION");
        assertThat((String) JsonPath.read(last, "$.structured.summary")).isNotEmpty();
        // 詳しい質問はなく、システムへの答えと文章だけで進んだ
        assertThat((List<String>) JsonPath.read(last, "$.intake.entries[*].questionId"))
                .containsExactly("type", "system");
        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater");
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select (usages->0->>'failed')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isFalse();
        // 失敗した聞き手の利用量も、モック由来として残る
        assertThat(jdbc.queryForObject(
                        "select (usages->1->>'mock')::boolean from inquiries where id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    // ---- 利用量 ----

    @Test
    void 変えてほしいは調査せず_利用量に聞き手と言い直しだけが現れる() throws Exception {
        String id = toStructured("画面を変えてほしい", null);
        confirm(id).andExpect(jsonPath("$.awaiting").value("HANDLING"));

        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater");
    }

    @Test
    void 仕組みを知りたいの全経路の利用量は受付_聞き手_言い直し_調査_説明の順() throws Exception {
        String id = toAnswering(SPEC, null);
        stream(id);

        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater", "investigator", "explainer");
    }

    // ---- 入口の答えの記録 ----

    @Test
    void 担当者の詳細には入口の答えが聞いた順に入り_選んだ_わからない_書かずに進んだが区別できる() throws Exception {
        String id = idOf(start());
        pick(id, "type", "HOW_TO");
        pick(id, "system", "job-admin");
        unknown(id, "function");
        pick(id, "q1", "o2");
        skip(id, "note").andExpect(jsonPath("$.awaiting").value("QUESTION_CONFIRMATION"));
        confirm(id);
        // 調査は確定の時点で始まる。結果が出た後に、解決していないと伝えて担当者に渡す
        stream(id);
        resolve(id, false).andExpect(jsonPath("$.awaiting").value("HANDLING"));

        String detail = operatorDetail(id);

        assertThat((List<String>) JsonPath.read(detail, "$.intake[*].questionId"))
                .containsExactly("type", "system", "function", "q1", "note");
        assertThat((List<String>) JsonPath.read(detail, "$.intake[*].kind"))
                .containsExactly("TYPE", "SYSTEM", "FUNCTION", "DETAIL", "NOTE");
        assertThat((List<String>) JsonPath.read(detail, "$.intake[*].answer.kind"))
                .containsExactly("OPTION", "OPTION", "UNKNOWN", "OPTION", "SKIPPED");
        assertThat((String) JsonPath.read(detail, "$.intake[3].answer.label")).isEqualTo("保存したとき");
        assertThat((String) JsonPath.read(detail, "$.intake[3].text")).isEqualTo("どの場面での話ですか");
        assertThat((Object) JsonPath.read(detail, "$.utterance")).isNull();
    }

    @Test
    void 補足に書いた文章は本人が書いた文章に入り_詳しい質問がなければ入口の答えは置き場所だけ() throws Exception {
        String id = idOf(create("[質問なし] " + SPEC));
        finishIntake(id, null);
        confirm(id);
        // 調査は確定の時点で始まる。結果が出た後に、解決していないと伝えて担当者に渡す
        stream(id);
        resolve(id, false).andExpect(status().isOk());

        String detail = operatorDetail(id);

        assertThat((List<String>) JsonPath.read(detail, "$.intake[*].kind")).containsExactly("TYPE", "SYSTEM");
        assertThat((String) JsonPath.read(detail, "$.utterance")).isEqualTo("[質問なし] " + SPEC);
    }
}
