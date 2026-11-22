package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/** 担当者のための訳（契約書「訳」）を API から確かめる。本人の言語が {@code en} の件を、{@code ja} で見る担当者が訳す。 */
class TranslationApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";
    private static final String MARK = "【訳の見本】";

    @MockitoSpyBean
    TranslatorSkill translator;

    @BeforeEach
    void reset() {
        clearInvocations(translator);
    }

    private long translateCalls() {
        return mockingDetails(translator).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("translate"))
                .count();
    }

    /** 担当者に渡った、本人の言語が en の件を作る。 */
    private String englishFiled(String utterance) throws Exception {
        String id = filedNow(utterance, null);
        jdbc.update("update inquiries set language = 'en' where id = ?::uuid", id);
        return id;
    }

    private ResultActions translate(String id, String viewerLanguage) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/translation").header("Accept-Language", viewerLanguage));
    }

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    private int usageCount(String id) {
        return jdbc.queryForObject("select jsonb_array_length(usages) from inquiries where id = ?::uuid", Integer.class, id);
    }

    @Test
    void 本人の言語と違う言語で見る担当者に_本人が書いた文章と言い直しと説明と本人の発言を訳す() throws Exception {
        String id = englishFiled(HOW_TO);
        operatorAsk(id);
        String messageId = askerReplies(id, "It was yesterday.");
        String detail = detail(id);

        String body = text(translate(id, "ja").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(body, "$.from")).isEqualTo("en");
        assertThat((String) JsonPath.read(body, "$.to")).isEqualTo("ja");
        assertThat((List<String>) JsonPath.read(body, "$.items[*].key"))
                .containsExactly("utterance", "structured.summary", "answer.askerExplanation", "message:" + messageId);
        assertThat((String) JsonPath.read(body, "$.items[0].original")).isEqualTo(JsonPath.read(detail, "$.utterance"));
        assertThat((String) JsonPath.read(body, "$.items[1].original")).isEqualTo(JsonPath.read(detail, "$.structured.summary"));
        assertThat((String) JsonPath.read(body, "$.items[2].original")).isEqualTo(JsonPath.read(detail, "$.answer.askerExplanation"));
        assertThat((String) JsonPath.read(body, "$.items[3].original")).isEqualTo("It was yesterday.");
        // モックは訳さず、原文の前に印を付ける
        for (int i = 0; i < 4; i++) {
            assertThat((String) JsonPath.read(body, "$.items[" + i + "].translated"))
                    .isEqualTo(MARK + JsonPath.<String>read(body, "$.items[" + i + "].original"));
        }
        // 担当者の発言は、訳さない
        assertThat((List<String>) JsonPath.read(body, "$.items[*].original")).doesNotContain("詳しく教えてください");
    }

    @Test
    void ないものは入らない() throws Exception {
        // 言い直しを作れず、答えもない件。本人が書いた文章だけがある
        String id = idOf(create("[言い直しエラー] " + HOW_TO));
        unknown(id, "system").andExpect(status().isOk());
        unknown(id, "q1").andExpect(status().isServiceUnavailable());
        sendNow(id).andExpect(status().isOk());
        jdbc.update("update inquiries set language = 'en' where id = ?::uuid", id);

        String body = text(translate(id, "ja").andExpect(status().isOk()));

        assertThat((List<String>) JsonPath.read(body, "$.items[*].key")).containsExactly("utterance");
    }

    @Test
    void 二度目はスキルを呼ばず_利用量も増えない_増えた発言の分だけを訳す() throws Exception {
        String id = englishFiled(HOW_TO);
        translate(id, "ja").andExpect(status().isOk());
        assertThat(translateCalls()).isEqualTo(1);
        int usagesAfterFirst = usageCount(id);
        assertThat(usageSteps(id)).endsWith("translator");

        String second = text(translate(id, "ja").andExpect(status().isOk()));

        assertThat(translateCalls()).isEqualTo(1);
        assertThat(usageCount(id)).isEqualTo(usagesAfterFirst);
        assertThat((List<String>) JsonPath.read(second, "$.items[*].translated")).allSatisfy(t -> assertThat(t).startsWith(MARK));

        // 本人の発言が増えた後は、増えた分だけを訳す
        String messageId = askerReplies(id, "One more thing.");
        String third = text(translate(id, "ja").andExpect(status().isOk()));

        assertThat(translateCalls()).isEqualTo(2);
        assertThat(usageCount(id)).isEqualTo(usagesAfterFirst + 1);
        assertThat((List<String>) JsonPath.read(third, "$.items[*].key")).endsWith("message:" + messageId);
        assertThat((List<String>) JsonPath.read(third, "$.items[-1:].translated")).containsExactly(MARK + "One more thing.");
        org.mockito.Mockito.verify(translator).translate(List.of("One more thing."), "en", "ja");
    }

    @Test
    void 訳す先の言語ごとに別に保存する() throws Exception {
        String id = idOf(create(HOW_TO));
        finishIntake(id, null);
        confirm(id).andExpect(status().isOk());
        sendNow(id).andExpect(status().isOk());
        jdbc.update("update inquiries set language = 'fr' where id = ?::uuid", id);

        String toJa = text(translate(id, "ja").andExpect(status().isOk()));
        String toEn = text(translate(id, "en").andExpect(status().isOk()));

        assertThat((String) JsonPath.read(toJa, "$.items[0].translated")).startsWith(MARK);
        assertThat((String) JsonPath.read(toEn, "$.items[0].translated")).startsWith("[Sample translation] ");
        assertThat(translateCalls()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from inquiries, jsonb_object_keys(translations) k where id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void 本人の言語と同じ言語ならINVALID_STATE() throws Exception {
        String ja = filedNow(HOW_TO, null);
        String en = englishFiled(HOW_TO);

        translate(ja, "ja").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        translate(en, "en").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        // 見る人の言語が決まらなければ既定の言語（ja）
        mvc.perform(post("/api/operator/inquiries/" + ja + "/translation")).andExpect(status().isConflict());
        assertThat(translateCalls()).isZero();
    }

    @Test
    void 担当者に渡したことのない件はNOT_FOUND() throws Exception {
        translate(idOf(start()), "ja").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 訳が失敗するとAI_UNAVAILABLE_訳は保存されず_失敗した呼び出しが利用量に残る() throws Exception {
        String id = englishFiled("[訳エラー] " + HOW_TO);

        translate(id, "ja").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        assertThat(row(id).get("translations").toString()).isEqualTo("{}");
        assertThat((List<Boolean>) JsonPath.read(detail(id), "$.trace[?(@.skill=='translator')].failed")).containsExactly(true);
    }

    @Test
    void 状態とメッセージと既読は変わらない() throws Exception {
        String id = englishFiled(HOW_TO);
        mvc.perform(post("/api/operator/inquiries/" + id + "/read")).andExpect(status().isOk());
        var before = row(id);

        translate(id, "ja").andExpect(status().isOk());

        var after = row(id);
        for (String column : List.of(
                "phase", "turn", "awaiting", "ending", "handover_reason", "asker_unread_reply", "read_at", "messages", "history")) {
            assertThat(String.valueOf(after.get(column))).as(column).isEqualTo(String.valueOf(before.get(column)));
        }
    }

    @Test
    void 本人向けのInquiryViewとtraceと担当者向けの詳細のGETに訳は出ない() throws Exception {
        String id = englishFiled(HOW_TO);
        translate(id, "ja").andExpect(status().isOk());

        String asker = view(id);
        String operator = detail(id);

        assertThat(asker).doesNotContain("translat").doesNotContain(MARK);
        assertThat((List<String>) JsonPath.read(asker, "$.trace[*].skill")).doesNotContain("translator");
        // 担当者向けの詳細は、原文だけ。訳は、この口の応答にだけ含める。実行記録には訳の呼び出しが入る
        assertThat(operator).doesNotContain("\"translations\"").doesNotContain(MARK);
        assertThat((List<String>) JsonPath.read(operator, "$.trace[*].skill")).contains("translator");
        mvc.perform(get("/api/inquiries")).andExpect(status().isOk());
    }

    // ---- 補助 ----

    private void operatorAsk(String id) throws Exception {
        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "詳しく教えてください")))
                .andExpect(status().isOk());
    }

    /** 本人が追記する。追記したメッセージの識別子を返す。 */
    private String askerReplies(String id, String body) throws Exception {
        // 本人が状態を変える操作は、問い合わせの言語をそのリクエストの言語に合わせ直すので、en で追記する
        String view = text(mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("body", body)))
                .andExpect(status().isOk()));
        List<String> ids = JsonPath.read(view, "$.messages[?(@.from=='ASKER')].id");
        return ids.get(ids.size() - 1);
    }
}
