package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 調査の結果を一次情報に当たって照合し、事実を確かめられたものとそうでないものに分ける。 */
public final class InvestigationVerifier {

    private final CitationVerifier citationVerifier;

    /**
     * @param citationVerifier 出典の照合
     */
    public InvestigationVerifier(CitationVerifier citationVerifier) {
        if (citationVerifier == null) {
            throw new IllegalArgumentException("citationVerifier は必須");
        }
        this.citationVerifier = citationVerifier;
    }

    /**
     * 出典を照合し、事実ごとに根拠を絞る。根拠が1件でも通れば確かめられた事実（根拠は通ったものだけ）、
     * すべて落ちれば確かめられなかった事実。
     *
     * @param investigation 調査の出力
     * @param scope 投稿者の閲覧範囲
     * @return 照合後の調査結果
     */
    public VerifiedInvestigation verify(Investigation investigation, AccessScope scope) {
        // 同じ出典が複数の事実に現れても、照合は1回にする
        List<Citation> distinct = new ArrayList<>(investigation.findings().stream()
                .flatMap(f -> f.evidence().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        List<CitationVerifier.Result> results = citationVerifier.verify(distinct, scope);

        List<Finding> confirmed = new ArrayList<>();
        List<Finding> unconfirmed = new ArrayList<>();
        for (Finding finding : investigation.findings()) {
            List<Citation> passed = finding.evidence().stream()
                    .filter(c -> results.stream().anyMatch(r -> r.verified() && r.citation().equals(c)))
                    .toList();
            if (passed.isEmpty()) {
                unconfirmed.add(finding);
            } else {
                confirmed.add(new Finding(finding.statement(), passed));
            }
        }
        return new VerifiedInvestigation(
                confirmed, unconfirmed, investigation.notFound(), investigation.nextChecks(), results);
    }
}
