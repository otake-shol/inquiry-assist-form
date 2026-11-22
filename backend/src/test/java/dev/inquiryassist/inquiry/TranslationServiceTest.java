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
import java.util.Map;
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
    void 返ってきた文章の数が合わなければAI_UNAVAILABLEとして扱い_訳は保存しない() {
        InquiryEntity e = inquiry("The posting disappeared.");
        // 1件渡したのに、2件返す偽のスキル
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("訳1", "訳2")), Usage.ofMock()));

        assertThatThrownBy(() -> service.translate(e.id, "ja")).isInstanceOf(AiUnavailableException.class);

        // 失敗した呼び出しの利用量だけを保存する。訳は保存しない
        InquiryEntity saved = new InquiryEntity("u", Urgency.NORMAL, Instant.now());
        org.mockito.ArgumentCaptor<Function<InquiryEntity, InquiryCommitter.Update>> captor =
                org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(committer).update(any(), captor.capture());
        captor.getValue().apply(saved);
        assertThat(saved.translations).isEmpty();
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
    void すべて保存済みならスキルを呼ばず_利用量も保存しない() {
        InquiryEntity e = inquiry("The posting disappeared.");
        e.translations.put("ja", Map.of(TranslationService.hashOf("The posting disappeared."), "求人が消えました。"));

        TranslationResult result = service.translate(e.id, "ja");

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("utterance");
            assertThat(item.original()).isEqualTo("The posting disappeared.");
            assertThat(item.translated()).isEqualTo("求人が消えました。");
        });
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
    void 同じ文章は1度だけ訳す_足りない分だけをスキルに渡す() {
        InquiryEntity e = inquiry("同じ");
        e.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, "利用者", "同じ", Instant.now());
        e.addMessage(MessageAuthor.OPERATOR, MessageKind.ASK, "担当者", "担当者の発言", Instant.now());
        e.addMessage(MessageAuthor.ASKER, MessageKind.NOTE, "利用者", "新しい発言", Instant.now());
        e.translations.put("ja", Map.of(TranslationService.hashOf("同じ"), "same"));
        when(translator.translate(any(), any(), any()))
                .thenReturn(new AiResult<>(new Translation(List.of("new")), Usage.ofMock()));

        TranslationResult result = service.translate(e.id, "ja");

        verify(translator).translate(List.of("新しい発言"), "en", "ja");
        assertThat(result.items()).extracting(TranslationResult.Item::translated).containsExactly("same", "same", "new");
        // 担当者の発言は訳さない
        assertThat(result.items()).extracting(TranslationResult.Item::original).doesNotContain("担当者の発言");
    }
}
