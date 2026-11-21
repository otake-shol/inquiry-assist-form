package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 対象システムの選択・推測・変更と、整形結果への反映を確認する。 */
class SystemApiTest extends ApiSupport {

    private ResultActions putSystem(String id, String body) throws Exception {
        return mvc.perform(put("/api/inquiries/" + id + "/system").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void configは設定の対象システム一覧を返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.systems[*].id").value(Matchers.contains("job-admin", "applicant-list", "billing")))
                .andExpect(jsonPath("$.systems[0].label").value("求人管理画面"));
    }

    @Test
    void 本人が選んだ対象システムはそのまま使われ推測ではない() throws Exception {
        String created = create("期限の仕様を教えて", "job-admin");

        assertThat((String) JsonPath.read(created, "$.system.id")).isEqualTo("job-admin");
        assertThat((String) JsonPath.read(created, "$.system.label")).isEqualTo("求人管理画面");
        assertThat((Boolean) JsonPath.read(created, "$.systemGuessed")).isFalse();
    }

    @Test
    void 選ばなければ発話から推測し推測であることが分かる() throws Exception {
        String created = create("請求管理の期限の仕様を教えて", null);

        assertThat((String) JsonPath.read(created, "$.system.id")).isEqualTo("billing");
        assertThat((Boolean) JsonPath.read(created, "$.systemGuessed")).isTrue();
    }

    @Test
    void 推測できなければ対象システムはnullで推測ではない() throws Exception {
        String created = create("期限の仕様を教えて", null);

        assertThat((Object) JsonPath.read(created, "$.system")).isNull();
        assertThat((Boolean) JsonPath.read(created, "$.systemGuessed")).isFalse();
    }

    @Test
    void 本人が選んだ場合は発話に別の名前があっても推測結果を使わない() throws Exception {
        String created = create("請求管理の期限の仕様を教えて", "job-admin");

        assertThat((String) JsonPath.read(created, "$.system.id")).isEqualTo("job-admin");
        assertThat((Boolean) JsonPath.read(created, "$.systemGuessed")).isFalse();
    }

    @Test
    void 一覧にないsystemIdは400で問い合わせは保存されない() throws Exception {
        mvc.perform(post("/api/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"utterance\":\"保存されない発話\",\"systemId\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        assertThat(jdbc.queryForObject(
                        "select count(*) from inquiries where utterance = '保存されない発話'", Integer.class))
                .isZero();
    }

    @Test
    void 対象システムが決まっていると画面を尋ねる質問は出ない() throws Exception {
        String selected = create("期限の仕様を教えて", "billing");
        String guessed = create("請求管理の期限の仕様を教えて", null);
        String unknown = create("期限の仕様を教えて", null);

        assertThat((List<String>) JsonPath.read(selected, "$.questions[*].id")).containsExactly("q2");
        assertThat((List<String>) JsonPath.read(guessed, "$.questions[*].id")).containsExactly("q2");
        assertThat((List<String>) JsonPath.read(unknown, "$.questions[*].id")).containsExactly("q1", "q2");
    }

    @Test
    void PUT_systemで直すと推測ではなくなりnullで未定に戻せる() throws Exception {
        String id = idOf(create("請求管理の期限の仕様を教えて", null));

        putSystem(id, "{\"systemId\":\"applicant-list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system.id").value("applicant-list"))
                .andExpect(jsonPath("$.system.label").value("応募者向けの一覧"))
                .andExpect(jsonPath("$.systemGuessed").value(false));
        putSystem(id, "{\"systemId\":null}")
                .andExpect(jsonPath("$.system").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.systemGuessed").value(false));
        putSystem(id, "{\"systemId\":\"unknown\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        assertThat(jdbc.queryForObject("select system_id from inquiries where id = ?::uuid", String.class, id))
                .isNull();
    }

    @Test
    void PUT_systemはTRIAGED以外では409になる() throws Exception {
        String structured = idOf(create("期限の仕様", null));
        answers(structured, EMPTY_ANSWERS);
        String closed = idOf(create("期限の仕様", null));
        sendNow(closed);

        putSystem(structured, "{\"systemId\":\"billing\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        putSystem(closed, "{\"systemId\":\"billing\"}").andExpect(status().isConflict());
    }

    @Test
    void 確定した対象システムのlabelがstructuredのtargetSystemに入り作り直しでも保たれる() throws Exception {
        String id = idOf(create("期限の仕様を教えて", "job-admin"));

        answers(id, EMPTY_ANSWERS).andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        mvc.perform(post("/api/inquiries/" + id + "/revision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"違う\"}"))
                .andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        confirm(id).andExpect(jsonPath("$.structured.targetSystem").value("求人管理画面"));
        assertThat(jdbc.queryForObject(
                        "select structured_confirmed->>'targetSystem' from inquiries where id = ?::uuid",
                        String.class,
                        id))
                .isEqualTo("求人管理画面");
    }

    @Test
    void 推測された対象システムもstructuredのtargetSystemに入り未定ならnullのまま() throws Exception {
        String guessed = idOf(create("請求管理の期限の仕様", null));
        String unknown = idOf(create("期限の仕様", null));

        answers(guessed, EMPTY_ANSWERS).andExpect(jsonPath("$.structured.targetSystem").value("請求管理"));
        answers(unknown, EMPTY_ANSWERS).andExpect(jsonPath("$.structured.targetSystem").value(Matchers.nullValue()));
    }

    @Test
    void 判定に失敗した問い合わせでも本人が選んだ対象システムは残る() throws Exception {
        String body = text(mvc.perform(post("/api/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"utterance\":\"[判定エラー] x\",\"systemId\":\"billing\"}"))
                .andExpect(status().isServiceUnavailable()));
        String id = JsonPath.read(body, "$.inquiryId");

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.system.id").value("billing"))
                .andExpect(jsonPath("$.systemGuessed").value(false));
        putSystem(id, "{\"systemId\":null}").andExpect(status().isConflict());
    }
}
