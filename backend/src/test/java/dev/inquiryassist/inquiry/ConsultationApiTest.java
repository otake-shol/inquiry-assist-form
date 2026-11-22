package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 仕様の持ち主への相談と、決定の記録（契約書「仕様の持ち主への相談」）を API から確かめる。相談は担当者が始め、持ち主の決定が
 * 先例として残る。テスト用の設定の相談先は、全体の1つの一覧。
 */
class ConsultationApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";
    private static final String QUESTION = "期限ちょうどの時刻の扱いは、意図した仕様でしょうか。";
    private static final String OWNER = "仕様の持ち主（テスト）";

    @Autowired
    TimeRuleService rules;

    // ---- 補助 ----

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private ResultActions consult(String id, String body) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/consultations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions decide(String id, String body) throws Exception {
        return mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String decisionBody() {
        return json(
                "decisionKind", "AS_INTENDED",
                "decision", "期限ちょうどの時刻から期限切れとして扱うのは、意図した仕様です。",
                "reason", "締め切りの時刻を過ぎた応募を受け付けないため。",
                "decidedBy", OWNER);
    }

    private ResultActions cancel(String id) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/consultations/cancel"));
    }

    /** 担当者に渡って、担当者の番の件を作る。 */
    private String handling() throws Exception {
        return filedNow(HOW_TO, null);
    }

    private String consulting() throws Exception {
        String id = handling();
        consult(id, json("question", QUESTION)).andExpect(status().isOk());
        return id;
    }

    private List<String> ownerListIds() throws Exception {
        String body = text(mvc.perform(get("/api/owner/consultations")).andExpect(status().isOk()));
        return JsonPath.read(body, "$.items[*].inquiryId");
    }

    private static List<String> events(String detail) {
        return JsonPath.read(detail, "$.history[*].event");
    }

    // ---- 相談先の引き継ぎ ----

    @Test
    void 既定の相談先は置き場所によらず全体の一覧() throws Exception {
        String system = filedNow("求人管理画面の応募者の一覧の仕様を教えて", null);
        String other = filedNow("請求管理の期限の仕様を教えて", null);

        assertThat((List<String>) JsonPath.read(detail(system), "$.defaultConsultants")).containsExactly("全体の仕様の持ち主");
        assertThat((List<String>) JsonPath.read(detail(other), "$.defaultConsultants")).containsExactly("全体の仕様の持ち主");
    }

    @Test
    void 相談先を省くと既定の相談先_替えれば替えた相談先() throws Exception {
        String byDefault = filedNow("求人管理画面の応募者の一覧の仕様を教えて", null);
        String changed = filedNow("求人管理画面の応募者の一覧の仕様を教えて", null);

        consult(byDefault, json("question", QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].to[0]").value("全体の仕様の持ち主"))
                .andExpect(jsonPath("$.consultations[0].to.length()").value(1));
        consult(changed, "{\"question\":\"" + QUESTION + "\",\"to\":[\"  別の人  \",\"別の人\",\"もう一人\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].to[0]").value("別の人"))
                .andExpect(jsonPath("$.consultations[0].to[1]").value("もう一人"))
                .andExpect(jsonPath("$.consultations[0].to.length()").value(2));
        // 空の一覧も、省いたのと同じ
        String empty = filedNow("請求管理の期限の仕様を教えて", null);
        consult(empty, "{\"question\":\"" + QUESTION + "\",\"to\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].to[0]").value("全体の仕様の持ち主"));
    }

    @Test
    void 相談先が6人以上や名前が不正や相談の文章が不正ならVALIDATION() throws Exception {
        String id = handling();

        consult(id, "{\"question\":\"相談\",\"to\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        consult(id, "{\"question\":\"相談\",\"to\":[\"a\",\"  \"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        consult(id, "{\"question\":\"相談\",\"to\":[\"" + "あ".repeat(101) + "\"]}")
                .andExpect(status().isBadRequest());
        consult(id, json("question", "   ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        consult(id, json("question", "あ".repeat(4001))).andExpect(status().isBadRequest());
        consult(id, "{\"to\":[\"a\"]}").andExpect(status().isBadRequest());
        // 5人ちょうど、4000文字ちょうどは受け付ける
        consult(id, "{\"question\":\"" + "あ".repeat(4000) + "\",\"to\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}").andExpect(status().isOk());
        // 失敗した入力では、状態は変わっていない（最後の相談だけが入った）
        assertThat((List<Object>) JsonPath.read(detail(id), "$.consultations")).hasSize(1);
    }

    // ---- 相談から決定まで ----

    @Test
    void 担当者が相談すると持ち主の番になり_持ち主が決めると先例ができて担当者の番に戻る() throws Exception {
        String id = handling();
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        String reasonBefore = JsonPath.read(detail(id), "$.handoverReason");
        String phaseBefore = JsonPath.read(detail(id), "$.phase");

        // 相談する
        String consulted = text(consult(id, json("question", QUESTION)).andExpect(status().isOk()));

        assertThat((String) JsonPath.read(consulted, "$.turn")).isEqualTo("OWNER");
        assertThat((String) JsonPath.read(consulted, "$.awaiting")).isEqualTo("CONSULTATION");
        assertThat((String) JsonPath.read(consulted, "$.phase")).isEqualTo(phaseBefore);
        assertThat((String) JsonPath.read(consulted, "$.handoverReason")).isEqualTo(reasonBefore);
        assertThat((String) JsonPath.read(consulted, "$.consultations[0].status")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(consulted, "$.consultations[0].question")).isEqualTo(QUESTION);
        assertThat((String) JsonPath.read(consulted, "$.consultations[0].askedBy")).isEqualTo("担当者（開発用）");
        assertThat((Object) JsonPath.read(consulted, "$.consultations[0].closedAt")).isNull();
        assertThat((Object) JsonPath.read(consulted, "$.consultations[0].precedentId")).isNull();
        assertThat((List<Object>) JsonPath.read(consulted, "$.decisions")).isEmpty();
        assertThat(events(consulted)).endsWith("CONSULTED");
        assertThat((List<String>) JsonPath.read(consulted, "$.history[-1:].actor")).containsExactly("OPERATOR");

        // 持ち主の一覧に出る
        String list = text(mvc.perform(get("/api/owner/consultations")).andExpect(status().isOk()));
        List<Object> mine = JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')]");
        assertThat(mine).hasSize(1);
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].question"))
                .containsExactly(QUESTION);
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].consultationId"))
                .containsExactly((String) JsonPath.read(consulted, "$.consultations[0].id"));
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].type.id"))
                .containsExactly("HOW_TO");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].urgency"))
                .containsExactly("NORMAL");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].language"))
                .containsExactly("ja");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.inquiryId=='" + id + "')].summary")).singleElement().asString().isNotBlank();

        // 持ち主の詳細は、担当者向けの詳細と同じ形で読める。担当者の側の既読は変えない
        String owner = text(mvc.perform(get("/api/owner/inquiries/" + id)).andExpect(status().isOk()));
        assertThat(owner).isEqualTo(detail(id));
        assertThat((Boolean) JsonPath.read(owner, "$.read")).isTrue();

        // 決める
        String decided = text(decide(id, decisionBody()).andExpect(status().isOk()));

        assertThat((String) JsonPath.read(decided, "$.turn")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(decided, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(decided, "$.phase")).isEqualTo(phaseBefore);
        assertThat((String) JsonPath.read(decided, "$.handoverReason")).isEqualTo("DECIDED");
        assertThat((Boolean) JsonPath.read(decided, "$.read")).isFalse();
        assertThat((String) JsonPath.read(decided, "$.consultations[0].status")).isEqualTo("DECIDED");
        assertThat((Object) JsonPath.read(decided, "$.consultations[0].closedAt")).isNotNull();
        String precedentId = JsonPath.read(decided, "$.consultations[0].precedentId");
        assertThat((List<String>) JsonPath.read(decided, "$.decisions[*].id")).containsExactly(precedentId);
        assertThat((String) JsonPath.read(decided, "$.decisions[0].inquiryId")).isEqualTo(id);
        assertThat((String) JsonPath.read(decided, "$.decisions[0].type.id")).isEqualTo("HOW_TO");
        assertThat((String) JsonPath.read(decided, "$.decisions[0].decisionKind")).isEqualTo("AS_INTENDED");
        assertThat((String) JsonPath.read(decided, "$.decisions[0].decision")).startsWith("期限ちょうどの時刻から");
        assertThat((String) JsonPath.read(decided, "$.decisions[0].reason")).startsWith("締め切りの時刻");
        assertThat((String) JsonPath.read(decided, "$.decisions[0].decidedBy")).isEqualTo(OWNER);
        assertThat(decided).doesNotContain("recordedBy").doesNotContain("\"confirmed\"");
        assertThat((String) JsonPath.read(decided, "$.decisions[0].language")).isEqualTo("ja");
        assertThat((Object) JsonPath.read(decided, "$.decisions[0].decidedAt")).isNotNull();
        assertThat(events(decided)).endsWith("CONSULTED", "DECIDED");
        assertThat((List<String>) JsonPath.read(decided, "$.history[-2:].actor")).containsExactly("OPERATOR", "OWNER");
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id))
                .isEqualTo(1);
        // 持ち主の一覧から消える
        assertThat(ownerListIds()).doesNotContain(id);
        // 決めた後の担当者は、これまでどおり操作できる
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT", "body", "決まりました")))
                .andExpect(status().isOk());
    }

    @Test
    void 持ち主の一覧は長く待たせている順() throws Exception {
        String first = consulting();
        String second = consulting();
        // 1つ目を後から相談したことにする
        jdbc.update(
                "update inquiries set consultations = jsonb_set(consultations, '{0,askedAt}', to_jsonb(?::text)) where id = ?::uuid",
                "2020-01-01T00:00:00Z",
                second);

        List<String> ids = ownerListIds();

        assertThat(ids).contains(first, second);
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first));
    }

    @Test
    void 状況を省くと言い直し_本人の文章の先頭_相談の文章の順で入る() throws Exception {
        // 言い直しがある
        String withSummary = consulting();
        String summary = JsonPath.read(detail(withSummary), "$.structured.summary");
        decide(withSummary, decisionBody()).andExpect(status().isOk());
        assertThat(situationOf(withSummary)).isEqualTo(summary);

        // 言い直しがなければ、本人が書いた文章の先頭200文字
        String longText = "あ".repeat(300);
        String noStructure = idOf(create("[言い直しエラー] " + longText));
        unknown(noStructure, "system");
        unknown(noStructure, "q1");
        sendNow(noStructure).andExpect(status().isOk());
        consult(noStructure, json("question", QUESTION)).andExpect(status().isOk());
        decide(noStructure, decisionBody()).andExpect(status().isOk());
        assertThat(situationOf(noStructure)).isEqualTo(("[言い直しエラー] " + longText).substring(0, 200));

        // それもなければ、相談の文章
        String nothing = consulting();
        jdbc.update(
                "update inquiries set structured_by_ai = null, structured_confirmed = null, texts = '[]'::jsonb, intake = '[]'::jsonb"
                        + " where id = ?::uuid",
                nothing);
        decide(nothing, decisionBody()).andExpect(status().isOk());
        assertThat(situationOf(nothing)).isEqualTo(QUESTION);

        // 書けば、書いたとおり（前後の空白は除く）
        String explicit = consulting();
        decide(explicit, json(
                        "decisionKind", "DEFECT", "decision", "d", "reason", "r", "decidedBy", OWNER, "situation", "  この状況です  "))
                .andExpect(status().isOk());
        assertThat(situationOf(explicit)).isEqualTo("この状況です");
    }

    private String situationOf(String id) {
        return jdbc.queryForObject("select situation from precedents where inquiry_id = ?::uuid", String.class, id);
    }

    @Test
    void 決定の入力の誤りはVALIDATION() throws Exception {
        String id = consulting();
        String ok = decisionBody();

        decide(id, json("decisionKind", "MAYBE", "decision", "d", "reason", "r", "decidedBy", OWNER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        decide(id, json("decision", "d", "reason", "r", "decidedBy", OWNER)).andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "  ", "reason", "r", "decidedBy", OWNER))
                .andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "", "decidedBy", OWNER))
                .andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", " "))
                .andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", "あ".repeat(101)))
                .andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "あ".repeat(4001), "reason", "r", "decidedBy", OWNER))
                .andExpect(status().isBadRequest());
        decide(id, json("decisionKind", "ACCEPT", "decision", "d", "reason", "r", "decidedBy", OWNER, "situation", "  "))
                .andExpect(status().isBadRequest());
        // 失敗した入力では、何も変わらない（先例もできない）
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id)).isZero();
        assertThat((String) JsonPath.read(detail(id), "$.awaiting")).isEqualTo("CONSULTATION");
        // 決定の種類は7つとも受け付ける
        for (String kind : List.of("AS_INTENDED", "DEFECT", "ACCEPT", "REJECT", "ADOPT", "DEFER", "OTHER")) {
            String each = consulting();
            decide(each, json("decisionKind", kind, "decision", "d", "reason", "r", "decidedBy", OWNER))
                    .andExpect(status().isOk());
        }
        assertThat(ok).contains("AS_INTENDED");
    }

    @Test
    void 相談していない件と存在しない件_決定を待っていない件() throws Exception {
        String neverConsulted = handling();
        String unknownId = "00000000-0000-0000-0000-000000000000";

        mvc.perform(get("/api/owner/inquiries/" + neverConsulted)).andExpect(status().isNotFound());
        mvc.perform(get("/api/owner/inquiries/" + unknownId)).andExpect(status().isNotFound());
        decide(neverConsulted, decisionBody()).andExpect(status().isNotFound());
        decide(unknownId, decisionBody()).andExpect(status().isNotFound());
        // 相談したことはあるが、決定を待っていない
        String id = consulting();
        decide(id, decisionBody()).andExpect(status().isOk());
        decide(id, decisionBody()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        // 相談したことのある件は、決まった後も持ち主が読める
        mvc.perform(get("/api/owner/inquiries/" + id)).andExpect(status().isOk());
        // 担当者に渡したことのない件への相談は NOT_FOUND
        consult(idOf(start()), json("question", QUESTION)).andExpect(status().isNotFound());
    }

    // ---- 相談の取り下げ ----

    @Test
    void 相談を取り下げると担当者の番に戻り_理由は変わらず_もう一度相談できる() throws Exception {
        String id = handling();
        String reason = JsonPath.read(detail(id), "$.handoverReason");
        consult(id, json("question", QUESTION)).andExpect(status().isOk());
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());

        String canceled = text(cancel(id).andExpect(status().isOk()));

        assertThat((String) JsonPath.read(canceled, "$.turn")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(canceled, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(canceled, "$.handoverReason")).isEqualTo(reason);
        assertThat((String) JsonPath.read(canceled, "$.consultations[0].status")).isEqualTo("CANCELED");
        assertThat((Object) JsonPath.read(canceled, "$.consultations[0].closedAt")).isNotNull();
        assertThat((Object) JsonPath.read(canceled, "$.consultations[0].precedentId")).isNull();
        assertThat((List<Object>) JsonPath.read(canceled, "$.decisions")).isEmpty();
        assertThat(events(canceled)).endsWith("CONSULTED", "CONSULTATION_CANCELED");
        assertThat(ownerListIds()).doesNotContain(id);
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id)).isZero();

        // もう一度相談できる（相談が2件になる）
        String again = text(consult(id, json("question", "もう一度確認です")).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(again, "$.consultations[*].status")).containsExactly("CANCELED", "OPEN");
        // 相談している間に、相談は重ねられない。取り下げの後でないと取り下げられない
        consult(id, json("question", "重ねる")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        cancel(id).andExpect(status().isOk());
        cancel(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        // 取り下げた相談には決定できない
        decide(id, decisionBody()).andExpect(status().isConflict());
    }

    // ---- 相談している間のほかの操作 ----

    @Test
    void 相談している間は聞き返す_結果_見送る_下書きができず_置き直す_訳はできる() throws Exception {
        String id = consulting();

        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "詳しく")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "RESULT", "body", "結果")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/operator/inquiries/" + id + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reasonKind", "OUT_OF_SCOPE", "reason", "対象外")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/operator/inquiries/" + id + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat((String) JsonPath.read(detail(id), "$.awaiting")).isEqualTo("CONSULTATION");

        // 置き直す。番も待っていることも変わらない
        mvc.perform(put("/api/operator/inquiries/" + id + "/placement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOOKS_WRONG\",\"systemId\":\"billing\",\"functionId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn").value("OWNER"))
                .andExpect(jsonPath("$.awaiting").value("CONSULTATION"));
        // 訳す
        jdbc.update("update inquiries set language = 'en' where id = ?::uuid", id);
        mvc.perform(post("/api/operator/inquiries/" + id + "/translation").header("Accept-Language", "ja"))
                .andExpect(status().isOk());
    }

    @Test
    void 相談している間の本人の追記は番を変えず担当者側を未読に戻し_取り下げると相談はCANCELED() throws Exception {
        String id = consulting();
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        int eventsBefore = events(detail(id)).size();

        mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", "補足です")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn").value("OWNER"))
                .andExpect(jsonPath("$.awaiting").value("CONSULTATION"));

        String after = detail(id);
        assertThat((Boolean) JsonPath.read(after, "$.read")).isFalse();
        assertThat((String) JsonPath.read(after, "$.awaiting")).isEqualTo("CONSULTATION");
        assertThat(events(after)).hasSize(eventsBefore);
        assertThat((List<String>) JsonPath.read(after, "$.messages[*].body")).contains("補足です");
        assertThat((String) JsonPath.read(after, "$.consultations[0].status")).isEqualTo("OPEN");

        // 本人が取り下げると、終わり、相談は取り下げになる
        mvc.perform(post("/api/inquiries/" + id + "/withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ending").value("WITHDRAWN"));
        String closed = detail(id);
        assertThat((String) JsonPath.read(closed, "$.consultations[0].status")).isEqualTo("CANCELED");
        assertThat((Object) JsonPath.read(closed, "$.consultations[0].closedAt")).isNotNull();
        assertThat((String) JsonPath.read(closed, "$.closedBy")).isEqualTo("ASKER");
        assertThat(ownerListIds()).doesNotContain(id);
    }

    @Test
    void 返事がないときの決まりの対象にならない() throws Exception {
        String id = consulting();
        Instant origin = Instant.parse("2026-01-05T03:00:00Z");
        jdbc.update("update inquiries set awaiting_since = ? where id = ?::uuid", Timestamp.from(origin), id);

        TimeRuleService.Result result = rules.run(origin.plusSeconds(86400L * 200));

        assertThat(result).isEqualTo(new TimeRuleService.Result(0, 0));
        assertThat((String) JsonPath.read(detail(id), "$.awaiting")).isEqualTo("CONSULTATION");
        assertThat(row(id).get("reminded_at")).isNull();
        // 後始末（ほかのテストの判定に巻き込まれないよう、起点をいまに戻す）
        jdbc.update("update inquiries set awaiting_since = now() where id = ?::uuid", id);
    }

    // ---- 本人向けの表示 ----

    @Test
    void 本人には相談も先例も既定の相談先も見えず_持ち主の番であることだけが見える() throws Exception {
        String id = consulting();

        String asker = view(id);

        assertThat((String) JsonPath.read(asker, "$.turn")).isEqualTo("OWNER");
        assertThat((String) JsonPath.read(asker, "$.awaiting")).isEqualTo("CONSULTATION");
        assertThat(asker).doesNotContain("\"consultations\"").doesNotContain("\"decisions\"").doesNotContain("\"defaultConsultants\"");
        assertThat(asker).doesNotContain(QUESTION);
        String list = text(mvc.perform(get("/api/inquiries")).andExpect(status().isOk()));
        assertThat((List<Object>) JsonPath.read(list, "$.items[?(@.id=='" + id + "')].turn")).containsExactly("OWNER");
        assertThat((List<Object>) JsonPath.read(list, "$.items[?(@.id=='" + id + "')].awaiting")).containsExactly("CONSULTATION");
        // 決まった後も、本人には先例が見えない
        decide(id, decisionBody()).andExpect(status().isOk());
        assertThat(view(id)).doesNotContain("\"decisions\"").doesNotContain("期限ちょうどの時刻から");
    }

    // ---- 先例 ----

    @Test
    void 先例のマスは後から置き直しても変わらず_名前は見る人の言語で返り_設定にない識別子は識別子のまま() throws Exception {
        String id = filedNow("求人管理画面の求人の掲載の使い方を教えて", null);
        consult(id, json("question", QUESTION)).andExpect(status().isOk());
        MvcResult decided = decideAs(id, "en");
        String body = decided.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 決めたときの見る人の言語が、先例の language。名前もその言語
        assertThat((String) JsonPath.read(body, "$.decisions[0].language")).isEqualTo("en");
        assertThat((String) JsonPath.read(body, "$.decisions[0].type.label")).isEqualTo("How to use something");
        assertThat((String) JsonPath.read(body, "$.decisions[0].system.id")).isEqualTo("job-admin");
        assertThat((String) JsonPath.read(body, "$.decisions[0].system.label")).isEqualTo("Job admin");
        assertThat((String) JsonPath.read(body, "$.decisions[0].function.label")).isEqualTo("Posting");
        // 同じ先例を日本語で見れば、名前は日本語（language は決めたときのまま）
        String ja = detail(id);
        assertThat((String) JsonPath.read(ja, "$.decisions[0].system.label")).isEqualTo("求人管理画面");
        assertThat((String) JsonPath.read(ja, "$.decisions[0].language")).isEqualTo("en");
        // 書かれた内容は、訳さず、書かれたまま
        assertThat((String) JsonPath.read(ja, "$.decisions[0].decision")).startsWith("期限ちょうどの時刻から");

        // 後から問い合わせを置き直しても、先例のマスは変わらない
        mvc.perform(put("/api/operator/inquiries/" + id + "/placement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DO_FOR_ME\",\"systemId\":\"billing\",\"functionId\":\"invoice\"}"))
                .andExpect(status().isOk());
        Map<String, Object> precedent = jdbc.queryForMap("select * from precedents where inquiry_id = ?::uuid", id);
        assertThat(precedent.get("type")).isEqualTo("HOW_TO");
        assertThat(precedent.get("system_id")).isEqualTo("job-admin");
        assertThat(precedent.get("function_id")).isEqualTo("posting");
        assertThat((String) JsonPath.read(detail(id), "$.system.id")).isEqualTo("billing");
        assertThat((String) JsonPath.read(detail(id), "$.decisions[0].system.id")).isEqualTo("job-admin");

        // 設定から消えたシステムや機能は、識別子のまま
        jdbc.update("update precedents set system_id = 'gone', function_id = 'gone-fn' where inquiry_id = ?::uuid", id);
        String gone = detail(id);
        assertThat((String) JsonPath.read(gone, "$.decisions[0].system.label")).isEqualTo("gone");
        assertThat((String) JsonPath.read(gone, "$.decisions[0].function.label")).isEqualTo("gone-fn");
        // マスがどれも決まっていない先例も、返せる
        jdbc.update("update precedents set type = null, system_id = null, function_id = null where inquiry_id = ?::uuid", id);
        String none = detail(id);
        assertThat((Object) JsonPath.read(none, "$.decisions[0].type")).isNull();
        assertThat((Object) JsonPath.read(none, "$.decisions[0].system")).isNull();
        assertThat((Object) JsonPath.read(none, "$.decisions[0].function")).isNull();
    }

    private MvcResult decideAs(String id, String language) throws Exception {
        return mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                        .header("Accept-Language", language)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody()))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 先例は問い合わせが終わっても残り_複数の相談から複数できる() throws Exception {
        String id = handling();
        consult(id, json("question", "1回目")).andExpect(status().isOk());
        decide(id, decisionBody()).andExpect(status().isOk());
        consult(id, json("question", "2回目")).andExpect(status().isOk());
        decide(id, json("decisionKind", "DEFECT", "decision", "不具合です", "reason", "仕様と違う", "decidedBy", OWNER))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + id + "/withdraw")).andExpect(status().isOk());

        String detail = detail(id);

        assertThat((List<String>) JsonPath.read(detail, "$.decisions[*].decisionKind")).containsExactly("AS_INTENDED", "DEFECT");
        assertThat((List<String>) JsonPath.read(detail, "$.consultations[*].status")).containsExactly("DECIDED", "DECIDED");
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
        assertThat((List<String>) JsonPath.read(detail, "$.consultations[*].precedentId"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(detail, "$.decisions[*].id"));
    }

    // ---- 担当者のストリーム ----

    @Test
    void 相談_決定_取り下げで担当者のストリームにupdatedが流れる() throws Exception {
        String id = handling();
        MvcResult stream = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        consult(id, json("question", QUESTION)).andExpect(status().isOk());
        assertThat(waitFor(() -> updatedAwaiting(stream, id).contains("CONSULTATION"), 5000)).isTrue();
        decide(id, decisionBody()).andExpect(status().isOk());
        assertThat(waitFor(() -> updatedAwaiting(stream, id).contains("HANDLING"), 5000)).isTrue();
        consult(id, json("question", "もう一度")).andExpect(status().isOk());
        cancel(id).andExpect(status().isOk());
        assertThat(waitFor(() -> updatedAwaiting(stream, id).stream().filter("HANDLING"::equals).count() >= 2, 5000))
                .isTrue();
    }

    /** ストリームに流れた、この問い合わせの updated の awaiting を、流れた順に返す。 */
    private static List<String> updatedAwaiting(MvcResult stream, String id) {
        String content;
        try {
            content = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        List<String> awaiting = new ArrayList<>();
        String name = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && "updated".equals(name)) {
                String data = line.substring("data:".length()).trim();
                if (id.equals(JsonPath.read(data, "$.id"))) {
                    awaiting.add(JsonPath.read(data, "$.awaiting"));
                }
                name = null;
            }
        }
        return awaiting;
    }

    // ---- 一覧と担当者のストリームの項目の相談先 ----

    @Test
    void 一覧の項目の相談先は相談している間だけ名前が入り_取り下げと決定の後は空になる() throws Exception {
        String id = filedNow("求人管理画面の応募者の一覧の仕様を教えて", null);
        assertThat(operatorListConsultants(id)).isEmpty();
        MvcResult stream = mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 相談している間は、決定を待っている相談の to（相談先を替えれば、替えた名前）
        consult(id, "{\"question\":\"" + QUESTION + "\",\"to\":[\"相談先A\",\"相談先B\"]}").andExpect(status().isOk());
        assertThat(operatorListConsultants(id)).containsExactly("相談先A", "相談先B");
        assertThat(waitFor(() -> streamConsultants(stream, id).contains(List.of("相談先A", "相談先B")), 5000)).isTrue();

        // 取り下げの後は空
        cancel(id).andExpect(status().isOk());
        assertThat(operatorListConsultants(id)).isEmpty();
        assertThat(waitFor(() -> lastOf(streamConsultants(stream, id)).isEmpty(), 5000)).isTrue();

        // もう一度相談して、決定の後も空（既定の相談先で相談する）
        consult(id, json("question", "もう一度")).andExpect(status().isOk());
        assertThat(operatorListConsultants(id)).containsExactly("全体の仕様の持ち主");
        decide(id, decisionBody()).andExpect(status().isOk());
        assertThat(operatorListConsultants(id)).isEmpty();
        assertThat(waitFor(() -> lastOf(streamConsultants(stream, id)).isEmpty(), 5000)).isTrue();
        // 一覧の項目の形は、ほかの項目と同じく、必ず配列を持つ
        String list = text(mvc.perform(get("/api/operator/inquiries")).andExpect(status().isOk()));
        assertThat((List<Object>) JsonPath.read(list, "$.items[?(@.consultants == null)]")).isEmpty();
    }

    private List<String> operatorListConsultants(String id) throws Exception {
        String list = text(mvc.perform(get("/api/operator/inquiries")).andExpect(status().isOk()));
        List<List<String>> found = JsonPath.read(list, "$.items[?(@.id=='" + id + "')].consultants");
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static List<String> lastOf(List<List<String>> history) {
        return history.isEmpty() ? List.of("（まだ流れていない）") : history.get(history.size() - 1);
    }

    /** ストリームに流れた、この問い合わせの filed と updated の consultants を、流れた順に返す。 */
    private static List<List<String>> streamConsultants(MvcResult stream, String id) {
        String content;
        try {
            content = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        List<List<String>> found = new ArrayList<>();
        String name = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && ("updated".equals(name) || "filed".equals(name))) {
                String data = line.substring("data:".length()).trim();
                if (id.equals(JsonPath.read(data, "$.id"))) {
                    found.add(JsonPath.read(data, "$.consultants"));
                }
                name = null;
            }
        }
        return found;
    }
}
