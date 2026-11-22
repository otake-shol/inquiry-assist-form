package dev.inquiryassist.inquiry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * 手元のモデルの実装を外す前に保存した、モックでない利用量（実装の名前を持たず、モデルの名前とトークン数を持つ）を、これまでどおり
 * 読めることを確かめる。実行記録（trace）には、実装の名前（{@code ollama}）とモデルの名前が、保存した値のまま出る。
 * 本人向けと担当者向けのどちらでも読める。
 */
class LegacyUsageApiTest extends ApiSupport {

    /** 手元のモデルで動かしたころの形。実装の名前の項目はなく、{@code mock} が false。 */
    private static final String LEGACY_USAGES = "["
            + "{\"step\":\"reception\",\"model\":\"qwen3.6:35b\",\"promptVersion\":\"v2\",\"inputTokens\":77,\"outputTokens\":9,"
            + "\"cacheReadTokens\":0,\"mock\":false,\"failed\":false,\"durationMillis\":812},"
            + "{\"step\":\"restater\",\"model\":\"qwen3.6:35b\",\"promptVersion\":\"v2\",\"inputTokens\":310,\"outputTokens\":64,"
            + "\"cacheReadTokens\":0,\"mock\":false,\"failed\":false},"
            + "{\"step\":\"investigator\",\"model\":\"qwen3.6:35b\",\"promptVersion\":\"v1\",\"inputTokens\":2663,"
            + "\"outputTokens\":197,\"cacheReadTokens\":0,\"mock\":false,\"failed\":true,\"durationMillis\":2921},"
            + "{\"step\":\"explainer\",\"model\":\"mock\",\"promptVersion\":\"mock\",\"inputTokens\":0,\"outputTokens\":0,"
            + "\"cacheReadTokens\":0,\"mock\":true,\"failed\":false,\"durationMillis\":3}"
            + "]";

    @Test
    void 手元のモデルで動かした記録は_本人向けも担当者向けも読め_実装の名前とモデルの名前がそのまま出る() throws Exception {
        String id = filedNow("使い方を教えて", null);
        jdbc.update(
                "update inquiries set usages = ?::jsonb, mock = false where id = ?::uuid", LEGACY_USAGES, id);

        for (String path : new String[] {"/api/inquiries/" + id, "/api/operator/inquiries/" + id}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trace.length()").value(4))
                    .andExpect(jsonPath("$.trace[0].skill").value("reception"))
                    .andExpect(jsonPath("$.trace[0].provider").value("ollama"))
                    .andExpect(jsonPath("$.trace[0].model").value("qwen3.6:35b"))
                    .andExpect(jsonPath("$.trace[0].promptVersion").value("v2"))
                    .andExpect(jsonPath("$.trace[0].inputTokens").value(77))
                    .andExpect(jsonPath("$.trace[0].outputTokens").value(9))
                    .andExpect(jsonPath("$.trace[0].durationMillis").value(812))
                    .andExpect(jsonPath("$.trace[0].failed").value(false))
                    // 時間を持たない形も読める
                    .andExpect(jsonPath("$.trace[1].provider").value("ollama"))
                    .andExpect(jsonPath("$.trace[1].durationMillis").doesNotExist())
                    .andExpect(jsonPath("$.trace[2].provider").value("ollama"))
                    .andExpect(jsonPath("$.trace[2].model").value("qwen3.6:35b"))
                    .andExpect(jsonPath("$.trace[2].failed").value(true))
                    // モックの呼び出しは、これまでどおり mock
                    .andExpect(jsonPath("$.trace[3].provider").value("mock"));
        }
        // 本人向けの記録は、モックでないことを示す
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.mock").value(false));
        // 設定の応答は、すべてモック
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.skills.reception").value("mock"))
                .andExpect(jsonPath("$.skills.advisor").value("mock"))
                .andExpect(jsonPath("$.skills.translator").value("mock"));
    }

    @Test
    void 実装の名前を保存した記録は_設定が変わった後も保存した名前のまま出る_名前のない記録は読み替える() throws Exception {
        String id = filedNow("使い方を教えて", null);
        // 設定上の実装はすべて mock。保存した名前が、bedrock と、後から差し替えた custom の記録と、名前のない古い記録
        jdbc.update(
                "update inquiries set usages = ?::jsonb where id = ?::uuid",
                "["
                        + "{\"step\":\"investigator\",\"model\":\"m1\",\"promptVersion\":\"v1\",\"inputTokens\":1,\"outputTokens\":1,"
                        + "\"cacheReadTokens\":0,\"mock\":false,\"failed\":false,\"durationMillis\":5,\"provider\":\"bedrock\"},"
                        + "{\"step\":\"investigator\",\"model\":\"m2\",\"promptVersion\":\"v1\",\"inputTokens\":1,\"outputTokens\":1,"
                        + "\"cacheReadTokens\":0,\"mock\":false,\"failed\":false,\"durationMillis\":5,\"provider\":\"custom\"},"
                        + "{\"step\":\"explainer\",\"model\":\"qwen3.6:35b\",\"promptVersion\":\"v2\",\"inputTokens\":1,\"outputTokens\":1,"
                        + "\"cacheReadTokens\":0,\"mock\":false,\"failed\":false}"
                        + "]",
                id);

        for (String path : new String[] {"/api/inquiries/" + id, "/api/operator/inquiries/" + id}) {
            mvc.perform(get(path))
                    .andExpect(jsonPath("$.trace[0].provider").value("bedrock"))
                    .andExpect(jsonPath("$.trace[1].provider").value("custom"))
                    .andExpect(jsonPath("$.trace[2].provider").value("ollama"));
        }
    }

    @Test
    void 新しい記録は_記録した時点のスキルごとの実装の名前を保存する() throws Exception {
        String id = filedNow("使い方を教えて", null);

        java.util.List<String> providers = jdbc.queryForList(
                "select u->>'provider' from inquiries, jsonb_array_elements(usages) u where id = ?::uuid", String.class, id);

        org.assertj.core.api.Assertions.assertThat(providers).isNotEmpty().containsOnly("mock");
    }
}
