package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 説明が出す手順（契約書「InquiryView」の {@code answer.steps}）を API から確かめる。 */
class AnswerStepsApiTest extends ApiSupport {

    private static final String HOW_TO = "使い方を教えて";

    private String operatorDetail(String id) throws Exception {
        return text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
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

    @Test
    void 使い方の答えに手順が入り_本人向けにも担当者向けにも返る() throws Exception {
        String id = toAnswering(HOW_TO, null);

        String done = doneOf(stream(id));

        assertThat((String) JsonPath.read(done, "$.type.id")).isEqualTo("HOW_TO");
        assertThat((List<String>) JsonPath.read(done, "$.answer.steps[*].action"))
                .containsExactly("求人管理画面で、対象の求人の編集を開く", "掲載期限を変えて、保存する");
        assertThat((Object) JsonPath.read(done, "$.answer.steps[0].check")).isNull();
        assertThat((String) JsonPath.read(done, "$.answer.steps[1].check")).isEqualTo("応募者向けの一覧に、求人が表示される");
        // 取得（9）でも同じ
        assertThat((List<String>) JsonPath.read(view(id), "$.answer.steps[*].action")).hasSize(2);
        // 担当者に渡せば、担当者向けの詳細にも返る
        sendNow(id).andExpect(status().isOk());
        assertThat((List<String>) JsonPath.read(operatorDetail(id), "$.answer.steps[*].action")).hasSize(2);
    }

    @Test
    void 仕組みとおかしいかもしれないは手順が空() throws Exception {
        String howItWorks = toAnswering("期限の仕様", null);
        String wrong = toAnswering("表示がおかしい", null);

        assertThat((List<Object>) JsonPath.read(doneOf(stream(howItWorks)), "$.answer.steps")).isEmpty();
        assertThat((List<Object>) JsonPath.read(doneOf(stream(wrong)), "$.answer.steps")).isEmpty();
        assertThat((List<Object>) JsonPath.read(operatorDetail(wrong), "$.answer.steps")).isEmpty();
    }

    @Test
    void 手順なしのタグと的外れでは手順が空_説明の文章は残る() throws Exception {
        String noSteps = toAnswering("[手順なし] " + HOW_TO, null);
        String offTopic = toAnswering("[的外れ] " + HOW_TO, null);

        String noStepsDone = doneOf(stream(noSteps));
        String offTopicDone = doneOf(stream(offTopic));

        assertThat((List<Object>) JsonPath.read(noStepsDone, "$.answer.steps")).isEmpty();
        assertThat((String) JsonPath.read(noStepsDone, "$.awaiting")).isEqualTo("RESULT_CONFIRMATION");
        assertThat((String) JsonPath.read(noStepsDone, "$.answer.askerExplanation")).isNotBlank();
        assertThat((Boolean) JsonPath.read(offTopicDone, "$.answer.answered")).isFalse();
        assertThat((List<Object>) JsonPath.read(offTopicDone, "$.answer.steps")).isEmpty();
        assertThat((String) JsonPath.read(offTopicDone, "$.answer.askerExplanation")).isNotBlank();
    }

    @Test
    void 根拠なしの答えの手順は空() throws Exception {
        String id = toAnswering("[根拠なし] " + HOW_TO, null);

        String done = doneOf(stream(id));

        assertThat((Boolean) JsonPath.read(done, "$.answer.grounded")).isFalse();
        assertThat((List<Object>) JsonPath.read(done, "$.answer.steps")).isEmpty();
    }

    @Test
    void 裏での調査の結果の手順は担当者向けにだけ返り_本人向けではanswerごとnull() throws Exception {
        String id = toAnswering(HOW_TO, null);
        // 答えを待たずに送る（調べていない間に送ると、裏で調査が始まり、結果は担当者だけに届く）
        sendNow(id).andExpect(status().isOk());

        assertThat((Object) JsonPath.read(view(id), "$.answer")).isNull();
        assertThat((List<String>) JsonPath.read(operatorDetail(id), "$.answer.steps[*].action")).hasSize(2);
    }

    @Test
    void 説明だけが失敗した場合は手順も空で保存され_担当者向けにも空() throws Exception {
        String id = toAnswering("[説明エラー] " + HOW_TO, null);
        stream(id);
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(answer_steps) from inquiries where id = ?::uuid", Integer.class, id))
                .isZero();

        sendNow(id).andExpect(status().isOk());

        String detail = operatorDetail(id);
        assertThat((Boolean) JsonPath.read(detail, "$.answer.grounded")).isFalse();
        assertThat((List<Object>) JsonPath.read(detail, "$.answer.steps")).isEmpty();
    }
}
