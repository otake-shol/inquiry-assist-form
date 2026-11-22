package dev.inquiryassist.web;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.lifecycle.DeclineReason;
import dev.inquiryassist.core.lifecycle.Phase;
import dev.inquiryassist.core.lifecycle.ReplyKind;
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

    /** 担当者の返信。{@code kind} は {@code ASK} か {@code RESULT}。長さの検証は、前後の空白を除いてから行うためサービスで行う。 */
    public record ReplyRequest(@NotNull ReplyKind kind, @NotNull String body) {}

    /** 見送り。理由の長さの検証は、前後の空白を除いてから行うためサービスで行う。 */
    public record DeclineRequest(@NotNull DeclineReason reasonKind, @NotNull String reason) {}

    /** 区間の変更。範囲外の区間（{@code INTAKE}、{@code CLOSED}）はサービスが検証する。 */
    public record PhaseRequest(@NotNull Phase phase) {}

    /** 置き直し。どの項目も {@code null}（決まっていない）にできる。 */
    public record PlacementRequest(InquiryType type, String systemId, String functionId) {}

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
     * 担当者に渡したことのある問い合わせの一覧（新しい順。業務が止まっている件は先頭）。
     *
     * @param unreadOnly true なら未読だけ
     * @return 一覧（業務が止まっている件が先頭）と、未読数
     */
    @GetMapping("/inquiries")
    public OperatorList list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return operators.list(unreadOnly);
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
     * 担当者が本人に聞き返す、または結果を伝える。
     *
     * @param id 問い合わせの識別子
     * @param request 発言の種類と本文
     * @return 返信後の全内容
     */
    @PostMapping("/inquiries/{id}/replies")
    public OperatorDetail reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest request) {
        return operators.reply(id, request.kind(), request.body());
    }

    /**
     * 担当者が見送る。
     *
     * @param id 問い合わせの識別子
     * @param request 理由の種類と文章
     * @return 見送り後の全内容
     */
    @PostMapping("/inquiries/{id}/decline")
    public OperatorDetail decline(@PathVariable UUID id, @Valid @RequestBody DeclineRequest request) {
        return operators.decline(id, request.reasonKind(), request.reason());
    }

    /**
     * 担当者が区間を変える。
     *
     * @param id 問い合わせの識別子
     * @param request 変更後の区間
     * @return 変更後の全内容
     */
    @PutMapping("/inquiries/{id}/phase")
    public OperatorDetail changePhase(@PathVariable UUID id, @Valid @RequestBody PhaseRequest request) {
        return operators.changePhase(id, request.phase());
    }

    /**
     * 担当者が置き直す。
     *
     * @param id 問い合わせの識別子
     * @param request 種類、システム、機能（どれも null にできる）
     * @return 変更後の全内容
     */
    @PutMapping("/inquiries/{id}/placement")
    public OperatorDetail replacePlacement(@PathVariable UUID id, @Valid @RequestBody PlacementRequest request) {
        return operators.replacePlacement(id, request.type(), request.systemId(), request.functionId());
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
