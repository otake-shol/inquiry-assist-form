package dev.inquiryassist.inquiry;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** {@code GET /api/config} の、種類、緊急度、システムと機能、言語（契約書 1）。名前は既定の言語（ja）のもの。 */
class ConfigApiTest extends ApiSupport {

    @Test
    void 言語は設定の順で返り先頭が既定の言語() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languages").value(contains("ja", "en")));
    }

    @Test
    void 種類は5つを聞く順に名前と説明つきで返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.types[*].id")
                        .value(contains("HOW_TO", "HOW_IT_WORKS", "LOOKS_WRONG", "DO_FOR_ME", "CHANGE_REQUEST")))
                .andExpect(jsonPath("$.types[*].label")
                        .value(contains("使い方を知りたい", "仕組みを知りたい", "おかしいかもしれない", "やってほしい", "変えてほしい")))
                .andExpect(jsonPath("$.types[0].hint").value("操作の手順"))
                .andExpect(jsonPath("$.types[4].hint").value("機能の追加や改善"));
    }

    @Test
    void 緊急度は3つを返す() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.urgencies[*].id").value(contains("NORMAL", "HURRY", "BLOCKED")))
                .andExpect(jsonPath("$.urgencies[*].label").value(contains("通常", "急ぎ", "業務が止まっている")));
    }

    @Test
    void システムは機能の一覧つきで返し_機能の設定がないシステムは空() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.systems[*].id").value(contains("job-admin", "billing", "reports")))
                .andExpect(jsonPath("$.systems[0].label").value("求人管理画面"))
                .andExpect(jsonPath("$.systems[0].functions[*].id").value(contains("posting", "applicants")))
                .andExpect(jsonPath("$.systems[0].functions[0].label").value("求人の掲載"))
                .andExpect(jsonPath("$.systems[1].functions[0].label").value("請求書"))
                .andExpect(jsonPath("$.systems[2].functions").isEmpty());
    }

    @Test
    void 廃止した項目は返さない() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.incidentGuidance").doesNotExist())
                .andExpect(jsonPath("$.systems[0].slackWebhookUrl").doesNotExist());
    }
}
