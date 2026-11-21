package dev.inquiryassist.web;

import dev.inquiryassist.inquiry.OperatorDetail;
import dev.inquiryassist.inquiry.OperatorList;
import dev.inquiryassist.inquiry.OperatorService;
import dev.inquiryassist.notification.OperatorStreamHub;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** オペレータ向けの API。認証は後のフェーズ（SSO）で加え、それまではローカルでの利用に限る。 */
@RestController
@RequestMapping("/api/operator")
public class OperatorController {

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
     * @return 一覧と未読数
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
     * 問い合わせが担当者に送られるたびに {@code filed} を流す SSE。
     *
     * @return SSE のストリーム
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
