package dev.inquiryassist.web;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.inquiry.ComposeFailedException;
import dev.inquiryassist.inquiry.InquiryNotFoundException;
import dev.inquiryassist.inquiry.InvalidStateException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 例外を契約書のエラー応答 {@code {code, message}} に変換する。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 入力検証の失敗を 400 にする。
     *
     * @param e 例外
     * @return エラー応答
     */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorBody> validation(Exception e) {
        String message = e instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .reduce((a, b) -> a + " / " + b)
                        .orElse("入力が不正です")
                : "入力が不正です";
        return json(HttpStatus.BAD_REQUEST, "VALIDATION", message);
    }

    /**
     * @param e 例外
     * @return 404 の応答
     */
    @ExceptionHandler(InquiryNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(InquiryNotFoundException e) {
        return json(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /**
     * @param e 例外
     * @return 409 の応答
     */
    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ErrorBody> invalidState(InvalidStateException e) {
        return json(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    /**
     * @param e 例外
     * @return 422 の応答
     */
    @ExceptionHandler(AiRefusalException.class)
    public ResponseEntity<ErrorBody> refused(AiRefusalException e) {
        return json(HttpStatus.UNPROCESSABLE_ENTITY, "AI_REFUSED", e.getMessage());
    }

    /**
     * @param e 例外
     * @return 503 の応答
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ErrorBody> unavailable(AiUnavailableException e) {
        log.warn("AI を呼び出せませんでした", e);
        return json(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", e.getMessage());
    }

    /**
     * 問いの組み立て（言い直し）の失敗を、原因に応じて 422／503 にし、保存した問い合わせの id を本文に加える。
     *
     * @param e 例外
     * @return エラー応答
     */
    @ExceptionHandler(ComposeFailedException.class)
    public ResponseEntity<ErrorBody> composeFailed(ComposeFailedException e) {
        boolean refused = e.getCause() instanceof AiRefusalException;
        if (!refused) {
            log.warn("問いの組み立てで AI を呼び出せませんでした id={}", e.inquiryId(), e);
        }
        return ResponseEntity.status(refused ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(refused ? "AI_REFUSED" : "AI_UNAVAILABLE", e.getMessage(), e.inquiryId()));
    }

    /** Accept が text/event-stream の要求でも JSON で返せるよう、Content-Type を明示する。 */
    private static ResponseEntity<ErrorBody> json(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(code, message));
    }
}
