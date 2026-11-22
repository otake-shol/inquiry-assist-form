package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import dev.inquiryassist.core.lifecycle.Turn;
import dev.inquiryassist.core.skill.Draft;
import dev.inquiryassist.core.skill.DrafterSkill;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 下書きの利用量の保存が、ほかの操作と競合しても、下書きは返すことを、DB なしで確かめる。 */
class DraftUsageConflictTest {

    @Test
    void 利用量の保存が競合しても下書きは返す() {
        InquiryRepository repository = mock(InquiryRepository.class);
        InquiryCommitter committer = mock(InquiryCommitter.class);
        DrafterSkill drafter = mock(DrafterSkill.class);
        InquiryEntity inquiry = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        inquiry.handedOverAt = Instant.now();
        inquiry.phase = Phase.KNOW;
        inquiry.turn = Turn.OPERATOR;
        inquiry.awaiting = Awaiting.HANDLING;
        when(repository.findById(inquiry.id)).thenReturn(Optional.of(inquiry));
        when(drafter.draft(any())).thenReturn(new AiResult<>(new Draft("下書き"), Usage.ofMock()));
        when(committer.update(any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(InquiryEntity.class, inquiry.id));
        InquiryProperties properties = new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, java.time.Duration.ofSeconds(5)),
                null,
                Map.of(),
                new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("利用者", "担当者"),
                Map.of(),
                InquiryProperties.Rules.defaults());
        OperatorService service = new OperatorService(
                repository,
                properties,
                SkillImplementations.from(new MockEnvironment()),
                mock(InquiryPresenter.class),
                new IntakeLocales(properties),
                mock(ApplicationEventPublisher.class),
                mock(InvestigationRunner.class),
                drafter,
                committer);

        DraftResult result = service.draft(inquiry.id, ReplyKind.ASK, null);

        assertThat(result).isEqualTo(new DraftResult("下書き", "ja"));
    }
}
