package dev.inquiryassist.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.CitationVerifier;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.ZeroAnswer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MockInquiryAssistantTest {

    private static final Duration DELAY = Duration.ofMillis(40);
    private final MockInquiryAssistant assistant = new MockInquiryAssistant(DELAY);
    private final AccessScope scope = new AccessScope("u", Set.of("job-board", "faq", "glossary"));
    private final CitationVerifier verifier = new CitationVerifier(new MockKnowledgeSearch());

    private StructuredInquiry inquiry(InquiryCategory category, String goal) {
        return new StructuredInquiry("言い直し", category, null, goal, null, null, null);
    }

    @ParameterizedTest
    @CsvSource({
        "サイトがつながらない, INCIDENT",
        "システムが落ちている, INCIDENT",
        "本番が落ちています, INCIDENT",
        "掲載の延長をお願いします, WORK_REQUEST",
        "障害だが対応してください, INCIDENT", // 障害が依頼より優先
        "依頼したいがバグかもしれない, WORK_REQUEST", // 依頼がバグより優先
        "一覧の表示がおかしい, SUSPECTED_BUG",
        "期限の仕様を教えて, SPEC_QUESTION"
    })
    void キーワードの優先順位で種別を判定する(String utterance, InquiryCategory expected) {
        assertThat(assistant.triage(utterance).value().category()).isEqualTo(expected);
    }

    @Test
    void 正常系は追加質問が2問で選択肢つき() {
        var questions = assistant.triage("期限の仕様を教えて").value().questions();

        assertThat(questions).hasSize(2);
        assertThat(questions).allSatisfy(q -> assertThat(q.options()).isNotEmpty());
    }

    @Test
    void 質問なしタグは追加質問を出さない() {
        assertThat(assistant.triage("[質問なし] 期限の仕様を教えて").value().questions()).isEmpty();
    }

    @Test
    void 作業依頼と障害ではAIの追加質問を出さない() {
        assertThat(assistant.triage("延長をお願いします").value().questions()).isEmpty();
        assertThat(assistant.triage("障害です").value().questions()).isEmpty();
    }

    @Test
    void 判定エラータグは種別の判定でAI利用不可になる() {
        assertThatThrownBy(() -> assistant.triage("[判定エラー] 期限の仕様"))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 正常系の回答は進捗3件以上で出典2件がすべて照合できる() {
        List<String> progress = new ArrayList<>();

        ZeroAnswer answer = assistant
                .answer(inquiry(InquiryCategory.SPEC_QUESTION, "期限の仕様"), scope, progress::add)
                .value();

        assertThat(progress).hasSizeBetween(3, 4);
        assertThat(answer.grounded()).isTrue();
        assertThat(answer.citations()).hasSize(2);
        assertThat(verifier.verify(answer.citations(), scope)).allSatisfy(r -> assertThat(r.verified()).isTrue());
        assertThat(answer.memo().findings()).isNotEmpty();
        assertThat(answer.memo().notFound()).isNotEmpty();
        assertThat(answer.memo().nextChecks()).isNotEmpty();
    }

    @Test
    void 不具合の疑いの説明は現在の動作として書き仕様の正否を断定しない() {
        ZeroAnswer answer = assistant
                .answer(inquiry(InquiryCategory.SUSPECTED_BUG, "表示がおかしい"), scope, m -> {})
                .value();

        assertThat(answer.askerExplanation()).contains("現在の実装では").contains("確認します");
        assertThat(answer.askerExplanation()).doesNotContain("バグです").doesNotContain("誤りです");
    }

    @Test
    void 根拠なしタグは根拠なしの回答と技術メモを返す() {
        ZeroAnswer answer = assistant
                .answer(inquiry(InquiryCategory.SPEC_QUESTION, "[根拠なし] 期限の仕様"), scope, m -> {})
                .value();

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.memo().notFound()).isNotEmpty();
    }

    @Test
    void 幽霊出典タグは実在しない出典だけを返し照合で落ちる() {
        ZeroAnswer answer = assistant
                .answer(inquiry(InquiryCategory.SPEC_QUESTION, "[幽霊出典] 期限の仕様"), scope, m -> {})
                .value();

        assertThat(answer.citations()).isNotEmpty();
        assertThat(verifier.verify(answer.citations(), scope)).allSatisfy(r -> assertThat(r.verified()).isFalse());
        assertThat(verifier.apply(answer, scope).grounded()).isFalse();
    }

    @Test
    void エラータグは回答でAI利用不可になる() {
        assertThatThrownBy(() -> assistant.answer(inquiry(InquiryCategory.SPEC_QUESTION, "[エラー] x"), scope, m -> {}))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void 拒否タグは回答でAI拒否になる() {
        assertThatThrownBy(() -> assistant.answer(inquiry(InquiryCategory.SPEC_QUESTION, "[拒否] x"), scope, m -> {}))
                .isInstanceOf(AiRefusalException.class);
    }

    @Test
    void 遅延タグは進捗の各段階で設定した時間だけ待つ() {
        List<Long> times = new ArrayList<>();
        long start = System.nanoTime();

        assistant.answer(inquiry(InquiryCategory.SPEC_QUESTION, "[遅延] x"), scope, m -> times.add(System.nanoTime()));

        long previous = start;
        for (long t : times) {
            assertThat(Duration.ofNanos(t - previous)).isGreaterThanOrEqualTo(DELAY.minusMillis(5));
            previous = t;
        }
        assertThat(times).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void 遅延タグがなければ待たない() {
        long start = System.nanoTime();

        assistant.answer(inquiry(InquiryCategory.SPEC_QUESTION, "x"), scope, m -> {});

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(DELAY);
    }

    @Test
    void 回答を出さない種別を渡すと拒否する() {
        assertThatThrownBy(() -> assistant.answer(inquiry(InquiryCategory.WORK_REQUEST, "x"), scope, m -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
