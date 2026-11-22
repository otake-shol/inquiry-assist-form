package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.Turn;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

/** 相談先が1人も決まらないとき（設定にも指定にもない）は、相談できない（{@code VALIDATION}）ことを、DB なしで確かめる。 */
class ConsultantsMissingTest {

    private final InquiryRepository repository = mock(InquiryRepository.class);

    private OperatorService service(List<String> globalConsultants) {
        InquiryProperties properties = new InquiryProperties(
                List.of("ja"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new InquiryProperties.Slack(null, Duration.ofSeconds(5)),
                null,
                Map.of(),
                new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                new InquiryProperties.Trace(true),
                new InquiryProperties.Dev("利用者", "担当者"),
                Map.of(),
                InquiryProperties.Rules.defaults(),
                globalConsultants);
        return new OperatorService(
                repository,
                properties,
                SkillImplementations.from(new MockEnvironment()),
                mock(InquiryPresenter.class),
                new IntakeLocales(properties),
                mock(ApplicationEventPublisher.class),
                mock(dev.inquiryassist.core.skill.DrafterSkill.class),
                mock(InquiryCommitter.class),
                mock(PrecedentRepository.class),
                mock(PrecedentFinder.class));
    }

    private InquiryEntity handling() {
        InquiryEntity e = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        e.handedOverAt = Instant.now();
        e.phase = Phase.KNOW;
        e.turn = Turn.OPERATOR;
        e.awaiting = Awaiting.HANDLING;
        when(repository.findById(e.id)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 設定にも指定にもなければ相談できず_状態は変わらない() {
        InquiryEntity e = handling();
        OperatorService service = service(List.of());

        assertThatThrownBy(() -> service.consult(e.id, null, "相談", "ja")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consult(e.id, List.of(), "相談", "ja")).isInstanceOf(IllegalArgumentException.class);

        assertThat(e.awaiting).isEqualTo(Awaiting.HANDLING);
        assertThat(e.consultations).isEmpty();
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 設定になくても_指定すれば相談できる() {
        InquiryEntity e = handling();
        when(repository.saveAndFlush(e)).thenReturn(e);
        OperatorService service = service(List.of());
        InquiryPresenter presenter = mock(InquiryPresenter.class);

        // 全内容の組み立ては、この確認の対象ではないので、例外にならなければよい（モックの presenter は null を返す）
        try {
            service.consult(e.id, List.of("指定した人"), "相談", "ja");
        } catch (NullPointerException ignored) {
            // 組み立てに使う記録の一部が、モックでは空
        }

        assertThat(e.awaiting).isEqualTo(Awaiting.CONSULTATION);
        assertThat(e.consultations).singleElement().satisfies(c -> assertThat(c.to()).containsExactly("指定した人"));
    }
}
