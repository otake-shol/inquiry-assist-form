package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import dev.inquiryassist.TestcontainersConfiguration;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryAssistant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 言い直しの作り直しで AI が失敗したとき、残り回数を減らさず一言も保存しないことを確認する。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RevisionFailureTest {

    @Autowired
    InquiryService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    InquiryAssistant assistant;

    @Test
    void AIが失敗した作り直しは残り回数を減らさず一言も残さない() {
        var id = service.create("期限の仕様", null).id();
        service.answerQuestions(id, List.of());

        doThrow(new AiUnavailableException("失敗", null)).when(assistant).revise(any(), any(), any());
        assertThatThrownBy(() -> service.revise(id, "違う")).isInstanceOf(AiUnavailableException.class);
        doThrow(new AiRefusalException("拒否")).when(assistant).revise(any(), any(), any());
        assertThatThrownBy(() -> service.revise(id, "違う")).isInstanceOf(AiRefusalException.class);

        assertThat(service.get(id).revisionsLeft()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(revision_notes) from inquiries where id = ?::uuid",
                        Integer.class,
                        id.toString()))
                .isZero();

        doCallRealMethod().when(assistant).revise(any(), any(), any());
        assertThat(service.revise(id, "違う").revisionsLeft()).isEqualTo(1);
    }
}
