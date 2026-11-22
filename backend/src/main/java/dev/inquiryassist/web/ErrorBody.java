package dev.inquiryassist.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * エラー応答の本文。SSE の {@code failed} イベントの data も同じ形。
 *
 * @param code エラーの種類（VALIDATION / NOT_FOUND / INVALID_STATE / AI_REFUSED / AI_UNAVAILABLE）
 * @param message 画面に出せる説明
 * @param inquiryId 問いの組み立ての失敗で保存した問い合わせの id。それ以外のエラーでは出力しない
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, UUID inquiryId) {

    /**
     * 問い合わせの id を持たないエラー本文を作る。
     *
     * @param code エラーの種類
     * @param message 画面に出せる説明
     */
    public ErrorBody(String code, String message) {
        this(code, message, null);
    }
}
