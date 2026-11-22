package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private ResultActions operatorRecord(String id, String body) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
            String inquiryId, String type, String systemId, String functionId, boolean confirmed, long secondsAgo, String language) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(secondsAgo));
        jdbc.update(
                "insert into precedents (id, inquiry_id, type, system_id, function_id, situation, decision_kind, decision, reason,"
                        + " decided_by, recorded_by, confirmed, language, decided_at, updated_at)"
                        + " values (?::uuid, ?::uuid, ?, ?, ?, '状況', 'ACCEPT', ?, '理由', '決めた人', ?, ?, ?, ?, ?)",
                id.toString(),
                inquiryId,
                type,
                systemId,
                functionId,
                "決定-" + id,
                confirmed ? "OWNER" : "OPERATOR",
                confirmed,
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
        String sameOld = insertPrecedent(other, "HOW_TO", system, "f1", true, 500, "ja");
        String sameNew = insertPrecedent(other, "HOW_TO", system, "f1", false, 100, "en");
        String otherFunction = insertPrecedent(other, "HOW_TO", system, "f2", true, 10, "ja");
        String noFunction = insertPrecedent(other, "HOW_TO", system, null, true, 20, "ja");
        String differentType = insertPrecedent(other, "LOOKS_WRONG", system, "f1", true, 5, "ja");
        String differentSystem = insertPrecedent(other, "HOW_TO", uniqueSystem(), "f1", true, 5, "ja");
        String own = insertPrecedent(inquiry, "HOW_TO", system, "f1", true, 1, "ja");

        String detail = detail(inquiry);

        // 機能が同じものが先（新しい順）、その後に、機能が違う・決まっていないもの（新しい順）。種類やシステムが違うもの、自分の先例は含めない
        assertThat(precedentIds(detail)).containsExactly(sameNew, sameOld, otherFunction, noFunction);
        assertThat(precedentIds(detail)).doesNotContain(differentType, differentSystem, own);
        // 言語は問わない。書かれた言語のまま返す
        assertThat((List<String>) JsonPath.read(detail, "$.precedents[*].language")).containsExactly("en", "ja", "ja", "ja");
        // 確かめられていない先例も含める
        assertThat((List<Boolean>) JsonPath.read(detail, "$.precedents[*].confirmed")).containsExactly(false, true, true, true);
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
            ids.add(insertPrecedent(other, "HOW_TO", system, "f", true, 100 - i, "ja"));
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
        String precedent = insertPrecedent(other, "HOW_TO", system, null, true, 10, "ja");
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
    void 案を作った後に新しい先例ができても作り直さず_調べ直すと作り直す() throws Exception {
        String system = uniqueSystem();
        String inquiry = handling("使い方を教えて");
        placeAt(inquiry, "HOW_TO", system, null);
        String other = handling("使い方を教えて");
        mvc.perform(post("/api/operator/inquiries/" + inquiry + "/investigation")).andExpect(status().isOk());
        awaitInvestigationIdle(inquiry);
        // 先例がない間の案
        assertThat((List<Object>) JsonPath.read(detail(inquiry), "$.advice.judgment.basedOnPrecedents")).isEmpty();
        long advisorCalls = usageSteps(inquiry).stream().filter("advisor"::equals).count();

        String precedent = insertPrecedent(other, "HOW_TO", system, null, true, 1, "ja");

        // 作り直さない
        assertThat(usageSteps(inquiry).stream().filter("advisor"::equals).count()).isEqualTo(advisorCalls);
        assertThat((List<Object>) JsonPath.read(detail(inquiry), "$.advice.judgment.basedOnPrecedents")).isEmpty();
        // 調べ直すと、その時点の先例で作り直す
        mvc.perform(post("/api/operator/inquiries/" + inquiry + "/investigation")).andExpect(status().isOk());
        awaitInvestigationIdle(inquiry);
        assertThat((List<String>) JsonPath.read(detail(inquiry), "$.advice.judgment.basedOnPrecedents[*].id"))
                .containsExactly(precedent);
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
        String precedent = insertPrecedent(other, "CHANGE_REQUEST", "reports", null, true, 1, "ja");
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

    @Test
    void 担当者の番で記録すると先例だけができ_状態も履歴も変わらない() throws Exception {
        String id = handling("使い方を教えて");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        var before = row(id);
        List<String> historyBefore = JsonPath.read(detail(id), "$.history[*].event");

        String recorded = text(operatorRecord(id, body(DECISION)).andExpect(status().isOk()));

        assertThat((List<String>) JsonPath.read(recorded, "$.decisions[*].decision")).containsExactly(DECISION);
        assertThat((String) JsonPath.read(recorded, "$.decisions[0].recordedBy")).isEqualTo("OPERATOR");
        assertThat((Boolean) JsonPath.read(recorded, "$.decisions[0].confirmed")).isFalse();
        assertThat((String) JsonPath.read(recorded, "$.decisions[0].decidedBy")).isEqualTo("決めた人");
        assertThat((String) JsonPath.read(recorded, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((List<String>) JsonPath.read(recorded, "$.history[*].event")).isEqualTo(historyBefore);
        var after = row(id);
        for (String column : List.of("phase", "turn", "awaiting", "ending", "handover_reason", "read_at", "asker_unread_reply", "history")) {
            assertThat(String.valueOf(after.get(column))).as(column).isEqualTo(String.valueOf(before.get(column)));
        }
        assertThat((List<Object>) JsonPath.read(recorded, "$.consultations")).isEmpty();
    }

    @Test
    void 相談している間に記録すると相談がDECIDEDになり担当者の番に戻り_履歴の起こした人は担当者で_未読に戻さない() throws Exception {
        String id = handling("使い方を教えて");
        String reasonBefore = JsonPath.read(detail(id), "$.handoverReason");
        consult(id).andExpect(status().isOk());
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());

        String recorded = text(operatorRecord(id, body(DECISION)).andExpect(status().isOk()));

        assertThat((String) JsonPath.read(recorded, "$.turn")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(recorded, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(recorded, "$.handoverReason")).isEqualTo("DECIDED");
        assertThat(reasonBefore).isNotEqualTo("DECIDED");
        assertThat((Boolean) JsonPath.read(recorded, "$.read")).isTrue();
        assertThat((String) JsonPath.read(recorded, "$.consultations[0].status")).isEqualTo("DECIDED");
        String precedentId = JsonPath.read(recorded, "$.decisions[0].id");
        assertThat((String) JsonPath.read(recorded, "$.consultations[0].precedentId")).isEqualTo(precedentId);
        assertThat((List<String>) JsonPath.read(recorded, "$.history[-2:].event")).containsExactly("CONSULTED", "DECIDED");
        assertThat((List<String>) JsonPath.read(recorded, "$.history[-2:].actor")).containsExactly("OPERATOR", "OPERATOR");
        assertThat((String) JsonPath.read(recorded, "$.decisions[0].recordedBy")).isEqualTo("OPERATOR");
        assertThat((Boolean) JsonPath.read(recorded, "$.decisions[0].confirmed")).isFalse();
        // 持ち主の一覧から消える
        String list = text(mvc.perform(get("/api/owner/consultations")).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(list, "$.items[*].inquiryId")).doesNotContain(id);
    }

    @Test
    void ほかの状態では記録できず_入力の誤りはVALIDATION() throws Exception {
        String asked = handling("使い方を教えて");
        mvc.perform(post("/api/operator/inquiries/" + asked + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "詳しく")))
                .andExpect(status().isOk());
        operatorRecord(asked, body(DECISION)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        String declined = handling("使い方を教えて");
        mvc.perform(post("/api/operator/inquiries/" + declined + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reasonKind", "OUT_OF_SCOPE", "reason", "対象外")))
                .andExpect(status().isOk());
        operatorRecord(declined, body(DECISION)).andExpect(status().isConflict());
        operatorRecord(idOf(start()), body(DECISION)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, asked)).isZero();

        String id = handling("使い方を教えて");
        operatorRecord(id, json("decisionKind", "MAYBE", "decision", "d", "reason", "r", "decidedBy", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        operatorRecord(id, json("decisionKind", "ACCEPT", "decision", " ", "reason", "r", "decidedBy", "x")).andExpect(status().isBadRequest());
        operatorRecord(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "", "decidedBy", "x")).andExpect(status().isBadRequest());
        operatorRecord(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "")).andExpect(status().isBadRequest());
        operatorRecord(id, json("decision", "d", "reason", "r", "decidedBy", "x")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id)).isZero();
    }

    // ---- 先例の API ----

    @Test
    void 一覧は置き場所で絞れ_確かめられていない先例だけも絞れ_新しい順() throws Exception {
        String system = uniqueSystem();
        String other = handling("使い方を教えて");
        String a = insertPrecedent(other, "HOW_TO", system, "f1", true, 300, "ja");
        String b = insertPrecedent(other, "LOOKS_WRONG", system, "f1", false, 200, "ja");
        String c = insertPrecedent(other, "HOW_TO", system, "f2", false, 100, "ja");
        String d = insertPrecedent(other, "HOW_TO", uniqueSystem(), "f1", true, 50, "ja");

        assertThat(listIds("?systemId=" + system)).containsExactly(c, b, a);
        assertThat(listIds("?systemId=" + system + "&functionId=f1")).containsExactly(b, a);
        assertThat(listIds("?systemId=" + system + "&type=HOW_TO")).containsExactly(c, a);
        assertThat(listIds("?systemId=" + system + "&functionId=f1&type=HOW_TO")).containsExactly(a);
        assertThat(listIds("?systemId=" + system + "&unconfirmedOnly=true")).containsExactly(c, b);
        assertThat(listIds("?systemId=" + system + "&type=HOW_TO&unconfirmedOnly=true")).containsExactly(c);
        assertThat(listIds("?systemId=" + system + "&unconfirmedOnly=false")).containsExactly(c, b, a);
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
            insertPrecedent(other, "HOW_TO", system, null, true, 1000 + i, "ja");
        }

        List<String> ids = listIds("?systemId=" + system);

        assertThat(ids).hasSize(100);
    }

    @Test
    void 一覧の名前は見る人の言語で返る() throws Exception {
        String other = handling("使い方を教えて");
        String id = insertPrecedent(other, "HOW_TO", "job-admin", "posting", true, 1, "ja");

        String ja = text(mvc.perform(get("/api/precedents?systemId=job-admin&functionId=posting&type=HOW_TO")).andExpect(status().isOk()));
        String en = text(mvc.perform(get("/api/precedents?systemId=job-admin&functionId=posting&type=HOW_TO")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk()));

        assertThat((List<String>) JsonPath.read(ja, "$.items[?(@.id=='" + id + "')].system.label")).containsExactly("求人管理画面");
        assertThat((List<String>) JsonPath.read(en, "$.items[?(@.id=='" + id + "')].system.label")).containsExactly("Job admin");
        assertThat((List<String>) JsonPath.read(en, "$.items[?(@.id=='" + id + "')].function.label")).containsExactly("Posting");
    }

    @Test
    void 直すと確かめられた先例になり_置き場所と元の問い合わせは変わらない() throws Exception {
        String inquiry = handling("使い方を教えて");
        String id = insertPrecedent(inquiry, "HOW_TO", "reports", "f", false, 10, "ja");
        var before = jdbc.queryForMap("select * from precedents where id = ?::uuid", id);

        String edited = text(mvc.perform(put("/api/precedents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "situation", "  直した状況  ",
                                "decisionKind", "DEFECT",
                                "decision", "直した決定",
                                "reason", "直した理由",
                                "decidedBy", "確かめた人")))
                .andExpect(status().isOk()));

        assertThat((String) JsonPath.read(edited, "$.id")).isEqualTo(id);
        assertThat((String) JsonPath.read(edited, "$.situation")).isEqualTo("直した状況");
        assertThat((String) JsonPath.read(edited, "$.decisionKind")).isEqualTo("DEFECT");
        assertThat((String) JsonPath.read(edited, "$.decision")).isEqualTo("直した決定");
        assertThat((String) JsonPath.read(edited, "$.reason")).isEqualTo("直した理由");
        assertThat((String) JsonPath.read(edited, "$.decidedBy")).isEqualTo("確かめた人");
        assertThat((Boolean) JsonPath.read(edited, "$.confirmed")).isTrue();
        // 置き場所、元の問い合わせ、記録した人、決めた時刻は変わらない
        assertThat((String) JsonPath.read(edited, "$.inquiryId")).isEqualTo(inquiry);
        assertThat((String) JsonPath.read(edited, "$.type.id")).isEqualTo("HOW_TO");
        assertThat((String) JsonPath.read(edited, "$.system.id")).isEqualTo("reports");
        assertThat((String) JsonPath.read(edited, "$.function.id")).isEqualTo("f");
        assertThat((String) JsonPath.read(edited, "$.recordedBy")).isEqualTo("OPERATOR");
        var after = jdbc.queryForMap("select * from precedents where id = ?::uuid", id);
        for (String column : List.of("inquiry_id", "type", "system_id", "function_id", "recorded_by", "language", "decided_at")) {
            assertThat(String.valueOf(after.get(column))).as(column).isEqualTo(String.valueOf(before.get(column)));
        }
        assertThat(((Timestamp) after.get("updated_at")).toInstant()).isAfter(((Timestamp) before.get("updated_at")).toInstant());
        // 一覧の「確かめられていない」から外れる
        assertThat(listIds("?systemId=reports&unconfirmedOnly=true")).doesNotContain(id);
    }

    @Test
    void 確かめたと伝えると確かめられた先例になる() throws Exception {
        String inquiry = handling("使い方を教えて");
        String id = insertPrecedent(inquiry, "HOW_TO", "reports", null, false, 10, "ja");

        mvc.perform(post("/api/precedents/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.decision").value(org.hamcrest.Matchers.startsWith("決定-")));
        // もう一度確かめても、そのまま
        mvc.perform(post("/api/precedents/" + id + "/confirm")).andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(true));
        assertThat(jdbc.queryForObject("select confirmed from precedents where id = ?::uuid", Boolean.class, id)).isTrue();
    }

    @Test
    void 存在しない先例はNOT_FOUND_入力の誤りはVALIDATION() throws Exception {
        String unknown = "00000000-0000-0000-0000-000000000000";
        String valid = json("situation", "s", "decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "x");
        mvc.perform(put("/api/precedents/" + unknown).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/precedents/" + unknown + "/confirm")).andExpect(status().isNotFound());

        String inquiry = handling("使い方を教えて");
        String id = insertPrecedent(inquiry, "HOW_TO", "reports", null, false, 10, "ja");
        for (String invalid : List.of(
                json("decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "x"),
                json("situation", " ", "decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "x"),
                json("situation", "s", "decisionKind", "MAYBE", "decision", "d", "reason", "r", "decidedBy", "x"),
                json("situation", "s", "decisionKind", "ACCEPT", "decision", "", "reason", "r", "decidedBy", "x"),
                json("situation", "s", "decisionKind", "ACCEPT", "decision", "d", "reason", "  ", "decidedBy", "x"),
                json("situation", "s", "decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", ""),
                json("situation", "s", "decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "あ".repeat(101)),
                json("situation", "あ".repeat(4001), "decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "x"))) {
            mvc.perform(put("/api/precedents/" + id).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        // 失敗した入力では、先例は変わらない
        assertThat(jdbc.queryForObject("select confirmed from precedents where id = ?::uuid", Boolean.class, id)).isFalse();
        mvc.perform(get("/api/precedents?x=1")).andExpect(status().isOk());
    }

    private List<String> listIds(String query) throws Exception {
        String body = text(mvc.perform(get("/api/precedents" + query)).andExpect(status().isOk()));
        return JsonPath.read(body, "$.items[*].id");
    }
}
