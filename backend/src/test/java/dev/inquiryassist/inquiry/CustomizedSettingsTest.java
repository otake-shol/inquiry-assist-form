package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** 実行記録を無効にし、表示名を差し替えた設定（本番に近い構成）での応答を確認する。 */
@TestPropertySource(properties = {
    "inquiry.trace.enabled=false",
    "inquiry.dev.user-name=テスト利用者",
    "inquiry.dev.operator-name=テスト担当者"
})
class CustomizedSettingsTest extends ApiSupport {

    @Test
    void traceを無効にするとInquiryViewとオペレータ詳細のtraceはnullでconfigのtraceはfalse() throws Exception {
        String id = toAnswering("期限の仕様", null);
        stream(id);
        resolve(id, false).andExpect(jsonPath("$.trace").value(nullValue()));

        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.trace").value(nullValue()));
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.trace").value(nullValue()));
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.trace").value(false));
        // 記録そのものは続ける（画面に見せないだけ）
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(usages) from inquiries where id = ?::uuid", Integer.class, id))
                .isEqualTo(5);
    }

    @Test
    void 表示名は設定の値を返し_メッセージには投稿した時点の名前が保存される() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.user.name").value("テスト利用者"))
                .andExpect(jsonPath("$.user.id").value(FixedCurrentUserProvider.USER_ID))
                .andExpect(jsonPath("$.operatorName").value("テスト担当者"));
        String id = idOf(create("期限の仕様", null));
        sendNow(id);

        mvc.perform(post("/api/operator/inquiries/" + id + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"返信\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"追記\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList(
                        "select m->>'authorName' from inquiries, jsonb_array_elements(messages) with ordinality as t(m, ord)"
                                + " where id = ?::uuid order by ord",
                        String.class,
                        id))
                .containsExactly("テスト担当者", "テスト利用者");
    }
}
