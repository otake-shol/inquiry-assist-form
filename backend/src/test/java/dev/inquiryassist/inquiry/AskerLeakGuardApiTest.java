package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 担当者だけが見るはずの情報が、本人向けの応答に漏れないことを、まとめて守る。担当者だけの情報を、すべて持たせた問い合わせを作り、
 * 本人向けの3つの口（詳細、一覧、進捗ストリームの {@code done}）について、(a) 最上位の鍵の一覧が決まったものと一致すること、
 * (b) 担当者だけの文字列と鍵の名前が、どこにも現れないことを確かめる。本人向けの型に項目を足すと、(a) が落ちるので、
 * 足してよいかを、この一覧を直しながら決める。
 *
 * <p>持たせる情報: 確かめられなかった内容（{@code [一部幽霊]}）、裏の調査の結果（調べている間に送ったので、結果は担当者だけ）、
 * AI の案、相談と決定、同じ置き場所の別の問い合わせの先例、担当者が頼んだ訳。
 */
class AskerLeakGuardApiTest extends ApiSupport {

    /** 本人向けの詳細（{@link InquiryView}）の最上位の鍵。 */
    private static final Set<String> VIEW_KEYS = new TreeSet<>(List.of(
            "id", "createdAt", "language", "phase", "turn", "awaiting", "ending", "closedBy", "declined", "urgency", "type",
            "system", "function", "utterance", "intake", "revisionsLeft", "structured", "answer", "canSendNow",
            "sentImmediately", "handedOverAt", "handoverReason", "unreadReply", "noReply", "messages", "trace", "mock"));

    /** 本人向けの一覧の1件（{@link InquiryList.Item}）の鍵。 */
    private static final Set<String> ITEM_KEYS = new TreeSet<>(List.of(
            "id", "createdAt", "language", "summary", "urgency", "phase", "turn", "awaiting", "ending", "handedOverAt",
            "unreadReply", "messageCount"));

    /** 本人向けの一覧の最上位の鍵。 */
    private static final Set<String> LIST_KEYS = new TreeSet<>(List.of("unreadReplyCount", "items"));

    /** 担当者向けの型にだけある項目の名前。本人向けの JSON のどこにも、鍵として現れてはならない。 */
    private static final List<String> OPERATOR_ONLY_KEYS = List.of(
            "advice", "consultations", "decisions", "precedents", "unconfirmed", "investigation", "defaultConsultants");

    /**
     * 本人の詳細にも出る項目。置き場所（識別子と名前。担当者向けの部分木では、先例の置き場所にも出る）と、本人が書いた内容から
     * 作る相談の状況（{@code situation}）。担当者だけの文字列を集めるときは、これらを除く。
     */
    private static final Set<String> PLACEMENT_KEYS =
            Set.of("label", "name", "id", "type", "system", "function", "systemId", "functionId", "situation");

    private static String uniqueSystem() {
        return "sys-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String operatorDetail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    /** 本人向けの、一覧の中のこの問い合わせの1件（JSON 文字列ではなく、一覧全体も返すため、一覧の本文を使う）。 */
    private String list() throws Exception {
        return text(mvc.perform(get("/api/inquiries")).andExpect(status().isOk()));
    }

    private static String doneOf(String streamBody) {
        String[] lines = streamBody.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("event:done")) {
                return lines[i + 1].substring("data:".length()).trim();
            }
        }
        throw new IllegalStateException("done がない: " + streamBody);
    }

    /** JSON 全体から、鍵の名前をすべて集める（入れ子も含む）。 */
    private static Set<String> allKeys(Object node, Set<String> into) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                into.add((String) k);
                allKeys(v, into);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(v -> allKeys(v, into));
        }
        return into;
    }

    /**
     * 担当者向けの部分木から、文字列の値を集める。本人向けにも出る語（列挙の値、識別子、時刻、置き場所の名前）は除く。
     * 置き場所の名前は、本人の詳細にも出す（問い合わせの種類、システム、機能）ので、先例などの置き場所の名前も除く。
     */
    private static void leaves(Object node, List<String> into) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (!PLACEMENT_KEYS.contains(k)) {
                    leaves(v, into);
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(v -> leaves(v, into));
        } else if (node instanceof String s
                && s.length() >= 8
                && !s.matches("[A-Z0-9_]+")
                && !s.matches("[0-9a-f-]{36}")
                && !s.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            into.add(s);
        }
    }

    private static void assertNoLeak(String asker, List<String> operatorOnlyStrings, String where) {
        Object parsed = JsonPath.parse(asker).json();
        Set<String> keys = allKeys(parsed, new TreeSet<>());
        assertThat(keys).as(where + " に担当者だけの鍵がある").doesNotContainAnyElementsOf(OPERATOR_ONLY_KEYS);
        for (String s : operatorOnlyStrings) {
            assertThat(asker).as(where + " に担当者だけの文字列がある: " + s).doesNotContain(s);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 担当者だけの情報を全部持つ問い合わせでも_本人向けの詳細_一覧_doneには漏れない() throws Exception {
        String markerDecision = "LEAK-決定-" + UUID.randomUUID();
        String markerPrecedent = "LEAK-先例-" + UUID.randomUUID();
        String markerQuestion = "LEAK-相談-" + UUID.randomUUID();
        String system = uniqueSystem();

        // 同じ置き場所の、別の問い合わせの先例
        String other = filedNow("使い方を教えて", null);
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update(
                "insert into precedents (id, inquiry_id, type, system_id, function_id, situation, decision_kind, decision, reason,"
                        + " decided_by, language, decided_at, updated_at)"
                        + " values (?::uuid, ?::uuid, 'HOW_TO', ?, 'f1', '状況', 'ACCEPT', ?, '理由', '決めた人', 'ja', ?, ?)",
                UUID.randomUUID().toString(),
                other,
                system,
                markerPrecedent,
                at,
                at);

        // 確かめられなかった内容を含む調査を、調べている間に送る（結果は担当者だけ）。置き場所を、先例と同じにする
        String id = toStructured("[一部幽霊] [遅延] 使い方を教えて", null);
        jdbc.update(
                "update inquiries set placement_override = ?::jsonb where id = ?::uuid",
                "{\"type\":\"HOW_TO\",\"systemId\":\"" + system + "\",\"functionId\":\"f1\"}",
                id);
        confirm(id).andExpect(status().isOk());
        sendNow(id).andExpect(status().isOk());
        awaitInvestigationIdle(id);

        // 相談して、持ち主が決める
        mvc.perform(post("/api/operator/inquiries/" + id + "/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("question", markerQuestion)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "decisionKind", "AS_INTENDED",
                                "decision", markerDecision,
                                "reason", "理由です",
                                "decidedBy", "決めた人")))
                .andExpect(status().isOk());
        awaitInvestigationIdle(id);

        // 担当者が訳を頼む（別の言語の件にする）
        jdbc.update("update inquiries set language = 'en' where id = ?::uuid", id);
        mvc.perform(post("/api/operator/inquiries/" + id + "/translation").header("Accept-Language", "ja"))
                .andExpect(status().isOk());
        jdbc.update("update inquiries set language = 'ja' where id = ?::uuid", id);

        // 担当者の画面には、すべてある（この問い合わせが、本当に全部持っていることの確かめ）
        String operator = operatorDetail(id);
        assertThat((List<String>) JsonPath.read(operator, "$.answer.memo.unconfirmed")).isNotEmpty();
        assertThat((String) JsonPath.read(operator, "$.investigation.audience")).isEqualTo("OPERATOR");
        assertThat((String) JsonPath.read(operator, "$.advice.status")).isEqualTo("DONE");
        assertThat((List<Object>) JsonPath.read(operator, "$.consultations")).hasSize(1);
        assertThat((List<String>) JsonPath.read(operator, "$.decisions[*].decision")).contains(markerDecision);
        assertThat((List<String>) JsonPath.read(operator, "$.precedents[*].decision")).contains(markerPrecedent);

        // 担当者だけの文字列を、担当者向けの部分木から集める
        List<String> operatorOnly = new ArrayList<>(List.of(markerDecision, markerPrecedent, markerQuestion));
        for (String path : List.of("$.answer", "$.advice", "$.consultations", "$.decisions", "$.precedents", "$.investigation")) {
            leaves(JsonPath.read(operator, path), operatorOnly);
        }
        assertThat(operatorOnly.size()).as("担当者だけの文字列を十分に集められている").isGreaterThan(10);

        // (1) 詳細
        String detail = text(mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isOk()));
        assertThat(allKeys(JsonPath.parse(detail).json(), new TreeSet<>()).containsAll(VIEW_KEYS)).isTrue();
        assertThat(new TreeSet<>(((Map<String, Object>) JsonPath.parse(detail).json()).keySet())).isEqualTo(VIEW_KEYS);
        assertThat((Object) JsonPath.read(detail, "$.answer")).isNull();
        assertNoLeak(detail, operatorOnly, "詳細");

        // (2) 一覧
        String list = list();
        assertThat(new TreeSet<>(((Map<String, Object>) JsonPath.parse(list).json()).keySet())).isEqualTo(LIST_KEYS);
        List<Map<String, Object>> mine = JsonPath.read(list, "$.items[?(@.id=='" + id + "')]");
        assertThat(mine).hasSize(1);
        assertThat(new TreeSet<>(mine.get(0).keySet())).isEqualTo(ITEM_KEYS);
        assertNoLeak(list, operatorOnly, "一覧");

        // (3) 進捗ストリームの done（調査を待つ状態はもう終わっているので、すぐ現在の状態で done）
        String done = doneOf(stream(id));
        assertThat(new TreeSet<>(((Map<String, Object>) JsonPath.parse(done).json()).keySet())).isEqualTo(VIEW_KEYS);
        assertNoLeak(done, operatorOnly, "done");
    }
}
