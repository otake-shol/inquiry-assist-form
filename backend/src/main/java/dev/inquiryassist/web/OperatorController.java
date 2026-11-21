package dev.inquiryassist.web;

import dev.inquiryassist.inquiry.HandlingStatus;
import dev.inquiryassist.inquiry.OperatorDetail;
import dev.inquiryassist.inquiry.OperatorList;
import dev.inquiryassist.inquiry.OperatorService;
import dev.inquiryassist.notification.OperatorStreamHub;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** オペレータ向けの API。認証は後のフェーズ（SSO）で加え、それまではローカルでの利用に限る。 */
@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    /** 対応状況の変更。3つの値以外は読み取れず 400 になる。 */
    public record StatusRequest(@NotNull HandlingStatus status) {}

    /** 担当者の返信。長さの検証は、前後の空白を除いてから行うためサービスで行う。 */
    public record ReplyRequest(@NotNull String body) {}

    private final OperatorService operators;
    private final OperatorStreamHub hub;

    /**
     * @param operators 一覧・詳細・既読化
     * @param hub ツール内の通知の配信
     */
    public OperatorController(OperatorService operators, OperatorStreamHub hub) {
        this.operators = operators;
        this.hub = hub;
    }

    /**
     * 担当者に送られた問い合わせの一覧（新しい順）。
     *
     * @param unreadOnly true なら未読だけ
     * @param status 指定した対応状況だけ。省略すると絞らない
     * @return 一覧と、未読数・対応状況ごとの件数
     */
    @GetMapping("/inquiries")
    public OperatorList list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) HandlingStatus status) {
        return operators.list(unreadOnly, status);
    }

    /**
     * 問い合わせの全内容。
     *
     * @param id 問い合わせの識別子
     * @return 全内容
     */
    @GetMapping("/inquiries/{id}")
    public OperatorDetail get(@PathVariable UUID id) {
        return operators.get(id);
    }

    /**
     * 既読にする。
     *
     * @param id 問い合わせの識別子
     * @return 既読後の全内容
     */
    @PostMapping("/inquiries/{id}/read")
    public OperatorDetail read(@PathVariable UUID id) {
        return operators.markRead(id);
    }

    /**
     * 対応状況を変える。
     *
     * @param id 問い合わせの識別子
     * @param request 変更後の対応状況
     * @return 変更後の全内容
     */
    @PutMapping("/inquiries/{id}/status")
    public OperatorDetail changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return operators.changeStatus(id, request.status());
    }

    /**
     * 担当者が返信する。
     *
     * @param id 問い合わせの識別子
     * @param request 本文
     * @return 返信後の全内容
     */
    @PostMapping("/inquiries/{id}/replies")
    public OperatorDetail reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest request) {
        return operators.reply(id, request.body());
    }

    /**
     * 問い合わせが担当者に送られるたびに {@code filed}、送付後に更新されるたびに {@code updated} を流す SSE。
     *
     * @return SSE のストリーム
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
