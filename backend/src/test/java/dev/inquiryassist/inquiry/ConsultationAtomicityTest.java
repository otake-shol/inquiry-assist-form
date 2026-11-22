package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 先例の作成と、問い合わせの状態の変更が、同じトランザクションで行われる（片方だけが残らない）ことを確かめる。
 * 先例の挿入の後に、問い合わせの更新が DB の制約で失敗する状況を作り、先例が残らないことを見る。
 */
class ConsultationAtomicityTest extends ApiSupport {

    @Test
    void 先例の挿入の後に問い合わせの更新が失敗したら_先例も残らず_状態も変わらない() throws Exception {
        String id = filedNow("使い方を教えて", null);
        mvc.perform(post("/api/operator/inquiries/" + id + "/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("question", "相談")))
                .andExpect(status().isOk());
        // 決めると、渡した理由が DECIDED になる。この値の更新だけを失敗させる制約を、一時的に足す（既存の行は検査しない。ほかのテストの行に影響しないため）
        jdbc.execute("alter table inquiries add constraint tmp_block_decided check (handover_reason is distinct from 'DECIDED') not valid");
        try {
            assertThatThrownBy(() -> mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("decisionKind", "ACCEPT", "decision", "決定", "reason", "理由", "decidedBy", "持ち主"))))
                    .isInstanceOf(Exception.class);
        } finally {
            jdbc.execute("alter table inquiries drop constraint tmp_block_decided");
        }

        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id)).isZero();
        String detail = text(mvc.perform(get("/api/operator/inquiries/" + id)).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(detail, "$.awaiting")).isEqualTo("CONSULTATION");
        assertThat((String) JsonPath.read(detail, "$.consultations[0].status")).isEqualTo("OPEN");
        assertThat((List<String>) JsonPath.read(detail, "$.history[-1:].event")).containsExactly("CONSULTED");
        // 制約を外した後は、同じ決定が通る
        mvc.perform(post("/api/owner/inquiries/" + id + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("decisionKind", "ACCEPT", "decision", "決定", "reason", "理由", "decidedBy", "持ち主")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from precedents where inquiry_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    }
}
