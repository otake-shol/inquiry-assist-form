package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** スキルの実行記録（trace）と、利用者の表示を返す設定を確認する（既定の設定。trace は有効）。 */
class TraceApiTest extends ApiSupport {

    @Test
    void traceは実行した順で_モックの呼び出しはmockで時間は0以上() throws Exception {
        String id = toAnswering("期限の仕様", null);
        stream(id);

        String view = text(mvc.perform(get("/api/inquiries/" + id)).andExpect(status().isOk()));

        assertThat((List<String>) JsonPath.read(view, "$.trace[*].skill"))
                .containsExactly("reception", "interviewer", "restater", "investigator", "explainer");
        assertThat((List<String>) JsonPath.read(view, "$.trace[*].provider")).containsOnly("mock");
        assertThat((List<String>) JsonPath.read(view, "$.trace[*].model")).containsOnly("mock");
        assertThat((List<Boolean>) JsonPath.read(view, "$.trace[*].failed")).containsOnly(false);
        assertThat((List<Integer>) JsonPath.read(view, "$.trace[*].durationMillis"))
                .hasSize(5)
                .doesNotContainNull()
                .allMatch(d -> d >= 0);
    }

    @Test
    void オペレータ詳細にも同じtraceが入り_進捗ストリームのdoneにも入る() throws Exception {
        String id = toAnswering("期限の仕様", null);
        String done = stream(id);
        resolve(id, false).andExpect(status().isOk());

        assertThat(done).contains("\"trace\":[");
        // 担当者に渡した後の、担当者向けの案（助言）の呼び出しも、担当者の記録には入る（本人向けには入らない）
        mvc.perform(get("/api/operator/inquiries/" + id))
                .andExpect(jsonPath("$.trace.length()").value(6))
                .andExpect(jsonPath("$.trace[3].skill").value("investigator"))
                .andExpect(jsonPath("$.trace[3].provider").value("mock"))
                .andExpect(jsonPath("$.trace[4].skill").value("explainer"))
                .andExpect(jsonPath("$.trace[4].durationMillis").isNumber())
                .andExpect(jsonPath("$.trace[5].skill").value("advisor"))
                .andExpect(jsonPath("$.trace[5].provider").value("mock"));
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.trace.length()").value(5));
    }

    @Test
    void 失敗した呼び出しの時間も記録される() throws Exception {
        // 受付の失敗はエラーにならず、失敗した呼び出しとして記録に残る
        String id = idOf(create("[判定エラー] 期限の仕様"));

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.trace.length()").value(1))
                .andExpect(jsonPath("$.trace[0].skill").value("reception"))
                .andExpect(jsonPath("$.trace[0].failed").value(true))
                .andExpect(jsonPath("$.trace[0].provider").value("mock"))
                .andExpect(jsonPath("$.trace[0].durationMillis").isNumber());
    }

    @Test
    void 説明の失敗でも調査と失敗した説明の時間が残る() throws Exception {
        String id = toAnswering("[説明エラー] 期限の仕様", null);
        stream(id);

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(jsonPath("$.trace[?(@.skill=='explainer')].failed").value(true))
                .andExpect(jsonPath("$.trace[?(@.skill=='explainer')].durationMillis").isNotEmpty())
                .andExpect(jsonPath("$.trace[?(@.skill=='investigator')].durationMillis").isNotEmpty());
    }

    @Test
    void durationMillisのない既存の形のusagesを持つ行も読める() throws Exception {
        String id = idOf(create("期限の仕様"));
        // この項目を加える前に保存された形（durationMillis がない）
        jdbc.update(
                "update inquiries set usages = ?::jsonb where id = ?::uuid",
                "[{\"step\":\"reception\",\"model\":\"mock\",\"promptVersion\":\"mock\",\"inputTokens\":0,"
                        + "\"outputTokens\":0,\"cacheReadTokens\":0,\"mock\":true,\"failed\":false}]",
                id);

        mvc.perform(get("/api/inquiries/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.length()").value(1))
                .andExpect(jsonPath("$.trace[0].skill").value("reception"))
                .andExpect(jsonPath("$.trace[0].durationMillis").value(nullValue()));
        // 読んだ行に新しい呼び出しが加わっても保存できる（古い形と新しい形が1つの配列に混ざる）
        finishIntake(id, null);
        mvc.perform(get("/api/inquiries/" + id)).andExpect(jsonPath("$.trace[0].durationMillis").value(nullValue()));
        // 古い形の行を、担当者に送った後のオペレータ詳細でも読める
        confirm(id).andExpect(status().isOk());
        sendNow(id).andExpect(status().isOk());
        mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(jsonPath("$.trace[0].durationMillis").value(nullValue()));
    }

    @Test
    void configは利用者と担当者の表示名とtraceを返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace").value(true))
                .andExpect(jsonPath("$.user.id").value(FixedCurrentUserProvider.USER_ID))
                .andExpect(jsonPath("$.user.name").value("開発用ユーザー"))
                .andExpect(jsonPath("$.operatorName").value("担当者（開発用）"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.systems.length()").value(3));
    }
}
