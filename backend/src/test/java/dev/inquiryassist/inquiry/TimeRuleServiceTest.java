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
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 判定を回す処理の、競合した件を次の回に回すことと、1件の失敗で他の件を止めないことを、DB なしで確かめる。 */
class TimeRuleServiceTest {

    /** 月曜 12:00（東京）。5営業日後は翌週の月曜。 */
    private static final Instant SINCE = Instant.parse("2026-01-05T03:00:00Z");
    private static final Instant REMIND_AT = Instant.parse("2026-01-12T03:00:00Z");

    private final InquiryRepository repository = mock(InquiryRepository.class);
    private final InquiryCommitter committer = mock(InquiryCommitter.class);
    private final java.util.Map<java.util.UUID, InquiryEntity> store = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, InquiryCommitter.Update> updates = new java.util.HashMap<>();
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
                    InquiryProperties.Rules.defaults(),
                    java.util.List.of()),
            committer);

    private static InquiryEntity waiting(Instant since, Instant remindedAt) {
        InquiryEntity e = new InquiryEntity("u", Urgency.NORMAL, since);
        e.phase = Phase.KNOW;
        e.awaiting = Awaiting.RESULT_CONFIRMATION;
        e.awaitingSince = since;
        e.remindedAt = remindedAt;
        return e;
    }

    /** 保存の共通の経路（{@link InquiryCommitter#update}）を、記録の読み込みと、変更の適用だけを行う偽にする。 */
    @SuppressWarnings("unchecked")
    private void commitDirectly() {
        when(committer.update(any(), any())).thenAnswer(inv -> {
            java.util.UUID id = inv.getArgument(0);
            InquiryEntity entity = store.get(id);
            if (entity == null) {
                return null;
            }
            InquiryCommitter.Update update =
                    ((java.util.function.Function<InquiryEntity, InquiryCommitter.Update>) inv.getArgument(1)).apply(entity);
            if (update == null) {
                return null;
            }
            updates.put(id, update);
            return entity;
        });
    }

    private InquiryEntity register(InquiryEntity e) {
        store.put(e.id, e);
        return e;
    }

    @Test
    void 競合した件は次の回に回し_ほかの件は続けて処理する() {
        commitDirectly();
        InquiryEntity conflicted = register(waiting(SINCE, null));
        InquiryEntity fine = register(waiting(SINCE, null));
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(conflicted.id, fine.id));
        when(committer.update(org.mockito.ArgumentMatchers.eq(conflicted.id), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(InquiryEntity.class, conflicted.id));

        TimeRuleService.Result result = service.run(REMIND_AT);

        assertThat(result).isEqualTo(new TimeRuleService.Result(1, 0));
        assertThat(fine.remindedAt).isEqualTo(REMIND_AT);
        assertThat(fine.askerUnreadReply).isTrue();
        assertThat(conflicted.remindedAt).isNull();
    }

    @Test
    void 一件の想定外の失敗でも残りの件の判定を止めない() {
        commitDirectly();
        InquiryEntity broken = register(waiting(SINCE, null));
        InquiryEntity fine = register(waiting(SINCE, null));
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(broken.id, fine.id));
        when(committer.update(org.mockito.ArgumentMatchers.eq(broken.id), any())).thenThrow(new IllegalStateException("壊れた行"));

        assertThat(service.run(REMIND_AT)).isEqualTo(new TimeRuleService.Result(1, 0));
    }

    @Test
    void 待っていることが変わっていた件は何もしない() {
        commitDirectly();
        InquiryEntity changed = register(waiting(SINCE, null));
        changed.awaiting = Awaiting.HANDLING;
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(changed.id));

        assertThat(service.run(REMIND_AT.plusSeconds(86400L * 100))).isEqualTo(new TimeRuleService.Result(0, 0));
        assertThat(updates).isEmpty();
        assertThat(changed.remindedAt).isNull();
    }

    @Test
    void 終える件は担当者のストリームへ更新を流す対象にし_知らせるだけの件は流さない() {
        commitDirectly();
        Instant closesAt = Instant.parse("2026-01-19T03:00:00Z");
        InquiryEntity toClose = register(waiting(SINCE, REMIND_AT));
        InquiryEntity toRemind = register(waiting(SINCE, null));
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(toClose.id, toRemind.id));

        assertThat(service.run(closesAt)).isEqualTo(new TimeRuleService.Result(1, 1));

        assertThat(toClose.ending).isEqualTo(Ending.ANSWERED);
        assertThat(updates.get(toClose.id).publish()).isEqualTo(InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
        assertThat(toRemind.remindedAt).isEqualTo(closesAt);
        assertThat(updates.get(toRemind.id).publish()).isEqualTo(InquiryCommitter.Publish.NONE);
    }

    @Test
    void 聞き返しへの返事を待つ件は取り下げで終え_起こしたのは時間() {
        commitDirectly();
        InquiryEntity asked = register(waiting(SINCE, REMIND_AT));
        asked.awaiting = Awaiting.ASKER_REPLY;
        asked.handedOverAt = SINCE;
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of(asked.id));

        assertThat(service.run(Instant.parse("2026-01-19T03:00:00Z"))).isEqualTo(new TimeRuleService.Result(0, 1));

        assertThat(asked.ending).isEqualTo(Ending.WITHDRAWN);
        assertThat(asked.closedBy).isEqualTo(dev.inquiryassist.core.lifecycle.Actor.TIME);
        assertThat(asked.awaiting).isNull();
    }

    @Test
    void 引く対象は結果の確かめと聞き返しへの返事() {
        commitDirectly();
        when(repository.findIdsByAwaitingIn(any())).thenReturn(List.of());

        service.run(REMIND_AT);

        verify(repository).findIdsByAwaitingIn(List.of(Awaiting.RESULT_CONFIRMATION, Awaiting.ASKER_REPLY));
        verify(committer, never()).update(any(), any());
    }
}
