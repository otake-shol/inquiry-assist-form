package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 既定の投稿先がなく、一部のシステムだけに投稿先がある設定での Slack の状態を確認する。 */
class SlackSystemOnlyTest extends ApiSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // リストは丸ごと置き換わるため、2つのシステムを与える。job-admin だけに投稿先がある
        registry.add("inquiry.systems[0].id", () -> "job-admin");
        registry.add("inquiry.systems[0].label.ja", () -> "求人管理画面");
        // 接続できないローカルのポート。このテストでは投稿の成否を見ない
        registry.add("inquiry.systems[0].slack-webhook-url", () -> "http://127.0.0.1:1/unused");
        registry.add("inquiry.systems[1].id", () -> "billing");
        registry.add("inquiry.systems[1].label.ja", () -> "請求管理");
        registry.add("inquiry.slack.timeout", () -> "1s");
    }

    private String slackStatusOf(String id) throws Exception {
        return JsonPath.read(text(mvc.perform(get("/api/operator/inquiries/" + id))), "$.slackStatus");
    }

    @Test
    void 投稿先のない別システムの問い合わせはSKIPPEDで対象未定もSKIPPEDになる() throws Exception {
        String other = filedNow("期限の仕様", "billing");
        String unknown = filedNow("期限の仕様", null);

        assertThat(slackStatusOf(other)).isEqualTo("SKIPPED");
        assertThat(slackStatusOf(unknown)).isEqualTo("SKIPPED");
    }

    @Test
    void 投稿先のあるシステムの問い合わせはSKIPPEDにならない() throws Exception {
        String id = filedNow("期限の仕様", "job-admin");

        assertThat(waitFor(
                        () -> {
                            try {
                                return !slackStatusOf(id).equals("PENDING");
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        8000))
                .isTrue();
        assertThat(slackStatusOf(id)).isEqualTo("FAILED");
    }
}
