package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.Phase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 判定を回す処理の、競合した件を次の回に回すことと、1件の失敗で他の件を止めないことを、DB なしで確かめる。 */
class TimeRuleServiceTest {

    /** 月曜 12:00（東京）。5営業日後は翌週の月曜。 */
    private static final Instant SINCE = Instant.parse("2026-01-05T03:00:00Z");
    private static final Instant REMIND_AT = Instant.parse("2026-01-12T03:00:00Z");

    private final InquiryRepository repository = mock(InquiryRepository.class);
    private final InquiryPresenter presenter = mock(InquiryPresenter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final TimeRuleService service = new TimeRuleService(
            repository,
            new InquiryProperties(
                    List.of("ja"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new InquiryProperties.Slack(null, java.time.Duration.ofSeconds(5)),
                    null,
                    java.util.Map.of(),
                    new InquiryProperties.Ollama("http://localhost:11434", "30m", 42),
                    new InquiryProperties.Trace(true),
                    new InquiryProperties.Dev("利用者", "担当者"),
                    java.util.Map.of(),
                    InquiryProperties.Rules.defaults()),
            presenter,
            transaction,
            events);

    private static InquiryEntity waiting(Instant since, Instant remindedAt) {
        InquiryEntity e = new InquiryEntity("u", Urgency.NORMAL, since);
        e.phase = Phase.KNOW;
        e.awaiting = Awaiting.RESULT_CONFIRMATION;
        e.awaitingSince = since;
        e.remindedAt = remindedAt;
        return e;
    }

    @SuppressWarnings("unchecked")
    private void runCallbacksDirectly() {
        when(transaction.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    void 競合した件は次の回に回し_ほかの件は続けて処理する() {
        runCallbacksDirectly();
        InquiryEntity conflicted = waiting(SINCE, null);
        InquiryEntity fine = waiting(SINCE, null);
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(conflicted.id, fine.id));
        when(repository.findById(conflicted.id)).thenReturn(Optional.of(conflicted));
        when(repository.findById(fine.id)).thenReturn(Optional.of(fine));
        when(repository.saveAndFlush(conflicted)).thenThrow(new ObjectOptimisticLockingFailureException(InquiryEntity.class, conflicted.id));
        when(repository.saveAndFlush(fine)).thenReturn(fine);

        TimeRuleService.Result result = service.run(REMIND_AT);

        assertThat(result).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat(fine.remindedAt).isEqualTo(REMIND_AT);
        assertThat(fine.askerUnreadReply).isTrue();
    }

    @Test
    void 一件の想定外の失敗でも残りの件の判定を止めない() {
        runCallbacksDirectly();
        InquiryEntity broken = waiting(SINCE, null);
        InquiryEntity fine = waiting(SINCE, null);
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(broken.id, fine.id));
        when(repository.findById(broken.id)).thenThrow(new IllegalStateException("壊れた行"));
        when(repository.findById(fine.id)).thenReturn(Optional.of(fine));
        when(repository.saveAndFlush(fine)).thenReturn(fine);

        assertThat(service.run(REMIND_AT)).isEqualTo(new TimeRuleService.Result(1, 0));
    }

    @Test
    void 待っていることが変わっていた件は何もしない() {
        runCallbacksDirectly();
        InquiryEntity changed = waiting(SINCE, null);
        changed.awaiting = Awaiting.HANDLING;
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(changed.id));
        when(repository.findById(changed.id)).thenReturn(Optional.of(changed));

        assertThat(service.run(REMIND_AT.plusSeconds(86400L * 100))).isEqualTo(new TimeRuleService.Result(0, 0));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void 終えた件のうち担当者に渡したことのある件だけストリームへ更新を流す() {
        runCallbacksDirectly();
        Instant closesAt = Instant.parse("2026-01-19T03:00:00Z");
        InquiryEntity handed = waiting(SINCE, REMIND_AT);
        handed.handedOverAt = SINCE;
        InquiryEntity neverHanded = waiting(SINCE, REMIND_AT);
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(handed.id, neverHanded.id));
        when(repository.findById(handed.id)).thenReturn(Optional.of(handed));
        when(repository.findById(neverHanded.id)).thenReturn(Optional.of(neverHanded));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presenter.updatedEvent(any())).thenReturn(mock(InquiryUpdatedEvent.class));

        assertThat(service.run(closesAt)).isEqualTo(new TimeRuleService.Result(0, 2));

        assertThat(handed.ending).isEqualTo(Ending.ANSWERED);
        assertThat(neverHanded.ending).isEqualTo(Ending.ANSWERED);
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(InquiryUpdatedEvent.class));
        verify(presenter).updatedEvent(handed);
        verify(presenter, never()).updatedEvent(neverHanded);
    }

    @Test
    void 聞き返しへの返事を待つ件は取り下げで終え_起こしたのは時間() {
        runCallbacksDirectly();
        InquiryEntity asked = waiting(SINCE, REMIND_AT);
        asked.awaiting = Awaiting.ASKER_REPLY;
        asked.handedOverAt = SINCE;
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(asked.id));
        when(repository.findById(asked.id)).thenReturn(Optional.of(asked));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presenter.updatedEvent(any())).thenReturn(mock(InquiryUpdatedEvent.class));

        assertThat(service.run(Instant.parse("2026-01-19T03:00:00Z"))).isEqualTo(new TimeRuleService.Result(0, 1));

        assertThat(asked.ending).isEqualTo(Ending.WITHDRAWN);
        assertThat(asked.closedBy).isEqualTo(dev.inquiryassist.core.lifecycle.Actor.TIME);
        assertThat(asked.awaiting).isNull();
    }

    @Test
    void 引く対象は結果の確かめと聞き返しへの返事() {
        runCallbacksDirectly();
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of());

        service.run(REMIND_AT);

        verify(repository).findIdsByAwaitingIn(List.of(Awaiting.RESULT_CONFIRMATION, Awaiting.ASKER_REPLY));
    }
}
