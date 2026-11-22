package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 先例を使う（契約書「仕様の持ち主への相談」の、担当者が決定を記録する・先例の一覧と直すこと・同じ置き場所の先例・AI の案と先例）を
 * API から確かめる。DB は他のテストと共有するため、置き場所を確かめるテストは、ほかのテストが使わないシステムの識別子を、
 * 置き場所の上書き（{@code placement_override}）で使う。
 */
class PrecedentUseApiTest extends ApiSupport {

    private static final String DECISION = "期限ちょうどの時刻から期限切れとして扱うのは、意図した仕様です。";

    // ---- 補助 ----

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private String handling(String text) throws Exception {
        return filedNow(text, null);
    }

    private ResultActions consult(String id) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/consultations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("question", "相談です")));
    }

    private String body(String decision) {
        return json("decisionKind", "AS_INTENDED", "decision", decision, "reason", "理由です", "decidedBy", "決めた人");
    }

    private ResultActions ownerDecide(String id, String decision) throws Exception {
        return mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(decision)));
    }

    private static String uniqueSystem() {
        return "sys-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 置き場所を上書きして、その置き場所の先例だけを調べられるようにする。 */
    private void placeAt(String id, String type, String systemId, String functionId) {
        jdbc.update(
                "update inquiries set placement_override = ?::jsonb where id = ?::uuid",
                "{\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"systemId\":"
                        + (systemId == null ? "null" : "\"" + systemId + "\"") + ",\"functionId\":"
                        + (functionId == null ? "null" : "\"" + functionId + "\"") + "}",
                id);
    }

    /** 先例を直接入れる。決めた時刻は、いまから秒数だけ前。 */
    private String insertPrecedent(
            String inquiryId, String type, String systemId, String functionId, long secondsAgo, String language) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(secondsAgo));
        jdbc.update(
                "insert into precedents (id, inquiry_id, type, system_id, function_id, situation, decision_kind, decision, reason,"
                        + " decided_by, language, decided_at, updated_at)"
                        + " values (?::uuid, ?::uuid, ?, ?, ?, '状況', 'ACCEPT', ?, '理由', '決めた人', ?, ?, ?)",
                id.toString(),
                inquiryId,
                type,
                systemId,
                functionId,
                "決定-" + id,
                language,
                at,
                at);
        return id.toString();
    }

    private List<String> precedentIds(String detail) {
        return JsonPath.read(detail, "$.precedents[*].id");
    }

    // ---- 同じ置き場所の先例 ----

    @Test
    void 同じシステムと種類の先例を_機能が同じものを先に_新しい順で_5件まで返し_自分の先例は含めない() throws Exception {
        String system = uniqueSystem();
        String inquiry = handling("使い方を教えて");
        placeAt(inquiry, "HOW_TO", system, "f1");
        String other = handling("使い方を教えて");
        String sameOld = insertPrecedent(other, "HOW_TO", system, "f1", 500, "ja");
        String sameNew = insertPrecedent(other, "HOW_TO", system, "f1", 100, "en");
        String otherFunction = insertPrecedent(other, "HOW_TO", system, "f2", 10, "ja");
        String noFunction = insertPrecedent(other, "HOW_TO", system, null, 20, "ja");
        String differentType = insertPrecedent(other, "LOOKS_WRONG", system, "f1", 5, "ja");
        String differentSystem = insertPrecedent(other, "HOW_TO", uniqueSystem(), "f1", 5, "ja");
        String own = insertPrecedent(inquiry, "HOW_TO", system, "f1", 1, "ja");

        String detail = detail(inquiry);

        // 機能が同じものが先（新しい順）、その後に、機能が違う・決まっていないもの（新しい順）。種類やシステムが違うもの、自分の先例は含めない
        assertThat(precedentIds(detail)).containsExactly(sameNew, sameOld, otherFunction, noFunction);
        assertThat(precedentIds(detail)).doesNotContain(differentType, differentSystem, own);
        // 言語は問わない。書かれた言語のまま返す
        assertThat((List<String>) JsonPath.read(detail, "$.precedents[*].language")).containsExactly("en", "ja", "ja", "ja");
        // 持ち主向けの詳細も同じ
        assertThat(text(mvc.perform(get("/api/owner/inquiries/" + inquiry)).andExpect(status().isNotFound())))
                .contains("NOT_FOUND");
    }

    @Test
    void 先例は5件までで_システムか種類が決まっていなければ空() throws Exception {
        String system = uniqueSystem();
        String inquiry = handling("使い方を教えて");
        String other = handling("使い方を教えて");
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(insertPrecedent(other, "HOW_TO", system, "f", 100 - i, "ja"));
        }
        placeAt(inquiry, "HOW_TO", system, "f");

        // 新しい順に5件（i が大きいほど新しい）
        assertThat(precedentIds(detail(inquiry))).containsExactly(ids.get(7), ids.get(6), ids.get(5), ids.get(4), ids.get(3));

        placeAt(inquiry, "HOW_TO", null, null);
        assertThat(precedentIds(detail(inquiry))).isEmpty();
        placeAt(inquiry, null, system, "f");
        assertThat(precedentIds(detail(inquiry))).isEmpty();
    }

    @Test
    void 持ち主向けの詳細にも同じ置き場所の先例が出る() throws Exception {
        String system = uniqueSystem();
        String inquiry = handling("使い方を教えて");
        placeAt(inquiry, "HOW_TO", system, null);
        String other = handling("使い方を教えて");
        String precedent = insertPrecedent(other, "HOW_TO", system, null, 10, "ja");
        consult(inquiry).andExpect(status().isOk());

        String owner = text(mvc.perform(get("/api/owner/inquiries/" + inquiry)).andExpect(status().isOk()));

        assertThat(precedentIds(owner)).containsExactly(precedent);
        assertThat(owner).isEqualTo(detail(inquiry));
    }

    // ---- 通し: 先例ができ、次の件の案が先例に基づく ----

    @Test
    void 最初の決定が先例になり_同じ置き場所の2件目の詳細に出て_案が先例に基づき_別のシステムの件には出ない() throws Exception {
        String first = handling("求人管理画面の応募者の一覧の仕様を教えて");
        consult(first).andExpect(status().isOk());
        String decision = DECISION + "(通し " + UUID.randomUUID() + ")";
        String decided = text(ownerDecide(first, decision).andExpect(status().isOk()));
        String precedentId = JsonPath.read(decided, "$.decisions[0].id");

        String second = handling("求人管理画面の応募者の一覧の仕様を教えて");
        String detail = detail(second);
        String billing = handling("請求管理の期限の仕様を教えて");

        assertThat(precedentIds(detail).get(0)).isEqualTo(precedentId);
        assertThat((String) JsonPath.read(detail, "$.precedents[0].decision")).isEqualTo(decision);
        // 案が、先例に基づく
        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(detail, "$.advice.judgment.text")).contains(decision);
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents[*].id")).containsExactly(precedentId);
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents[*].decision")).containsExactly(decision);
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents[*].decidedBy")).containsExactly("決めた人");
        assertThat((List<Object>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents[*].decidedAt")).hasSize(1);
        // 事実にも基づく
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOn")).hasSize(1);
        // 別のシステムの件には出ない
        assertThat(precedentIds(detail(billing))).doesNotContain(precedentId);
        assertThat((String) JsonPath.read(detail(billing), "$.advice.judgment.text")).doesNotContain(decision);
        // 1件目自身の詳細には、自分の先例は出ない（decisions にある）
        assertThat(precedentIds(detail(first))).doesNotContain(precedentId);
        // 本人向けには、どちらも出ない
        String asker = view(second);
        assertThat(asker).doesNotContain("\"precedents\"").doesNotContain("basedOnPrecedents").doesNotContain("\"advice\"");
    }

    @Test
    void 案を作った後に新しい先例ができても作り直さない() throws Exception {
        String system = uniqueSystem();
        // 先例がない間に、案ができる（置き場所が決まっていないので、先例は引かれない）
        String inquiry = handling("使い方を教えて");
        assertThat((String) JsonPath.read(detail(inquiry), "$.advice.status")).isEqualTo("DONE");
        assertThat((List<Object>) JsonPath.read(detail(inquiry), "$.advice.judgment.basedOnPrecedents")).isEmpty();
        long advisorCalls = usageSteps(inquiry).stream().filter("advisor"::equals).count();
        String other = handling("使い方を教えて");

        insertPrecedent(other, "HOW_TO", system, null, 1, "ja");
        placeAt(inquiry, "HOW_TO", system, null);

        // 新しい先例ができて、同じ置き場所の先例として出ても、案は作り直さない
        assertThat(precedentIds(detail(inquiry))).hasSize(1);
        assertThat(usageSteps(inquiry).stream().filter("advisor"::equals).count()).isEqualTo(advisorCalls);
        assertThat((List<Object>) JsonPath.read(detail(inquiry), "$.advice.judgment.basedOnPrecedents")).isEmpty();
    }

    @Test
    void 調べない種類は先例があれば案ができ_なければNOT_RUN() throws Exception {
        // 先例がない（システムが決まっていない）件は、材料がないので案を作らない
        String without = toStructured("要望です", null);
        confirm(without).andExpect(status().isOk());
        awaitInvestigationIdle(without);
        assertThat((String) JsonPath.read(detail(without), "$.advice.status")).isEqualTo("NOT_RUN");
        assertThat(usageSteps(without)).doesNotContain("advisor");

        // 先例がある置き場所の件は、事実がなくても案ができる
        String other = handling("使い方を教えて");
        String precedent = insertPrecedent(other, "CHANGE_REQUEST", "reports", null, 1, "ja");
        String with = toStructured("要望です レポート", null);
        assertThat((String) JsonPath.read(view(with), "$.system.id")).isEqualTo("reports");
        confirm(with).andExpect(status().isOk());
        awaitInvestigationIdle(with);

        String detail = detail(with);
        assertThat((String) JsonPath.read(detail, "$.type.id")).isEqualTo("CHANGE_REQUEST");
        assertThat((String) JsonPath.read(detail, "$.investigation.status")).isEqualTo("NOT_RUN");
        assertThat((String) JsonPath.read(detail, "$.advice.status")).isEqualTo("DONE");
        assertThat((List<Object>) JsonPath.read(detail, "$.advice.judgment.basedOn")).isEmpty();
        assertThat((List<String>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents[*].id")).contains(precedent);
        assertThat((Object) JsonPath.read(detail, "$.advice.procedure")).isNotNull();
        assertThat(precedentIds(detail)).contains(precedent);
    }

    @Test
    void 先例に対応する前に保存した案も読める() throws Exception {
        String id = handling("使い方を教えて");
        jdbc.update(
                "update inquiries set advice_status = 'DONE', advice = ?::jsonb where id = ?::uuid",
                "{\"judgment\":{\"text\":\"古い案\",\"needsConsultation\":false,\"consultationReason\":null,\"basedOn\":[\"事実\"]},"
                        + "\"procedure\":{\"steps\":[{\"target\":\"a\",\"action\":\"b\",\"check\":\"c\"}],\"rollback\":null,\"basedOn\":[\"事実\"]}}",
                id);

        String detail = detail(id);

        assertThat((String) JsonPath.read(detail, "$.advice.judgment.text")).isEqualTo("古い案");
        assertThat((List<Object>) JsonPath.read(detail, "$.advice.judgment.basedOnPrecedents")).isEmpty();
        assertThat((List<Object>) JsonPath.read(detail, "$.advice.procedure.basedOnPrecedents")).isEmpty();
    }

    // ---- 下書き ----

    @Test
    void 下書きの結果は_noteがあればnote_なければ決定の内容を使い_決定は最後のもの() throws Exception {
        String id = handling("使い方を教えて");
        consult(id).andExpect(status().isOk());
        ownerDecide(id, "最初の決定").andExpect(status().isOk());
        consult(id).andExpect(status().isOk());
        ownerDecide(id, "最後の決定").andExpect(status().isOk());
        String lead = "お待たせしました。確認した結果をお伝えします。";

        mvc.perform(post("/api/operator/inquiries/" + id + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(lead + "最後の決定"));
        mvc.perform(post("/api/operator/inquiries/" + id + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT", "note", "メモを先に")))
                .andExpect(jsonPath("$.body").value(lead + "メモを先に"));
        // 決定がない件は、これまでどおり判断の案
        String none = handling("使い方を教えて");
        mvc.perform(post("/api/operator/inquiries/" + none + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT")))
                .andExpect(jsonPath("$.body").value(lead + "資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。"));
    }

    // ---- 担当者が決定を記録する ----

    // ---- 先例の API ----

    @Test
    void 一覧は置き場所で絞れ_新しい順() throws Exception {
        String system = uniqueSystem();
        String other = handling("使い方を教えて");
        String a = insertPrecedent(other, "HOW_TO", system, "f1", 300, "ja");
        String b = insertPrecedent(other, "LOOKS_WRONG", system, "f1", 200, "ja");
        String c = insertPrecedent(other, "HOW_TO", system, "f2", 100, "ja");
        String d = insertPrecedent(other, "HOW_TO", uniqueSystem(), "f1", 50, "ja");

        assertThat(listIds("?systemId=" + system)).containsExactly(c, b, a);
        assertThat(listIds("?systemId=" + system + "&functionId=f1")).containsExactly(b, a);
        assertThat(listIds("?systemId=" + system + "&type=HOW_TO")).containsExactly(c, a);
        assertThat(listIds("?systemId=" + system + "&functionId=f1&type=HOW_TO")).containsExactly(a);
        assertThat(listIds("?systemId=zzz-none")).isEmpty();
        assertThat(listIds("?type=HOW_TO")).contains(a, c, d);
        // 種類の誤りは VALIDATION
        mvc.perform(get("/api/precedents?type=NOPE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void 一覧は最大100件() throws Exception {
        String system = uniqueSystem();
        String other = handling("使い方を教えて");
        for (int i = 0; i < 105; i++) {
            insertPrecedent(other, "HOW_TO", system, null, 1000 + i, "ja");
        }

        List<String> ids = listIds("?systemId=" + system);

        assertThat(ids).hasSize(100);
    }

    @Test
    void 一覧の名前は見る人の言語で返る() throws Exception {
        String other = handling("使い方を教えて");
        String id = insertPrecedent(other, "HOW_TO", "job-admin", "posting", 1, "ja");

        String ja = text(mvc.perform(get("/api/precedents?systemId=job-admin&functionId=posting&type=HOW_TO")).andExpect(status().isOk()));
        String en = text(mvc.perform(get("/api/precedents?systemId=job-admin&functionId=posting&type=HOW_TO")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk()));

        assertThat((List<String>) JsonPath.read(ja, "$.items[?(@.id=='" + id + "')].system.label")).containsExactly("求人管理画面");
        assertThat((List<String>) JsonPath.read(en, "$.items[?(@.id=='" + id + "')].system.label")).containsExactly("Job admin");
        assertThat((List<String>) JsonPath.read(en, "$.items[?(@.id=='" + id + "')].function.label")).containsExactly("Posting");
    }

    private List<String> listIds(String query) throws Exception {
        String body = text(mvc.perform(get("/api/precedents" + query)).andExpect(status().isOk()));
        return JsonPath.read(body, "$.items[*].id");
    }
}
