package dev.inquiryassist.web;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.inquiry.DecisionKind;
import dev.inquiryassist.inquiry.PrecedentService;
import dev.inquiryassist.inquiry.PrecedentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 先例の一覧と、直すこと（{@code /api/precedents}）。担当者と仕様の持ち主の両方が使う。認証は後で加える。 */
@RestController
@RequestMapping("/api/precedents")
public class PrecedentController {

    /** 先例の一覧。 */
    public record PrecedentList(List<PrecedentView> items) {}

    /** 先例を直す本文。すべて必須。 */
    public record EditRequest(
            @NotBlank @Size(max = 4000) String situation,
            @NotNull DecisionKind decisionKind,
            @NotBlank @Size(max = 4000) String decision,
            @NotBlank @Size(max = 4000) String reason,
            @NotBlank @Size(max = 100) String decidedBy) {}

    private final PrecedentService precedents;

    /**
     * @param precedents 先例の一覧と、直すこと
     */
    public PrecedentController(PrecedentService precedents) {
        this.precedents = precedents;
    }

    /**
     * 先例の一覧（新しい順、最大100件）。
     *
     * @param systemId システムで絞る
     * @param functionId 機能で絞る
     * @param type 種類で絞る
     * @param unconfirmedOnly true なら、確かめられていない先例だけ
     * @param lang 見る人の言語
     * @return 先例
     */
    @GetMapping
    public PrecedentList list(
            @RequestParam(required = false) String systemId,
            @RequestParam(required = false) String functionId,
            @RequestParam(required = false) InquiryType type,
            @RequestParam(defaultValue = "false") boolean unconfirmedOnly,
            @ViewerLang String lang) {
        return new PrecedentList(precedents.list(systemId, functionId, type, unconfirmedOnly, lang));
    }

    /**
     * 先例を直す。直すと確かめられた先例になる。
     *
     * @param id 先例の識別子
     * @param request 直した内容
     * @param lang 見る人の言語
     * @return 直した先例
     */
    @PutMapping("/{id}")
    public PrecedentView edit(@PathVariable UUID id, @Valid @RequestBody EditRequest request, @ViewerLang String lang) {
        return precedents.edit(
                id, request.situation(), request.decisionKind(), request.decision(), request.reason(), request.decidedBy(), lang);
    }

    /**
     * 確かめた。
     *
     * @param id 先例の識別子
     * @param lang 見る人の言語
     * @return 先例
     */
    @PostMapping("/{id}/confirm")
    public PrecedentView confirm(@PathVariable UUID id, @ViewerLang String lang) {
        return precedents.confirm(id, lang);
    }
}
