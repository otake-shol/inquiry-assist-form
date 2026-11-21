package dev.inquiryassist.web;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.InquiryCategory;
import dev.inquiryassist.inquiry.InquiryList;
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
import java.util.Set;
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

/** 問い合わせフォームの REST と SSE（契約書 2〜11）。 */
@RestController
public class InquiryController {

    private static final Logger log = LoggerFactory.getLogger(InquiryController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /** 新規の問い合わせ。 */
    public record CreateRequest(@NotBlank @Size(max = 4000) String utterance, String systemId) {}

    /** 対象システムの変更。{@code systemId} が null なら「わからない」に戻す。 */
    public record SystemRequest(String systemId) {}

    /** 種別の変更。 */
    public record CategoryRequest(@NotNull InquiryCategory category) {}

    /** 追加質問への回答1件。 */
    public record AnswerItem(@NotBlank String questionId, String answer) {}

    /** 追加質問への回答。 */
    public record AnswersRequest(@NotNull List<@NotNull @Valid AnswerItem> answers) {}

    /** 言い直しの作り直し。 */
    public record RevisionRequest(@NotBlank @Size(max = 1000) String note) {}

    /** 本人の追記。長さの検証は、前後の空白を除いてから行うためサービスで行う。 */
    public record MessageRequest(@NotNull String body) {}

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
        return service.create(request.utterance(), request.systemId());
    }

    /**
     * 対象システムを直す。
     *
     * @param id 問い合わせの識別子
     * @param request 対象システムの識別子（null で「わからない」）
     * @return 更新後の問い合わせ
     */
    @PutMapping("/api/inquiries/{id}/system")
    public InquiryView changeSystem(@PathVariable UUID id, @RequestBody SystemRequest request) {
        return service.changeSystem(id, request.systemId());
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
     * 言い直しを確定する。本文は {@code {}}（または本文なし）で AI の言い直しをそのまま確定し、
     * {@code {"summary": "…"}} で本人が書き直した文章に置き換える。それ以外の項目（旧形式の5項目など）は受け付けない。
     *
     * @param id 問い合わせの識別子
     * @param body リクエスト本文
     * @return 更新後の問い合わせ
     */
    @PutMapping("/api/inquiries/{id}/structured")
    public InquiryView structured(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        Object summary = null;
        if (body != null) {
            if (!Set.of("summary").containsAll(body.keySet())) {
                throw new IllegalArgumentException("summary 以外の項目は受け付けません");
            }
            summary = body.get("summary");
            if (summary != null && !(summary instanceof String)) {
                throw new IllegalArgumentException("summary は文字列");
            }
        }
        return service.confirmStructured(id, (String) summary);
    }

    /**
     * 本人が違う点を一言で伝え、言い直しを作り直す。
     *
     * @param id 問い合わせの識別子
     * @param request 違う点の一言
     * @return 更新後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/revision")
    public InquiryView revision(@PathVariable UUID id, @Valid @RequestBody RevisionRequest request) {
        return service.revise(id, request.note());
    }

    /**
     * 追加質問へ戻る。
     *
     * @param id 問い合わせの識別子
     * @return 更新後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/reopen")
    public InquiryView reopen(@PathVariable UUID id) {
        return service.reopen(id);
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
     * 自分が作った問い合わせの一覧（新しい順、最大50件）。
     *
     * @return 一覧と、未読の返信がある件数
     */
    @GetMapping("/api/inquiries")
    public InquiryList list() {
        return service.list();
    }

    /**
     * 担当者に送った後の問い合わせに追記する。
     *
     * @param id 問い合わせの識別子
     * @param request 本文
     * @return 追記後の問い合わせ
     */
    @PostMapping("/api/inquiries/{id}/messages")
    public InquiryView addMessage(@PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return service.addMessage(id, request.body());
    }

    /**
     * 問い合わせを取得する。担当者の返信が未読なら、読んだことにする。
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
