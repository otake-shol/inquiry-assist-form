package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Urgency;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Translation;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 訳の呼び出しの決まり（数の確かめ、保存済みの訳の再利用、保存の競合）を、DB なしで確かめる。 */
class TranslationServiceTest {

    private final InquiryRepository repository = mock(InquiryRepository.class);
    private final InquiryCommitter committer = mock(InquiryCommitter.class);
    private final TranslatorSkill translator = mock(TranslatorSkill.class);
    private final TranslationService service =
            new TranslationService(repository, translator, committer, SkillImplementations.from(new MockEnvironment()));

    private InquiryEntity inquiry(String text) {
        InquiryEntity e = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        e.language = "en";
        e.handedOverAt = Instant.now();
        e.texts.add(text);
        when(repository.findById(e.id)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 返ってきた文章の数が合わなければAI_UNAVAILABLEとして扱い_失敗した呼び出しの利用量だけを保存する() {
        InquiryEntity e = inquiry("The posting disappeared.");
        // 1件渡したのに、2件返す偽のスキル
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("訳1", "訳2")), Usage.ofMock()));

        assertThatThrownBy(() -> service.translate(e.id, "ja")).isInstanceOf(AiUnavailableException.class);

        // 失敗した呼び出しの利用量を保存する
        InquiryEntity saved = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        org.mockito.ArgumentCaptor<Function<InquiryEntity, InquiryCommitter.Update>> captor =
                org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(committer).update(any(), captor.capture());
        captor.getValue().apply(saved);
        assertThat(saved.usages).singleElement().satisfies(u -> {
            assertThat(u.step()).isEqualTo("translator");
            assertThat(u.failed()).isTrue();
        });
    }

    @Test
    void 数が少ない場合も失敗として扱う() {
        InquiryEntity e = inquiry("The posting disappeared.");
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of()), Usage.ofMock()));

        assertThatThrownBy(() -> service.translate(e.id, "ja")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 訳は保存せず_頼まれるたびにその時点の文章を訳す() {
        InquiryEntity e = inquiry("The posting disappeared.");
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("求人が消えました。")), Usage.ofMock()));

        service.translate(e.id, "ja");
        service.translate(e.id, "ja");

        // 2度目も、スキルを呼ぶ。利用量は、そのたびに記録する
        verify(translator, org.mockito.Mockito.times(2)).translate(List.of("The posting disappeared."), "en", "ja");
        verify(committer, org.mockito.Mockito.times(2)).update(any(), any());
    }

    @Test
    void 訳す文章がなければスキルを呼ばない() {
        InquiryEntity e = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        e.language = "en";
        e.handedOverAt = Instant.now();
        when(repository.findById(e.id)).thenReturn(Optional.of(e));

        assertThat(service.translate(e.id, "ja").items()).isEmpty();
        verify(translator, never()).translate(any(), any(), any());
        verify(committer, never()).update(any(), any());
    }

    @Test
    void 保存が競合しても訳は返す() {
        InquiryEntity e = inquiry("The posting disappeared.");
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("求人が消えました。")), Usage.ofMock()));
        when(committer.update(any(), any())).thenThrow(new ObjectOptimisticLockingFailureException(InquiryEntity.class, e.id));

        TranslationResult result = service.translate(e.id, "ja");

        assertThat(result.items()).extracting(TranslationResult.Item::translated).containsExactly("求人が消えました。");
        assertThat(result.from()).isEqualTo("en");
        assertThat(result.to()).isEqualTo("ja");
    }

    @Test
    void 同じ文章は1度だけスキルに渡し_担当者の発言は訳さない() {
        InquiryEntity e = inquiry("同じ");
        e.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, "利用者", "同じ", Instant.now());
        e.addMessage(MessageAuthor.OPERATOR, MessageKind.ASK, "担当者", "担当者の発言", Instant.now());
        e.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, "利用者", "新しい発言", Instant.now());
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("same", "new")), Usage.ofMock()));

        TranslationResult result = service.translate(e.id, "ja");

        verify(translator).translate(List.of("同じ", "新しい発言"), "en", "ja");
        assertThat(result.items()).extracting(TranslationResult.Item::translated).containsExactly("same", "same", "new");
        assertThat(result.items()).extracting(TranslationResult.Item::original).doesNotContain("担当者の発言");
    }
}
