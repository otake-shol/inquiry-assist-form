package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 担当者への AI の案（契約書「AI の案（advice）」）を API から確かめる。案は、担当者の番の問い合わせに、確かめられた事実が
 * 1つ以上あるとき、裏で作られる（時間に頼る確認は、固定の待ちではなく、状況が変わるまで待つ）。
 */
@SpringBootTest(properties = {"inquiry.mock.slow-step-delay=300ms", "inquiry.operator.heartbeat-interval=PT0.2S"})
class AdviceApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private String adviceStatus(String id) {
        return (String) row(id).get("advice_status");
    }

    private void awaitRow(String id, java.util.function.Supplier<Boolean> condition, String what) throws Exception {
        assertThat(waitFor(condition::get, 15_000)).as(what + ": " + id).isTrue();
    }

    private List<String> findings(String detail) {
        return JsonPath.read(detail, "$.answer.memo.findings");
    }

    // ---- 案ができる ----

    @Test
    void 裏での調査が終わると案がDONEで入り_判断の案は基づく事実の文を持つ() throws Exception {
        String id = filedNow(HOW_TO, null);

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(detail, "$.advice.judgment.text"))
                .isEqualTo("資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。");
        assertThat((Boolean) JsonPath.read(detail, "$.advice.judgment.needsConsultation")).isFalse();
        assertThat((Object) JsonPath.read(detail, "$.advice.judgment.consultationReason")).isNull();
        // basedOn は番号ではなく、1件目の事実の文（answer.memo.findings の文と同じ）
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOn"))
                .containsExactly(findings(detail).get(0));
        assertThat((Object) JsonPath.read(detail, "$.advice.procedure")).isNull();
        // 担当者向けの記録には、助言の呼び出しが入る
        assertThat((List<String>) JsonPath.read(detail, "$.trace[*].skill")).endsWith("advisor");
        assertThat(usageSteps(id)).endsWith("investigator", "explainer", "advisor");
    }

    @Test
    void 手順の案はDO_FOR_MEで入り_最後の事実の文に基づく() throws Exception {
        String id = filedNow("掲載の延長をお願いします", null);
        assertThat((String) JsonPath.read(view(id), "$.type.id")).isEqualTo("DO_FOR_ME");

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((List<Object>) JsonPath.read(detail, "$.advice.procedure.steps")).hasSize(2);
        assertThat((String) JsonPath.read(detail, "$.advice.procedure.steps[0].target")).isEqualTo("求人管理画面の求人の編集");
        assertThat((String) JsonPath.read(detail, "$.advice.procedure.steps[0].action")).isNotBlank();
        assertThat((String) JsonPath.read(detail, "$.advice.procedure.steps[0].check")).isNotBlank();
        assertThat((String) JsonPath.read(detail, "$.advice.procedure.rollback")).isEqualTo("掲載期限を、元の日付に戻す");
        List<String> findings = findings(detail);
        assertThat((List<String>) JsonPath.read(detail, "$.advice.procedure.basedOn"))
                .containsExactly(findings.get(findings.size() - 1));
    }

    @Test
    void 相談のタグは相談が要るとし_理由を添える() throws Exception {
        String id = filedNow("[相談] " + HOW_TO, null);

        String detail = detail(id);

        assertThat((Boolean) JsonPath.read(detail, "$.advice.judgment.needsConsultation")).isTrue();
        assertThat((String) JsonPath.read(detail, "$.advice.judgment.consultationReason")).isNotBlank();
    }

    @Test
    void 根拠なしの案はコードが捨て_judgmentはnullのままDONE() throws Exception {
        String id = filedNow("[根拠なしの案] " + HOW_TO, null);

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((Object) JsonPath.read(detail, "$.advice.judgment")).isNull();
        assertThat((Object) JsonPath.read(detail, "$.advice.procedure")).isNull();
    }

    @Test
    void 助言が失敗した場合はFAILED_案は空で_本人には何も起きない() throws Exception {
        String id = filedNow("[案エラー] " + HOW_TO, null);

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("FAILED");
        assertThat((Object) JsonPath.read(detail, "$.advice.judgment")).isNull();
        assertThat((Object) JsonPath.read(detail, "$.advice.procedure")).isNull();
        // 失敗した呼び出しとして、担当者向けの記録に残る
        assertThat((List<Boolean>) JsonPath.read(detail, "$.trace[?(@.skill=='advisor')].failed")).containsExactly(true);
        // 調査の結果は、案が失敗しても届いている
        assertThat((String) JsonPath.read(detail, "$.investigation.status")).isEqualTo("DONE");
        String asker = view(id);
        assertThat((String) JsonPath.read(asker, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((Boolean) JsonPath.read(asker, "$.unreadReply")).isFalse();
    }

    @Test
    void 確かめられた事実がない件は案を作らない() throws Exception {
        String id = filedNow("[根拠なし] " + HOW_TO, null);

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("NOT_RUN");
        assertThat((Object) JsonPath.read(detail, "$.advice.judgment")).isNull();
        assertThat(usageSteps(id)).doesNotContain("advisor");
    }

    // ---- 作るきっかけ ----

    @Test
    void 本人が解決していないと答えて渡った件にも案が入る() throws Exception {
        String id = toAnswering(HOW_TO, null);
        stream(id);
        // 本人が確かめている間は、担当者の番でないので、案を作らない
        assertThat(adviceStatus(id)).isEqualTo("NOT_RUN");
        assertThat(usageSteps(id)).doesNotContain("advisor");

        resolve(id, false).andExpect(status().isOk());

        assertThat(adviceStatus(id)).isEqualTo("DONE");
        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOn")).containsExactly(findings(detail).get(0));
        assertThat((String) JsonPath.read(detail, "$.handoverReason")).isEqualTo("UNRESOLVED");
    }

    @Test
    void 答えと同時に担当者に渡った件にも案が入る() throws Exception {
        String id = toAnswering("表示がおかしい", null);

        stream(id);

        String detail = detail(id);
        assertThat((String) JsonPath.read(detail, "$.handoverReason")).isEqualTo("ROUTE");
        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
    }

    @Test
    void 案がDONEの件が再び担当者の番になっても作り直さない() throws Exception {
        String id = filedNow(HOW_TO, null);
        assertThat(usageSteps(id)).filteredOn("advisor"::equals).hasSize(1);
        // 担当者が見送り、本人が追記して開き直す（REOPENED で担当者の番に戻る）
        mvc.perform(post("/api/operator/inquiries/" + id + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reasonKind", "OUT_OF_SCOPE", "reason", "対象外です")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "もう一度お願いします")))
                .andExpect(status().isOk());

        assertThat(usageSteps(id)).filteredOn("advisor"::equals).hasSize(1);
        assertThat((String) JsonPath.read(detail(id), "$.advice.status")).isEqualTo("DONE");
    }

    @Test
    void 本人が待っている間は案を呼ばない() throws Exception {
        String id = toAnswering("[遅延] " + HOW_TO, null);
        CompletableFuture<String> streaming = CompletableFuture.supplyAsync(() -> {
            try {
                return streamOnly(id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitRow(id, () -> "RUNNING".equals(row(id).get("investigation_status")), "調査が始まらない");

        assertThat(adviceStatus(id)).isEqualTo("NOT_RUN");
        String body = streaming.get(15, TimeUnit.SECONDS);

        // 本人が確かめる答えになった（担当者に渡らない）ので、案は作らない。本人向けの記録にも、助言は現れない
        assertThat(body).contains("RESULT_CONFIRMATION");
        awaitInvestigationIdle(id);
        assertThat(adviceStatus(id)).isEqualTo("NOT_RUN");
        assertThat(usageSteps(id)).doesNotContain("advisor");
        assertThat((List<String>) JsonPath.read(view(id), "$.trace[*].skill")).doesNotContain("advisor", "drafter");
    }

    // ---- 調べ直し ----

    @Test
    void 調べ直すと前の案を消して作り直す() throws Exception {
        String id = filedNow(HOW_TO, null);
        assertThat(usageSteps(id)).filteredOn("advisor"::equals).hasSize(1);

        mvc.perform(post("/api/operator/inquiries/" + id + "/investigation")).andExpect(status().isOk());
        awaitInvestigationIdle(id);

        assertThat(adviceStatus(id)).isEqualTo("DONE");
        assertThat(usageSteps(id)).filteredOn("advisor"::equals).hasSize(2);
        assertThat((String) JsonPath.read(detail(id), "$.advice.status")).isEqualTo("DONE");
    }

    // ---- 担当者側の未読 ----

    @Test
    void 案ができても担当者側は未読に戻らない() throws Exception {
        String id = toAnswering("[遅延] " + HOW_TO, null);
        CompletableFuture<String> streaming = CompletableFuture.supplyAsync(() -> {
            try {
                return streamOnly(id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitRow(id, () -> "RUNNING".equals(row(id).get("investigation_status")), "調査が始まらない");
        mvc.perform(post("/api/inquiries/" + id + "/send-now")).andExpect(status().isOk());
        streaming.get(15, TimeUnit.SECONDS);
        // 調査の結果が届いた（担当者側は未読に戻る）後に、担当者が既読にする
        awaitRow(id, () -> "DONE".equals(row(id).get("investigation_status")), "調査が終わらない");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());

        awaitInvestigationIdle(id);

        assertThat(adviceStatus(id)).isEqualTo("DONE");
        assertThat((Boolean) JsonPath.read(detail(id), "$.read")).isTrue();
    }

    // ---- 本人向けの表示 ----

    @Test
    void 本人向けのInquiryViewにはadviceもhandoverの案もなく_traceに助言と下書きを含めない() throws Exception {
        String id = filedNow(HOW_TO, null);
        mvc.perform(post("/api/operator/inquiries/" + id + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK")))
                .andExpect(status().isOk());

        String asker = view(id);

        assertThat(asker).doesNotContain("\"advice\"");
        assertThat((List<String>) JsonPath.read(asker, "$.trace[*].skill")).doesNotContain("advisor", "drafter");
        // 担当者向けには、どちらも入る
        assertThat((List<String>) JsonPath.read(detail(id), "$.trace[*].skill")).contains("advisor", "drafter");
        mvc.perform(get("/api/inquiries")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].advice").doesNotExist());
    }
}
