package dev.inquiryassist.web;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.inquiry.InquiryService;
import dev.inquiryassist.inquiry.InquiryView;
import dev.inquiryassist.inquiry.InvalidStateException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 問い合わせフォームの REST と SSE（契約書 2〜9）。 */
@RestController
public class InquiryController {

    private static final Logger log = LoggerFactory.getLogger(InquiryController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /** 新規の問い合わせ。 */
    public record CreateRequest(@NotBlank @Size(max = 4000) String utterance) {}

    /** 種別の変更。 */
    public record CategoryRequest(@NotNull InquiryCategory category) {}

    /** 追加質問への回答1件。 */
    public record AnswerItem(@NotBlank String questionId, String answer) {}

    /** 追加質問への回答。 */
    public record AnswersRequest(@NotNull List<@NotNull @Valid AnswerItem> answers) {}

    /** 整形結果の確定。 */
    public record StructuredRequest(
            @Size(max = 4000) String targetSystem,
            @NotBlank @Size(max = 4000) String goal,
            @Size(max = 4000) String whatHappened,
            @Size(max = 4000) String expectedResult,
            StructuredInquiry.Urgency urgency) {}

    /** 解決の判定。 */
    public record ResolutionRequest(@NotNull Boolean resolved) {}

    private final InquiryService service;

    /**
     * @param service 問い合わせのアプリケーション層
     */
    public InquiryController(InquiryService service) {
        this.service = service;
    }

    /**
     * 種別を判定し追加質問を作る。
     *
     * @param request 発話
     * @return {@code TRIAGED} の問い合わせ
     */
    @PostMapping("/api/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryView create(@Valid @RequestBody CreateRequest request) {
        return service.create(request.utterance());
    }

    /**
     * 種別を変更する。
     *
     * @param id 問い合わせの識別子
     * @param request 変更後の種別
     * @return 更新後の問い合わせ
     */
    @PutMapping("/api/inquiries/{id}/category")
    public InquiryView changeCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return service.changeCategory(id, request.category());
    }

    /**
     * 追加質問に回答する。
     *
     * @param id 問い合わせの識別子
     * @param request 回答
     * @return 更新後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/answers")
    public InquiryView answers(@PathVariable UUID id, @Valid @RequestBody AnswersRequest request) {
        return service.answerQuestions(
                id,
                request.answers().stream()
                        .map(a -> new InquiryService.AnswerInput(a.questionId(), a.answer()))
                        .toList());
    }

    /**
     * 整形結果を確定する。
     *
     * @param id 問い合わせの識別子
     * @param request 確定する整形結果
     * @return 更新後の問い合わせ
     */
    @PutMapping("/api/inquiries/{id}/structured")
    public InquiryView structured(@PathVariable UUID id, @Valid @RequestBody StructuredRequest request) {
        return service.confirmStructured(
                id,
                new InquiryService.StructuredInput(
                        request.targetSystem(),
                        request.goal(),
                        request.whatHappened(),
                        request.expectedResult(),
                        request.urgency()));
    }

    /**
     * 0次回答を生成し、進捗と結果を SSE で流す。生成は仮想スレッドで行う。
     *
     * <p>問い合わせが存在しなければ、ストリームを開始せず 404 を返す。それ以外の失敗は {@code failed} イベントで流す。
     *
     * @param id 問い合わせの識別子
     * @return SSE のストリーム
     */
    @GetMapping(path = "/api/inquiries/{id}/answer-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(@PathVariable UUID id) {
        service.get(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        Thread.ofVirtual().name("answer-" + id).start(() -> runAnswer(id, emitter));
        return emitter;
    }

    /**
     * 解決を判定する。
     *
     * @param id 問い合わせの識別子
     * @param request 解決したか
     * @return 更新後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/resolution")
    public InquiryView resolution(@PathVariable UUID id, @Valid @RequestBody ResolutionRequest request) {
        return service.resolve(id, request.resolved());
    }

    /**
     * 今すぐ担当者に送る。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/send-now")
    public InquiryView sendNow(@PathVariable UUID id) {
        return service.sendNow(id);
    }

    /**
     * 問い合わせを取得する。
     *
     * @param id 問い合わせの識別子
     * @return 問い合わせ
     */
    @GetMapping("/api/inquiries/{id}")
    public InquiryView get(@PathVariable UUID id) {
        return service.get(id);
    }

    private void runAnswer(UUID id, SseEmitter emitter) {
        try {
            InquiryView done = service.generateAnswer(id, message -> send(emitter, "progress", Map.of("message", message)));
            send(emitter, "done", done);
        } catch (AiRefusalException e) {
            send(emitter, "failed", new ErrorBody("AI_REFUSED", e.getMessage()));
        } catch (AiUnavailableException e) {
            log.warn("0次回答の生成で AI を呼び出せませんでした id={}", id, e);
            send(emitter, "failed", new ErrorBody("AI_UNAVAILABLE", e.getMessage()));
        } catch (InvalidStateException e) {
            send(emitter, "failed", new ErrorBody("INVALID_STATE", e.getMessage()));
        } catch (RuntimeException e) {
            // 契約外の失敗。画面を「担当者に送る」へ誘導できるよう AI_UNAVAILABLE として流す
            log.error("0次回答の生成で想定外の失敗 id={}", id, e);
            send(emitter, "failed", new ErrorBody("AI_UNAVAILABLE", "内部エラーが発生しました"));
        } finally {
            emitter.complete();
        }
    }

    /** クライアントが切断済みでも生成と保存は止めないため、送信の失敗は無視する。 */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE の送信に失敗（切断済み）: {}", event);
        }
    }
}
