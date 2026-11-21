package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.CitationVerifier;
import java.util.List;

/**
 * 照合後の調査結果。
 *
 * @param confirmed 根拠が1件以上照合を通った事実。{@code evidence} は通った出典だけ
 * @param unconfirmed 根拠がすべて落ちた事実
 * @param notFound 調べたが見つからなかったこと
 * @param nextChecks 次に確認すべき場所、または本人に聞くべきこと
 * @param citationResults 出典ごとの照合結果（重複は除く）
 */
public record VerifiedInvestigation(
        List<Finding> confirmed,
        List<Finding> unconfirmed,
        List<String> notFound,
        List<String> nextChecks,
        List<CitationVerifier.Result> citationResults) {

    public VerifiedInvestigation {
        confirmed = confirmed == null ? List.of() : List.copyOf(confirmed);
        unconfirmed = unconfirmed == null ? List.of() : List.copyOf(unconfirmed);
        notFound = notFound == null ? List.of() : List.copyOf(notFound);
        nextChecks = nextChecks == null ? List.of() : List.copyOf(nextChecks);
        citationResults = citationResults == null ? List.of() : List.copyOf(citationResults);
        for (Finding finding : confirmed) {
            for (Citation citation : finding.evidence()) {
                boolean passed = citationResults.stream()
                        .anyMatch(r -> r.verified() && r.citation().equals(citation));
                if (!passed) {
                    throw new IllegalArgumentException("確かめられた事実の根拠に、照合を通っていない出典がある");
                }
            }
        }
    }
}
