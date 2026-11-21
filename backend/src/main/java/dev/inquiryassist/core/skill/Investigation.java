package dev.inquiryassist.core.skill;

import java.util.List;

/**
 * 調査の出力。出典は未照合。
 *
 * @param findings 分かった事実（根拠つき）
 * @param notFound 調べたが見つからなかったこと
 * @param nextChecks 次に確認すべき場所、または本人に聞くべきこと
 */
public record Investigation(List<Finding> findings, List<String> notFound, List<String> nextChecks) {

    public Investigation {
        findings = findings == null ? List.of() : List.copyOf(findings);
        notFound = notFound == null ? List.of() : List.copyOf(notFound);
        nextChecks = nextChecks == null ? List.of() : List.copyOf(nextChecks);
    }
}
