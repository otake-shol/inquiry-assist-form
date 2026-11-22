package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/** 先例の一覧（契約書「先例の一覧」）。担当者と仕様の持ち主の両方が使う。先例は、直すことも、消すこともできない。 */
@Service
public class PrecedentService {

    /** 一覧で返す先例の数の上限。 */
    static final int LIST_LIMIT = 100;

    private final PrecedentRepository precedents;
    private final InquiryPresenter presenter;

    /**
     * @param precedents 先例の保存先
     * @param presenter 先例の表現を作る
     */
    PrecedentService(PrecedentRepository precedents, InquiryPresenter presenter) {
        this.precedents = precedents;
        this.presenter = presenter;
    }

    /**
     * 先例の一覧を返す。新しい順、最大100件。
     *
     * @param systemId システムで絞る。省くと絞らない
     * @param functionId 機能で絞る。省くと絞らない
     * @param type 種類で絞る。省くと絞らない
     * @param lang 見る人の言語
     * @return 先例
     */
    public List<PrecedentView> list(String systemId, String functionId, InquiryType type, String lang) {
        Specification<PrecedentEntity> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (systemId != null && !systemId.isBlank()) {
                all.add(cb.equal(root.get("systemId"), systemId));
            }
            if (functionId != null && !functionId.isBlank()) {
                all.add(cb.equal(root.get("functionId"), functionId));
            }
            if (type != null) {
                all.add(cb.equal(root.get("type"), type));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        return precedents
                .findAll(spec, PageRequest.of(0, LIST_LIMIT, Sort.by(Sort.Direction.DESC, "decidedAt")))
                .stream()
                .map(p -> presenter.precedentOf(p, lang))
                .toList();
    }
}
