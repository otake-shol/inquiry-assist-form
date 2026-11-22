package dev.inquiryassist.web;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.inquiry.PrecedentService;
import dev.inquiryassist.inquiry.PrecedentView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 先例の一覧（{@code /api/precedents}）。担当者と仕様の持ち主の両方が使う。認証は後で加える。 */
@RestController
@RequestMapping("/api/precedents")
public class PrecedentController {

    /** 先例の一覧。 */
    public record PrecedentList(List<PrecedentView> items) {}

    private final PrecedentService precedents;

    /**
     * @param precedents 先例の一覧
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
     * @param lang 見る人の言語
     * @return 先例
     */
    @GetMapping
    public PrecedentList list(
            @RequestParam(required = false) String systemId,
            @RequestParam(required = false) String functionId,
            @RequestParam(required = false) InquiryType type,
            @ViewerLang String lang) {
        return new PrecedentList(precedents.list(systemId, functionId, type, lang));
    }
}
