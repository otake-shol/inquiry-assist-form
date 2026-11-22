package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 担当者に渡した後の状態の遷移とやり取り（往復）を、API から確認する。DB は他のテストと共有するため、
 * 件数は前後の差、一覧は id で絞って確かめる。遷移の規則そのものは {@code InquiryStateMachineTest}。
 */
class RoundTripApiTest extends ApiSupport {

    @BeforeEach
    void clearNotices() {
        notifier.notices().clear();
    }

    /** 作って、答えを待たずに担当者に渡し（区間は KNOW）、id を返す。 */
    private String handedOver(String text) throws Exception {
        return filedNow(text, null);
    }

    /** 「おかしいかもしれない」で、AI の答えの完了と同時に担当者に渡し（区間は DECIDE）、id を返す。 */
    private String handedOverToDecide() throws Exception {
        String id = toAnswering("表示がおかしい", null);
        stream(id);
        return id;
    }

    private ResultActions operatorReply(String id, String kind, String body) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("kind", kind, "body", body)));
    }

    private ResultActions ask(String id, String body) throws Exception {
        return operatorReply(id, "ASK", body);
    }

    private ResultActions deliver(String id, String body) throws Exception {
        return operatorReply(id, "RESULT", body);
    }

    private ResultActions decline(String id, String reasonKind, String reason) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/decline")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("reasonKind", reasonKind, "reason", reason)));
    }

    private ResultActions replacePlacement(String id, String body) throws Exception {
        return mvc.perform(put("/api/operator/inquiries/" + id + "/placement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions append(String id, String body) throws Exception {
        return postJson("/api/inquiries/" + id + "/messages", json("body", body));
    }

    private ResultActions withdraw(String id) throws Exception {
        return mvc.perform(post("/api/inquiries/" + id + "/withdraw"));
    }

    private ResultActions resolveWithNote(String id, String note) throws Exception {
        return postJson("/api/inquiries/" + id + "/resolution", json("resolved", false, "note", note));
    }

    private String operatorDetail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private String myList() throws Exception {
        return text(mvc.perform(get("/api/inquiries")).andExpect(status().isOk()));
    }

    private int unreadReplyCount() throws Exception {
        return JsonPath.read(myList(), "$.unreadReplyCount");
    }

    /** 本人向けの一覧から、id が一致する1件の項目を取り出す。 */
    private Object listed(String id, String path) throws Exception {
        List<Object> found = JsonPath.read(myList(), "$.items[?(@.id=='" + id + "')]." + path);
        return found.get(0);
    }

    private String operatorList(String query) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries" + query)).andExpect(status().isOk()));
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult subscribe() throws Exception {
        return mvc.perform(get("/api/operator/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** ストリームから、指定した event の data のうち id が一致するものをすべて返す。 */
    private static List<String> eventData(String stream, String event, String id) {
        List<String> lines = stream.lines().toList();
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("event:" + event) && i + 1 < lines.size() && lines.get(i + 1).startsWith("data:")) {
                String data = lines.get(i + 1).substring("data:".length()).trim();
                if (id.equals(JsonPath.read(data, "$.id"))) {
                    found.add(data);
                }
            }
        }
        return found;
    }

    private List<String> historyEvents(String id) throws Exception {
        return JsonPath.read(operatorDetail(id), "$.history[*].event");
    }

    // ---- 担当者に渡す ----

    @Test
    void 担当者に渡しても終わらず_番が担当者になり_渡した時刻を持つ() throws Exception {
        String notHanded = idOf(create("期限の仕様"));
        String id = handedOver("期限の仕様");

        mvc.perform(get("/api/inquiries/" + notHanded))
                .andExpect(jsonPath("$.handedOverAt").value(nullValue()))
                .andExpect(jsonPath("$.unreadReply").value(false));
        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.ending").value(nullValue()))
                .andExpect(jsonPath("$.declined").value(nullValue()))
                .andExpect(jsonPath("$.handedOverAt").isNotEmpty())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.step").doesNotExist())
                .andExpect(jsonPath("$.outcome").doesNotExist())
                .andExpect(jsonPath("$.handlingStatus").doesNotExist());
        // 以前の filed_at に当たる
        assertThat(row(id).get("handed_over_at")).isNotNull();
        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.filedAt").isNotEmpty())
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"));
    }

    @Test
    void 種類ごとに渡したときの区間が決まり_渡しても終わらない() throws Exception {
        String know = handedOver("期限の仕様");
        String decide = handedOverToDecide();
        String change = toStructured("画面を変えてほしい", null);
        confirm(change);

        for (String id : List.of(know, decide, change)) {
            mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.ending").value(nullValue()));
        }
        assertThat((String) JsonPath.read(view(know), "$.phase")).isEqualTo("KNOW");
        assertThat((String) JsonPath.read(view(decide), "$.phase")).isEqualTo("DECIDE");
        assertThat((String) JsonPath.read(view(change), "$.phase")).isEqualTo("DECIDE");
    }

    @Test
    void 通知は初めて渡したときだけ_2回目以降はupdatedだけが流れる() throws Exception {
        MvcResult started = subscribe();
        String id = toAnswering("期限の仕様", null);
        stream(id);
        // AI の答えを確かめ、解決していないと答える（初めて渡す）
        resolve(id, false).andExpect(jsonPath("$.awaiting").value("HANDLING"));
        assertThat(waitFor(() -> eventData(content(started), "filed", id).size() == 1, 5000)).isTrue();
        assertThat(notifier.notices()).hasSize(1);

        // 担当者が結果を伝え、本人が解決していないと答える（2回目の受け渡し）
        deliver(id, "結果です").andExpect(status().isOk());
        resolve(id, false).andExpect(jsonPath("$.awaiting").value("HANDLING"));

        assertThat(waitFor(() -> eventData(content(started), "updated", id).size() >= 2, 5000)).isTrue();
        assertThat(eventData(content(started), "filed", id)).hasSize(1);
        assertThat(notifier.notices()).hasSize(1);
        assertThat(historyEvents(id)).filteredOn(e -> e.equals("HANDED_OVER")).hasSize(2);
    }

    // ---- 聞き返す ----

    @Test
    void 聞き返すと本人の番になり_本人側が未読になり_取得で既読になる() throws Exception {
        String id = handedOver("返信の確認");
        int before = unreadReplyCount();

        ask(id, "  どの画面ですか  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.turn").value("ASKER"))
                .andExpect(jsonPath("$.awaiting").value("ASKER_REPLY"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].from").value("OPERATOR"))
                .andExpect(jsonPath("$.messages[0].kind").value("ASK"))
                .andExpect(jsonPath("$.messages[0].authorName").value("担当者（開発用）"))
                .andExpect(jsonPath("$.messages[0].body").value("どの画面ですか"))
                .andExpect(jsonPath("$.messages[0].id").isNotEmpty())
                .andExpect(jsonPath("$.messages[0].createdAt").isNotEmpty());

        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        assertThat(listed(id, "messageCount")).isEqualTo(1);
        assertThat(listed(id, "awaiting")).isEqualTo("ASKER_REPLY");
        assertThat(unreadReplyCount()).isEqualTo(before + 1);

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.awaiting").value("ASKER_REPLY"))
                .andExpect(jsonPath("$.unreadReply").value(false))
                .andExpect(jsonPath("$.messages[0].body").value("どの画面ですか"));
        assertThat(listed(id, "unreadReply")).isEqualTo(false);
        assertThat(unreadReplyCount()).isEqualTo(before);
    }

    @Test
    void 聞き返しへの返事で担当者の番に戻り_担当者の側が未読に戻る() throws Exception {
        String id = handedOver("返事の確認");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(jsonPath("$.read").value(true));
        ask(id, "どの画面ですか");
        MvcResult started = subscribe();

        append(id, "  請求の画面です  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.messages[1].from").value("ASKER"))
                .andExpect(jsonPath("$.messages[1].kind").value("NOTE"))
                .andExpect(jsonPath("$.messages[1].authorName").value("開発用ユーザー"))
                .andExpect(jsonPath("$.messages[1].body").value("請求の画面です"));

        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"));
        assertThat(waitFor(() -> !eventData(content(started), "updated", id).isEmpty(), 5000)).isTrue();
        String data = eventData(content(started), "updated", id).get(0);
        assertThat((Boolean) JsonPath.read(data, "$.read")).isFalse();
        assertThat((String) JsonPath.read(data, "$.awaiting")).isEqualTo("HANDLING");
        assertThat((String) JsonPath.read(data, "$.turn")).isEqualTo("OPERATOR");
        assertThat((Integer) JsonPath.read(data, "$.messageCount")).isEqualTo(2);
        assertThat(historyEvents(id)).containsSubsequence("ASKED_BACK", "REPLIED");
    }

    @Test
    void 担当者の番の間の追記は番を変えず_担当者の側を未読に戻す() throws Exception {
        String id = handedOver("追記の確認");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(jsonPath("$.read").value(true));
        List<String> before = historyEvents(id);

        append(id, "もう1つあります")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.messages[0].kind").value("NOTE"));

        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.read").value(false));
        // 番が変わらない操作は、履歴に入れない
        assertThat(historyEvents(id)).isEqualTo(before);
        // 追記は本人の未読を増やさない
        assertThat(listed(id, "unreadReply")).isEqualTo(false);
    }

    // ---- 結果を伝える ----

    @Test
    void 結果を伝えると本人が確かめる_解決したと答えると終わる() throws Exception {
        String id = handedOverToDecide();

        deliver(id, "対応しました")
                .andExpect(jsonPath("$.phase").value("DECIDE"))
                .andExpect(jsonPath("$.turn").value("ASKER"))
                .andExpect(jsonPath("$.awaiting").value("RESULT_CONFIRMATION"))
                .andExpect(jsonPath("$.messages[0].kind").value("RESULT"));
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.awaiting").value("RESULT_CONFIRMATION"));

        resolve(id, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("CLOSED"))
                .andExpect(jsonPath("$.turn").value(nullValue()))
                .andExpect(jsonPath("$.awaiting").value(nullValue()))
                .andExpect(jsonPath("$.ending").value("RESOLVED"));
        assertThat(historyEvents(id)).containsSubsequence("RESULT_DELIVERED", "RESOLVED");
    }

    @Test
    void 担当者の結果に解決していないと答えると担当者の番に戻り_区間は変わらず_noteがメッセージに入る() throws Exception {
        String id = handedOverToDecide();
        deliver(id, "対応しました");

        resolveWithNote(id, "まだ直っていません")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DECIDE"))
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.messages[1].from").value("ASKER"))
                .andExpect(jsonPath("$.messages[1].kind").value("NOTE"))
                .andExpect(jsonPath("$.messages[1].body").value("まだ直っていません"));
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.read").value(false));
    }

    @Test
    void 結果への追記は解決していないと答えたのと同じ() throws Exception {
        String id = handedOverToDecide();
        deliver(id, "対応しました");

        append(id, "直っていません")
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.phase").value("DECIDE"));
    }

    @Test
    void 本人がAIの答えを確かめて解決していないと答えると担当者の番になり_noteが入る() throws Exception {
        String id = toAnswering("期限の仕様", null);
        stream(id);

        resolveWithNote(id, "知りたいのはそこではない")
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.messages[0].body").value("知りたいのはそこではない"))
                .andExpect(jsonPath("$.handedOverAt").isNotEmpty());
        // 解決したのに note を付けた、空の note、長すぎる note
        String another = toAnswering("期限の仕様", null);
        stream(another);
        postJson("/api/inquiries/" + another + "/resolution", json("resolved", true, "note", "x"))
                .andExpect(status().isBadRequest());
        resolveWithNote(another, "   ").andExpect(status().isBadRequest());
        resolveWithNote(another, "あ".repeat(4001)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/inquiries/" + another)).andExpect(jsonPath("$.awaiting").value("RESULT_CONFIRMATION"));
    }

    // ---- 見送る ----

    @Test
    void 見送ると理由がメッセージに入り終わる() throws Exception {
        String id = handedOverToDecide();
        int before = unreadReplyCount();

        decline(id, "KNOWN_LIMITATION", "  いまの仕様です  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("CLOSED"))
                .andExpect(jsonPath("$.turn").value(nullValue()))
                .andExpect(jsonPath("$.awaiting").value(nullValue()))
                .andExpect(jsonPath("$.ending").value("DECLINED"))
                .andExpect(jsonPath("$.declined.reasonKind").value("KNOWN_LIMITATION"))
                .andExpect(jsonPath("$.declined.reason").value("いまの仕様です"))
                .andExpect(jsonPath("$.messages[0].from").value("OPERATOR"))
                .andExpect(jsonPath("$.messages[0].kind").value("DECLINE"))
                .andExpect(jsonPath("$.messages[0].body").value("いまの仕様です"));

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.ending").value("DECLINED"))
                .andExpect(jsonPath("$.declined.reason").value("いまの仕様です"));
        // 見送りの連絡も、担当者の発言として、本人側は未読になる（取得するまで）
        assertThat(unreadReplyCount()).isEqualTo(before);
        assertThat(listed(id, "ending")).isEqualTo("DECLINED");
        assertThat(historyEvents(id)).endsWith("DECLINED");
    }

    @Test
    void 見送りの連絡は本人側を未読にする() throws Exception {
        String id = handedOver("未読の見送り");
        int before = unreadReplyCount();

        decline(id, "DUPLICATE", "重複です").andExpect(status().isOk());

        assertThat(listed(id, "unreadReply")).isEqualTo(true);
        assertThat(unreadReplyCount()).isEqualTo(before + 1);
    }

    @Test
    void 見送りの入力の誤りと実行できない状態() throws Exception {
        String id = handedOver("見送りの検証");

        decline(id, "UNKNOWN_KIND", "x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        decline(id, "OTHER", "   ").andExpect(status().isBadRequest());
        decline(id, "OTHER", "あ".repeat(2001)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/operator/inquiries/" + id + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        decline(id, "OTHER", "あ".repeat(2000)).andExpect(status().isOk());
        // 終わった件は、見送れない
        decline(id, "OTHER", "もう一度").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ---- 置き直す ----

    @Test
    @SuppressWarnings("unchecked")
    void 置き直すと置いた人がOPERATORになり_種類が変わると区間が変わり_入口の答えは残り_履歴に前後が残る() throws Exception {
        String id = toAnswering("[遅延] 期限の仕様", "job-admin");
        sendNow(id);
        String intakeBefore = JsonPath.read(operatorDetail(id), "$.intake[*].answer.kind").toString();

        replacePlacement(id, json("type", "DO_FOR_ME", "systemId", "billing", "functionId", "invoice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type.id").value("DO_FOR_ME"))
                .andExpect(jsonPath("$.type.by").value("OPERATOR"))
                .andExpect(jsonPath("$.system.id").value("billing"))
                .andExpect(jsonPath("$.system.label").value("請求管理"))
                .andExpect(jsonPath("$.system.by").value("OPERATOR"))
                .andExpect(jsonPath("$.function.id").value("invoice"))
                .andExpect(jsonPath("$.function.by").value("OPERATOR"))
                // 種類が変わったので、区間は新しい種類の決まりに合う。番は変わらない
                .andExpect(jsonPath("$.phase").value("DECIDE"))
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                // 入口で本人が何を選んだかは、そのまま残る
                .andExpect(jsonPath("$.intake[0].answer.kind").value("INFERRED"))
                .andExpect(jsonPath("$.intake[0].answer.optionId").value("HOW_IT_WORKS"))
                .andExpect(jsonPath("$.intake[1].answer.kind").value("OPTION"))
                .andExpect(jsonPath("$.intake[1].answer.optionId").value("job-admin"));
        assertThat(JsonPath.read(operatorDetail(id), "$.intake[*].answer.kind").toString()).isEqualTo(intakeBefore);

        List<Map<String, Object>> history = JsonPath.read(operatorDetail(id), "$.history");
        Map<String, Object> last = history.get(history.size() - 1);
        Map<String, Map<String, Object>> detail = (Map<String, Map<String, Object>>) last.get("detail");
        assertThat(last.get("event")).isEqualTo("PLACEMENT_CHANGED");
        assertThat(last.get("actor")).isEqualTo("OPERATOR");
        // 前後の置き場所は、既定の言語の名前で残る。決まっていない項目は null
        assertThat(detail.get("before"))
                .containsEntry("type", "仕組みを知りたい")
                .containsEntry("system", "求人管理画面")
                .containsEntry("function", null);
        assertThat(detail.get("after"))
                .containsEntry("type", "やってほしい")
                .containsEntry("system", "請求管理")
                .containsEntry("function", "請求書");
        // 置き直し以外の出来事は、detail が null
        assertThat(history.subList(0, history.size() - 1)).allSatisfy(h -> assertThat(h).containsEntry("detail", null));
        // 本人向けの表現にも、置き直した後の置き場所が出る
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.type.by").value("OPERATOR"));
        // 一覧の項目にも反映される
        String listed = operatorList("");
        assertThat(JsonPath.<Object>read(listed, "$.items[?(@.id=='" + id + "')].system.label"))
                .isEqualTo(List.of("請求管理"));
    }

    @Test
    void 種類が同じ置き直しは区間を変えず_どれもnullにできる() throws Exception {
        String id = handedOver("期限の仕様");

        replacePlacement(id, json("type", "HOW_IT_WORKS", "systemId", "reports"))
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.system.id").value("reports"))
                .andExpect(jsonPath("$.function").value(nullValue()));
        replacePlacement(id, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(nullValue()))
                .andExpect(jsonPath("$.system").value(nullValue()))
                .andExpect(jsonPath("$.function").value(nullValue()))
                // 種類なしは、KNOW に合わせる
                .andExpect(jsonPath("$.phase").value("KNOW"));
    }

    @Test
    void 担当者の番でなくても置き直せる_終わった件は置き直せない() throws Exception {
        String waiting = handedOver("返事待ち");
        ask(waiting, "どの画面ですか");
        replacePlacement(waiting, json("type", "LOOKS_WRONG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting").value("ASKER_REPLY"))
                .andExpect(jsonPath("$.turn").value("ASKER"))
                .andExpect(jsonPath("$.phase").value("DECIDE"));

        String closed = handedOver("終わった件");
        decline(closed, "OTHER", "対象外です");
        replacePlacement(closed, json("type", "HOW_TO"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 置き直しの入力の誤り() throws Exception {
        String id = handedOver("置き直しの検証");

        // そのシステムにない機能、一覧にないシステム、システムなしの機能、知らない種類
        replacePlacement(id, json("systemId", "billing", "functionId", "posting"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        replacePlacement(id, json("systemId", "nowhere")).andExpect(status().isBadRequest());
        replacePlacement(id, json("functionId", "posting")).andExpect(status().isBadRequest());
        replacePlacement(id, json("type", "INCIDENT")).andExpect(status().isBadRequest());
        replacePlacement("00000000-0000-0000-0000-000000000000", "{}").andExpect(status().isNotFound());
        assertThat((Object) JsonPath.read(view(id), "$.type.by")).isEqualTo("AI");
    }

    // ---- 追記と開き直し ----

    @Test
    void 終わった件への追記は開き直し_終わる前の区間に戻して担当者の番にする() throws Exception {
        String resolved = handedOverToDecide();
        deliver(resolved, "対応しました");
        resolve(resolved, true);
        String declined = handedOver("見送られた件");
        decline(declined, "NOT_REPRODUCIBLE", "再現しません");

        // 解決を伝えた件: 終わる前の区間（DECIDE）に戻る
        append(resolved, "やはり直っていません")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DECIDE"))
                .andExpect(jsonPath("$.turn").value("OPERATOR"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.ending").value(nullValue()));
        assertThat(historyEvents(resolved)).endsWith("RESOLVED", "REOPENED");
        // 見送られた件: 見送りの内容は消える
        append(declined, "再現手順を書きます")
                .andExpect(jsonPath("$.phase").value("KNOW"))
                .andExpect(jsonPath("$.awaiting").value("HANDLING"))
                .andExpect(jsonPath("$.ending").value(nullValue()))
                .andExpect(jsonPath("$.declined").value(nullValue()));
        mvc.perform(get("/api/operator/inquiries/" + declined)).andExpect(jsonPath("$.read").value(false));
    }

    @Test
    void 渡したことのない入口の途中の件と取り下げた件への追記はINVALID_STATE() throws Exception {
        String inProgress = idOf(create("期限の仕様"));
        String withdrawn = handedOver("取り下げる件");
        withdraw(withdrawn);

        for (String id : List.of(inProgress, withdrawn)) {
            append(id, "追記").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
    }

    // ---- 取り下げ ----

    @Test
    void 取り下げは終わっていなければどの時点でもでき_渡した件ではupdatedが流れる() throws Exception {
        List<String> ids =
                List.of(idOf(create("入口")), toStructured("確認中", null), toAnswering("調査中", null));
        String answered = toAnswering("答えの確認中", null);
        stream(answered);
        String handed = handedOver("担当者の番");
        MvcResult started = subscribe();

        for (String id : ids) {
            withdraw(id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phase").value("CLOSED"))
                    .andExpect(jsonPath("$.turn").value(nullValue()))
                    .andExpect(jsonPath("$.ending").value("WITHDRAWN"));
        }
        withdraw(answered).andExpect(jsonPath("$.ending").value("WITHDRAWN"));
        withdraw(handed).andExpect(jsonPath("$.ending").value("WITHDRAWN"));

        assertThat(waitFor(() -> !eventData(content(started), "updated", handed).isEmpty(), 5000)).isTrue();
        assertThat((String) JsonPath.read(eventData(content(started), "updated", handed).get(0), "$.ending"))
                .isEqualTo("WITHDRAWN");
        // 渡したことのない件は、担当者に知らせない
        assertThat(content(started)).doesNotContain(ids.get(0)).doesNotContain(answered);
        assertThat(historyEvents(handed)).endsWith("WITHDRAWN");
    }

    @Test
    void 終わった件は取り下げられない() throws Exception {
        String resolved = toAnswering("期限の仕様", null);
        stream(resolved);
        resolve(resolved, true);
        String declined = handedOver("見送り");
        decline(declined, "OTHER", "対象外");
        String withdrawn = idOf(create("取り下げ済み"));
        withdraw(withdrawn);

        for (String id : List.of(resolved, declined, withdrawn)) {
            withdraw(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
    }

    // ---- 実行できない状態 ----

    @Test
    void 本人の番のときの担当者の操作と_結果を確かめていないときの結果の確かめはINVALID_STATE() throws Exception {
        String id = handedOver("本人の番");
        ask(id, "どの画面ですか");

        ask(id, "もう一度").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        deliver(id, "結果").andExpect(status().isConflict());
        decline(id, "OTHER", "対象外").andExpect(status().isConflict());
        // 聞き返しへの返事を待っている間は、結果の確かめではない
        resolve(id, true).andExpect(status().isConflict());
        sendNow(id).andExpect(status().isConflict());
    }

    @Test
    void 担当者に渡したことのない問い合わせへの担当者の操作はNOT_FOUND() throws Exception {
        String id = idOf(create("期限の仕様"));

        ask(id, "x").andExpect(status().isNotFound());
        decline(id, "OTHER", "x").andExpect(status().isNotFound());
        replacePlacement(id, "{}").andExpect(status().isNotFound());
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isNotFound());
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isNotFound());
        // 以前の対応状況の切り替えは廃止した
        mvc.perform(put("/api/operator/inquiries/" + handedOver("廃止") + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 追記と返信の本文は前後の空白を除いて1から4000文字で_返信にはkindが要る() throws Exception {
        String id = handedOver("本文の検証");

        for (String bad : List.of("", "   ", "\n\t ", "あ".repeat(4001), " " + "あ".repeat(4001))) {
            append(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
            ask(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        postJson("/api/inquiries/" + id + "/messages", "{}").andExpect(status().isBadRequest());
        // kind がない、ASK・RESULT 以外
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
        operatorReply(id, "DECLINE", "x").andExpect(status().isBadRequest());
        operatorReply(id, "NOTE", "x").andExpect(status().isBadRequest());
        // 検証に落ちた入力は何も残さない
        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.awaiting").value("HANDLING"));

        append(id, "あ".repeat(4000)).andExpect(status().isOk());
        append(id, " 空白を除けば4000文字 " + "い".repeat(3984) + "   ").andExpect(status().isOk());
    }

    @Test
    void メッセージは古い順に1本の時系列で返る() throws Exception {
        String id = handedOver("時系列");

        ask(id, "1つ目");
        append(id, "2つ目");
        ask(id, "3つ目");

        String view = text(mvc.perform(get("/api/inquiries/" + id)));
        assertThat((List<String>) JsonPath.read(view, "$.messages[*].body")).containsExactly("1つ目", "2つ目", "3つ目");
        assertThat((List<String>) JsonPath.read(view, "$.messages[*].from"))
                .containsExactly("OPERATOR", "ASKER", "OPERATOR");
        assertThat((List<String>) JsonPath.read(view, "$.messages[*].kind")).containsExactly("ASK", "NOTE", "ASK");
        String detail = operatorDetail(id);
        assertThat((List<String>) JsonPath.read(detail, "$.messages[*].body")).containsExactly("1つ目", "2つ目", "3つ目");
    }

    // ---- 未読 ----

    @Test
    void 番が担当者に移るたびに担当者の側が未読に戻る() throws Exception {
        String id = handedOver("未読の確認");
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(jsonPath("$.read").value(true));
        deliver(id, "結果です");
        // 担当者が発言しても、担当者の側の既読は変わらない
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.read").value(true));

        resolveWithNote(id, "まだです");

        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.read").value(false));
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(jsonPath("$.read").value(true));
    }

    // ---- 履歴 ----

    @Test
    void 履歴は起きた順に出来事が並び_本人向けの表現には含まれない() throws Exception {
        String id = toAnswering("期限の仕様", null);
        stream(id);
        resolve(id, false);
        ask(id, "どの画面ですか");
        append(id, "請求です");
        deliver(id, "対応しました");
        resolve(id, true);
        append(id, "また起きました");
        withdraw(id);

        String detail = operatorDetail(id);

        assertThat((List<String>) JsonPath.read(detail, "$.history[*].event"))
                .containsExactly(
                        "CREATED",
                        "QUESTION_COMPOSED",
                        "QUESTION_CONFIRMED",
                        "ANSWERED_BY_AI",
                        "HANDED_OVER",
                        "ASKED_BACK",
                        "REPLIED",
                        "RESULT_DELIVERED",
                        "RESOLVED",
                        "REOPENED",
                        "WITHDRAWN");
        assertThat((List<String>) JsonPath.read(detail, "$.history[*].actor"))
                .containsExactly(
                        "ASKER", "AI", "ASKER", "AI", "ASKER", "OPERATOR", "ASKER", "OPERATOR", "ASKER", "ASKER",
                        "ASKER");
        // 各件は、変化の後の状態を持つ
        assertThat((String) JsonPath.read(detail, "$.history[5].awaiting")).isEqualTo("ASKER_REPLY");
        assertThat((String) JsonPath.read(detail, "$.history[5].turn")).isEqualTo("ASKER");
        assertThat((String) JsonPath.read(detail, "$.history[8].phase")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(detail, "$.history[8].ending")).isEqualTo("RESOLVED");
        assertThat((Object) JsonPath.read(detail, "$.history[8].turn")).isNull();
        assertThat((String) JsonPath.read(detail, "$.history[0].at")).isNotBlank();
        assertThat((Object) JsonPath.read(detail, "$.history[0].detail")).isNull();
        assertThat((Object) JsonPath.read(detail, "$.history[10].ending")).isEqualTo("WITHDRAWN");
        assertThat(view(id)).doesNotContain("\"history\"");
    }

    @Test
    void 入口の途中の遷移も履歴に残る() throws Exception {
        // 調査は確定の時点で始まる。送るまでに終わらないよう、遅らせる印を添える
        String id = toStructured("[遅延] 期限の仕様", null);
        mvc.perform(post("/api/inquiries/" + id + "/reopen")).andExpect(status().isOk());
        finishIntake(id, null);
        confirm(id);
        sendNow(id);

        assertThat(historyEvents(id))
                .containsExactly(
                        "CREATED",
                        "QUESTION_COMPOSED",
                        "REOPENED_INTAKE",
                        "QUESTION_COMPOSED",
                        "QUESTION_CONFIRMED",
                        "HANDED_OVER");
    }

    // ---- 一覧 ----

    @Test
    void 担当者の一覧は渡したことのある件だけで_新しい項目を持ち_終わった件も残る() throws Exception {
        String notHanded = idOf(create("期限の仕様"));
        String id = handedOver("一覧の項目");
        String declined = handedOver("見送る件");
        decline(declined, "OTHER", "対象外");
        String selfResolved = toAnswering("期限の仕様", null);
        stream(selfResolved);
        resolve(selfResolved, true);

        String body = operatorList("");

        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).contains(id, declined).doesNotContain(notHanded, selfResolved);
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + id + "')].phase")).isEqualTo(List.of("KNOW"));
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + id + "')].turn")).isEqualTo(List.of("OPERATOR"));
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + id + "')].awaiting")).isEqualTo(List.of("HANDLING"));
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + id + "')].messageCount")).isEqualTo(List.of(0));
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + declined + "')].ending"))
                .isEqualTo(List.of("DECLINED"));
        assertThat(JsonPath.<Object>read(body, "$.items[?(@.id=='" + id + "')].filedAt")).isNotNull();
        assertThat(body).doesNotContain("handlingStatus");
    }

    @Test
    void 本人の一覧は自分の問い合わせだけを新しい順に返し_新しい項目を持つ() throws Exception {
        String first = idOf(create("一覧の1件目"));
        String second = handedOver("一覧の2件目");
        String third = idOf(create("一覧の3件目"));
        jdbc.update("update inquiries set asker_id = 'someone-else' where id = ?::uuid", second);

        String body = myList();
        List<String> ids = JsonPath.read(body, "$.items[*].id");

        assertThat(ids).startsWith(third, first).doesNotContain(second);
        assertThat(ids.size()).isLessThanOrEqualTo(50);
        assertThat(listed(third, "phase")).isEqualTo("INTAKE");
        assertThat(listed(third, "turn")).isEqualTo("ASKER");
        assertThat(listed(third, "awaiting")).isEqualTo("INTAKE_ANSWER");
        assertThat(listed(third, "ending")).isNull();
        assertThat(listed(third, "summary")).isEqualTo("一覧の3件目");
        assertThat(listed(third, "createdAt")).isNotNull();
        assertThat(listed(third, "unreadReply")).isEqualTo(false);
        assertThat(listed(third, "messageCount")).isEqualTo(0);
        assertThat(listed(third, "urgency")).isEqualTo("NORMAL");
        assertThat(listed(third, "handedOverAt")).isNull();
        assertThat(body).doesNotContain("\"step\"").doesNotContain("\"outcome\"");
    }

    @Test
    void 本人の一覧は緊急度と担当者に渡した時刻を持ち_渡した時刻で結果の出どころを見分けられる() throws Exception {
        String urgent = idOf(create("急ぎの件", "HURRY"));
        String viaAi = toAnswering("期限の仕様", null);
        stream(viaAi);
        String viaOperator = handedOver("担当者の結果");
        deliver(viaOperator, "結果です");

        // AI の答えを確かめている件は、渡したことがない。担当者の結果を確かめている件は、渡したことがある
        assertThat(listed(urgent, "urgency")).isEqualTo("HURRY");
        assertThat(listed(viaAi, "awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat(listed(viaAi, "handedOverAt")).isNull();
        assertThat(listed(viaOperator, "awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat(listed(viaOperator, "handedOverAt")).isNotNull();
    }

    // ---- 他人の問い合わせ ----

    @Test
    void 他人の問い合わせは取得も本人向けのすべての操作もNOT_FOUND() throws Exception {
        String id = handedOver("他人の問い合わせ");
        ask(id, "返信").andExpect(status().isOk());
        jdbc.update("update inquiries set asker_id = 'someone-else' where id = ?::uuid", id);
        String json = "application/json";

        mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isNotFound());
        postJson("/api/inquiries/" + id + "/intake/answers", "{\"questionId\":\"type\",\"unknown\":true}")
                .andExpect(status().isNotFound());
        back(id, "type").andExpect(status().isNotFound());
        intakeText(id, "追記").andExpect(status().isNotFound());
        compose(id).andExpect(status().isNotFound());
        urgency(id, "BLOCKED").andExpect(status().isNotFound());
        confirm(id).andExpect(status().isNotFound());
        mvc.perform(post("/api/inquiries/" + id + "/revision").contentType(json).content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/inquiries/" + id + "/reopen")).andExpect(status().isNotFound());
        mvc.perform(get("/api/inquiries/" + id + "/answer-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        resolve(id, true).andExpect(status().isNotFound());
        sendNow(id).andExpect(status().isNotFound());
        withdraw(id).andExpect(status().isNotFound());
        append(id, "追記").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 取得できないので、返信の未読も消えない。担当者側の API は影響を受けない
        assertThat(row(id).get("asker_unread_reply")).isEqualTo(true);
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk());
    }
}
