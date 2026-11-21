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

/** スキルの構成に伴う、API から見た動き（照合、説明の失敗、聞き手の失敗、追加質問の回答の状態、利用量）を確認する。 */
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

    private List<String> usageSteps(String id) {
        return jdbc.queryForList(
                "select u->>'step' from inquiries, jsonb_array_elements(usages) with ordinality as t(u, ord)"
                        + " where id = ?::uuid order by ord",
                String.class,
                id);
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
                .andExpect(jsonPath("$.step").value("ANSWERING"))
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
                .andExpect(jsonPath("$.outcome").value("FILED"))
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
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.step").value("ANSWERING"));
    }

    // ---- 聞き手 ----

    @Test
    void 質問エラーでも201が返り質問なしで言い直しまで進める() throws Exception {
        String utterance = "[質問エラー] " + SPEC;

        String created = create(utterance, null);
        String id = idOf(created);

        assertThat((List<?>) JsonPath.read(created, "$.questions")).isEmpty();
        assertThat((String) JsonPath.read(created, "$.category")).isEqualTo("SPEC_QUESTION");
        answers(id, EMPTY_ANSWERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("STRUCTURED"))
                .andExpect(jsonPath("$.structured.summary").isNotEmpty());
        // 失敗した呼び出しとして利用量に残る
        assertThat(usageSteps(id)).startsWith("reception", "interviewer", "restater");
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

    @Test
    void 種別変更で聞き手が失敗しても200で質問は空になり次の変更で再び呼ばれる() throws Exception {
        String id = idOf(create("[質問エラー] 延長をお願いします", null));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/inquiries/" + id + "/category")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPEC_QUESTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isEmpty());

        assertThat(usageSteps(id)).containsExactly("reception", "interviewer");
    }

    // ---- 利用量 ----

    @Test
    void 利用量のstepはスキルの識別子で作業依頼では聞き手と調査が現れない() throws Exception {
        String id = idOf(create("延長をお願いします", null));
        answers(id, EMPTY_ANSWERS);
        confirm(id).andExpect(jsonPath("$.outcome").value("FILED"));

        assertThat(usageSteps(id)).containsExactly("reception", "restater");
    }

    @Test
    void 仕様確認の全経路の利用量は受付_聞き手_言い直し_調査_説明の順() throws Exception {
        String id = toAnswering(SPEC, null);
        stream(id);

        assertThat(usageSteps(id)).containsExactly("reception", "interviewer", "restater", "investigator", "explainer");
    }

    // ---- 追加質問の回答の状態 ----

    @Test
    void followUpsのstatusは回答あり_わからない_答える前に送ったの3通り() throws Exception {
        String answered = idOf(create(SPEC, null));
        answers(answered, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"求人管理画面\"},{\"questionId\":\"q2\",\"answer\":null}]}");
        sendNow(answered);
        String before = idOf(create(SPEC, null));
        sendNow(before);

        String a = operatorDetail(answered);
        assertThat((List<String>) JsonPath.read(a, "$.followUps[*].status")).containsExactly("ANSWERED", "UNKNOWN");
        assertThat((String) JsonPath.read(a, "$.followUps[0].answer")).isEqualTo("求人管理画面");
        assertThat((Object) JsonPath.read(a, "$.followUps[1].answer")).isNull();

        String b = operatorDetail(before);
        assertThat((List<String>) JsonPath.read(b, "$.followUps[*].status")).containsOnly("NOT_ANSWERED").hasSize(2);
        assertThat((List<Object>) JsonPath.read(b, "$.followUps[*].answer")).containsOnlyNulls();
    }

    @Test
    void 質問がなければfollowUpsは空のまま() throws Exception {
        String id = idOf(create("[質問なし] " + SPEC, null));
        answers(id, EMPTY_ANSWERS);
        sendNow(id);

        assertThat((List<?>) JsonPath.read(operatorDetail(id), "$.followUps")).isEmpty();
    }

    @Test
    void 追加質問へ戻った後に答えずに送ると未回答になる() throws Exception {
        String id = idOf(create(SPEC, null));
        answers(id, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"求人管理画面\"},{\"questionId\":\"q2\",\"answer\":\"掲載中\"}]}");
        mvc.perform(post("/api/inquiries/" + id + "/reopen")).andExpect(jsonPath("$.step").value("TRIAGED"));

        sendNow(id);

        String detail = operatorDetail(id);
        assertThat((List<String>) JsonPath.read(detail, "$.followUps[*].status")).containsOnly("NOT_ANSWERED");
        assertThat((List<Object>) JsonPath.read(detail, "$.followUps[*].answer")).containsOnlyNulls();
    }

    @Test
    void 追加質問へ戻って答え直すと回答あり扱いに戻る() throws Exception {
        String id = idOf(create(SPEC, null));
        answers(id, EMPTY_ANSWERS);
        mvc.perform(post("/api/inquiries/" + id + "/reopen"));
        answers(id, "{\"answers\":[{\"questionId\":\"q1\",\"answer\":\"その他\"}]}");
        sendNow(id);

        assertThat((List<String>) JsonPath.read(operatorDetail(id), "$.followUps[*].status"))
                .containsExactly("ANSWERED", "UNKNOWN");
    }

    @Test
    void 障害の固定質問に答えて案内された問い合わせは回答ありになる() throws Exception {
        String id = idOf(create("サイトが止まっていて障害になっている", null));
        answers(id, "{\"answers\":[{\"questionId\":\"incident-check\",\"answer\":\"いいえ\"}]}");
        sendNow(id);

        assertThat((List<String>) JsonPath.read(operatorDetail(id), "$.followUps[*].status")).containsExactly("ANSWERED");
    }
}
