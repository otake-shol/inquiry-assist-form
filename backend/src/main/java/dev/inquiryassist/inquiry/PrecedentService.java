package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.InquiryType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 先例の一覧と、直すこと（契約書「先例の一覧と、直すこと」）。担当者と仕様の持ち主の両方が使う。先例は消せない。
 */
@Service
public class PrecedentService {

    /** 一覧で返す先例の数の上限。 */
    static final int LIST_LIMIT = 100;

    private final PrecedentRepository precedents;
    private final InquiryPresenter presenter;
    private final TransactionTemplate transaction;

    /**
     * @param precedents 先例の保存先
     * @param presenter 先例の表現を作る
     * @param transaction 直すとき、確かめたときのトランザクション
     */
    PrecedentService(PrecedentRepository precedents, InquiryPresenter presenter, TransactionTemplate transaction) {
        this.precedents = precedents;
        this.presenter = presenter;
        this.transaction = transaction;
    }

    /**
     * 先例の一覧を返す。新しい順、最大100件。
     *
     * @param systemId システムで絞る。省くと絞らない
     * @param functionId 機能で絞る。省くと絞らない
     * @param type 種類で絞る。省くと絞らない
     * @param unconfirmedOnly true なら、確かめられていない先例だけ
     * @param lang 見る人の言語
     * @return 先例
     */
    public List<PrecedentView> list(
            String systemId, String functionId, InquiryType type, boolean unconfirmedOnly, String lang) {
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
            if (unconfirmedOnly) {
                all.add(cb.isFalse(root.get("confirmed")));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        return precedents
                .findAll(spec, PageRequest.of(0, LIST_LIMIT, Sort.by(Sort.Direction.DESC, "decidedAt")))
                .stream()
                .map(p -> presenter.precedentOf(p, lang))
                .toList();
    }

    /**
     * 先例を直す。直すと確かめられた先例になる。置き場所と、元の問い合わせは変えない。
     *
     * @param id 先例の識別子
     * @param situation 状況。前後の空白を除いて1〜4000文字
     * @param kind 決定の種類
     * @param decision 決定の内容。前後の空白を除いて1〜4000文字
     * @param reason 理由。前後の空白を除いて1〜4000文字
     * @param decidedBy 決めた人の名前。前後の空白を除いて1〜100文字
     * @param lang 見る人の言語
     * @return 直した先例
     * @throws IllegalArgumentException 入力が不正な場合
     * @throws PrecedentNotFoundException 存在しない場合
     */
    public PrecedentView edit(
            UUID id, String situation, DecisionKind kind, String decision, String reason, String decidedBy, String lang) {
        String situationText = required(situation, 4000, "situation");
        String decisionText = required(decision, 4000, "decision");
        String reasonText = required(reason, 4000, "reason");
        String decidedByText = required(decidedBy, 100, "decidedBy");
        return update(id, lang, p -> p.edit(situationText, kind, decisionText, reasonText, decidedByText, Instant.now()));
    }

    /**
     * 確かめた。確かめられた先例にする（すでに確かめられていても、そのまま返す）。
     *
     * @param id 先例の識別子
     * @param lang 見る人の言語
     * @return 先例
     * @throws PrecedentNotFoundException 存在しない場合
     */
    public PrecedentView confirm(UUID id, String lang) {
        return update(id, lang, p -> p.confirm(Instant.now()));
    }

    private PrecedentView update(UUID id, String lang, java.util.function.Consumer<PrecedentEntity> change) {
        PrecedentEntity saved = transaction.execute(status -> {
            PrecedentEntity p = precedents.findById(id).orElseThrow(() -> new PrecedentNotFoundException(id));
            change.accept(p);
            return precedents.saveAndFlush(p);
        });
        return presenter.precedentOf(saved, lang);
    }

    private static String required(String value, int max, String name) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException(name + " は前後の空白を除いて1〜" + max + "文字");
        }
        return trimmed;
    }
}
