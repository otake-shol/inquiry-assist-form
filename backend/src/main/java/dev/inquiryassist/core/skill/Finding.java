package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.Citation;
import java.util.List;

/**
 * 調査で分かった事実1件。事実と根拠を対にして持つ。
 *
 * <p>事実と出典を別々の一覧で持つと、出典が照合で落ちたときにどの事実が根拠を失ったのかが分からない。
 * 対にしておけば、根拠を失った事実だけを機械的に外せる。根拠を挙げられない内容は事実にしない。
 *
 * @param statement 事実の一文
 * @param evidence その事実の根拠となる出典。1件以上
 */
public record Finding(String statement, List<Citation> evidence) {

    public Finding {
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement は必須");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("事実には根拠が1件以上必要");
        }
    }
}
