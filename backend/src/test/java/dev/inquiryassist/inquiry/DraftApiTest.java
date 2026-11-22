package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 返信の下書き（契約書「担当者の操作」の「返信の下書きを作る」）を API から確かめる。下書きは、状態を変えず、何も送らない。 */
class DraftApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";
    private static final String LEAD = "お待たせしました。確認した結果をお伝えします。";

    private ResultActions draft(String id, String body) throws Exception {
        return mvc.perform(post("/api/operator/inquiries/" + id + "/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String detail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
    }

    @Test
    void 聞き返しの下書きはnoteなしでもありでも書け_本人の言語を返す() throws Exception {
        String id = filedNow(HOW_TO, null);

        draft(id, json("kind", "ASK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("確認させてください。起きている場面を、もう少し詳しく教えていただけますか。"))
                .andExpect(jsonPath("$.language").value("ja"));
        draft(id, json("kind", "ASK", "note", "画面名を教えてください"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("確認させてください。画面名を教えてください"));
    }

    @Test
    void 結果の下書きは_note_判断の案_事実の順で入る() throws Exception {
        String id = filedNow(HOW_TO, null);

        draft(id, json("kind", "RESULT", "note", "仕様どおりです"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(LEAD + "仕様どおりです"));
        // note がなければ、判断の案の文章
        draft(id, json("kind", "RESULT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(LEAD + "資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。"));
        // 判断の案がなければ、1件目の事実の文
        String noJudgment = filedNow("[根拠なしの案] " + HOW_TO, null);
        List<String> findings = JsonPath.read(detail(noJudgment), "$.answer.memo.findings");
        draft(noJudgment, json("kind", "RESULT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(LEAD + findings.get(0)));
    }

    @Test
    void 本人の言語が英語の件は英語で書く() throws Exception {
        String id = filedNow(HOW_TO, null);
        jdbc.update("update inquiries set language = 'en' where id = ?::uuid", id);

        draft(id, json("kind", "ASK", "note", "Which screen?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Let me check one thing. Which screen?"))
                .andExpect(jsonPath("$.language").value("en"));
        draft(id, json("kind", "RESULT", "note", "It works as documented."))
                .andExpect(jsonPath("$.body").value("Thank you for waiting. Here is what we found. It works as documented."));
    }

    @Test
    void 下書きは状態を変えず_メッセージを足さず_利用量だけを記録する() throws Exception {
        String id = filedNow(HOW_TO, null);
        var before = row(id);
        int messagesBefore = (int) jdbc.queryForObject(
                "select jsonb_array_length(messages) from inquiries where id = ?::uuid", Integer.class, id);
        int usagesBefore = (int) jdbc.queryForObject(
                "select jsonb_array_length(usages) from inquiries where id = ?::uuid", Integer.class, id);

        draft(id, json("kind", "RESULT", "note", "対応しました")).andExpect(status().isOk());

        var after = row(id);
        for (String column : List.of("phase", "turn", "awaiting", "ending", "handover_reason", "asker_unread_reply", "read_at")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        assertThat((int) jdbc.queryForObject(
                        "select jsonb_array_length(messages) from inquiries where id = ?::uuid", Integer.class, id))
                .isEqualTo(messagesBefore);
        assertThat((int) jdbc.queryForObject(
                        "select jsonb_array_length(usages) from inquiries where id = ?::uuid", Integer.class, id))
                .isEqualTo(usagesBefore + 1);
        assertThat(usageSteps(id)).endsWith("advisor", "drafter");
        // 履歴にも入らない
        assertThat((List<String>) JsonPath.read(detail(id), "$.history[*].event")).doesNotContain("DRAFTED");
    }

    @Test
    void 担当者の番でないときと終わった件はINVALID_STATE_渡したことのない件はNOT_FOUND() throws Exception {
        String asked = filedNow(HOW_TO, null);
        mvc.perform(post("/api/operator/inquiries/" + asked + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("kind", "ASK", "body", "詳しく教えてください")))
                .andExpect(status().isOk());
        draft(asked, json("kind", "ASK"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        String declined = filedNow(HOW_TO, null);
        mvc.perform(post("/api/operator/inquiries/" + declined + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reasonKind", "OUT_OF_SCOPE", "reason", "対象外です")))
                .andExpect(status().isOk());
        draft(declined, json("kind", "RESULT")).andExpect(status().isConflict());

        draft(idOf(start()), json("kind", "ASK")).andExpect(status().isNotFound());
    }

    @Test
    void 下書きが失敗するとAI_UNAVAILABLE_失敗した呼び出しとして利用量に残る() throws Exception {
        String id = filedNow("[下書きエラー] " + HOW_TO, null);

        draft(id, json("kind", "ASK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        assertThat((List<Boolean>) JsonPath.read(detail(id), "$.trace[?(@.skill=='drafter')].failed")).containsExactly(true);
        assertThat((String) JsonPath.read(detail(id), "$.awaiting")).isEqualTo("HANDLING");
    }

    @Test
    void 入力の誤りはVALIDATION() throws Exception {
        String id = filedNow(HOW_TO, null);

        draft(id, json("kind", "ASK", "note", "   ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        draft(id, json("kind", "ASK", "note", "あ".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        draft(id, json("note", "kind がない")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        draft(id, json("kind", "DECLINE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 前後の空白は除く。2000文字ちょうどは受け付ける
        draft(id, json("kind", "ASK", "note", "  質問です  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("確認させてください。質問です"));
        draft(id, json("kind", "ASK", "note", "あ".repeat(2000))).andExpect(status().isOk());
    }
}
