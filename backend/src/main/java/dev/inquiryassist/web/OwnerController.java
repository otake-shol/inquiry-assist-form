package dev.inquiryassist.web;

import dev.inquiryassist.inquiry.DecisionKind;
import dev.inquiryassist.inquiry.OperatorDetail;
import dev.inquiryassist.inquiry.OwnerList;
import dev.inquiryassist.inquiry.OwnerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仕様の持ち主向けの API（{@code /api/owner}）。担当者向けの API と同じく、認証は後で加える。 */
@RestController
@RequestMapping("/api/owner")
public class OwnerController {

    /** 決定。{@code situation} は任意。文章の長さの検証は、前後の空白を除いてから、サービスで行う。 */
    public record DecisionRequest(
            @NotNull DecisionKind decisionKind,
            @NotBlank @Size(max = 4000) String decision,
            @NotBlank @Size(max = 4000) String reason,
            @Size(max = 4000) String situation,
            @NotBlank @Size(max = 100) String decidedBy) {}

    private final OwnerService owners;

    /**
     * @param owners 仕様の持ち主向けの操作
     */
    public OwnerController(OwnerService owners) {
        this.owners = owners;
    }

    /**
     * 決定を待っている相談の一覧（古い順）。
     *
     * @param lang 見る人の言語
     * @return 一覧
     */
    @GetMapping("/consultations")
    public OwnerList consultations(@ViewerLang String lang) {
        return owners.list(lang);
    }

    /**
     * 相談したことのある問い合わせの全内容（担当者向けと同じ形）。
     *
     * @param id 問い合わせの識別子
     * @param lang 見る人の言語
     * @return 全内容
     */
    @GetMapping("/inquiries/{id}")
    public OperatorDetail get(@PathVariable UUID id, @ViewerLang String lang) {
        return owners.get(id, lang);
    }

    /**
     * 決める。
     *
     * @param id 問い合わせの識別子
     * @param request 決定
     * @param lang 見る人の言語
     * @return 決定後の全内容
     */
    @PostMapping("/inquiries/{id}/decision")
    public OperatorDetail decide(
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request, @ViewerLang String lang) {
        return owners.decide(
                id,
                request.decisionKind(),
                request.decision(),
                request.reason(),
                request.situation(),
                request.decidedBy(),
                lang);
    }
}
