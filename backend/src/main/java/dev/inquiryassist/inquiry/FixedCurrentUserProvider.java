package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AccessScope;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 固定ユーザー {@code local-user} を返す暫定実装。{@code job-board}、{@code faq}、{@code glossary} を閲覧できる。 */
@Component
public class FixedCurrentUserProvider implements CurrentUserProvider {

    /** 固定ユーザーの識別子。 */
    public static final String USER_ID = "local-user";

    private static final AccessScope SCOPE = new AccessScope(USER_ID, Set.of("job-board", "faq", "glossary"));

    /**
     * @return 固定ユーザーの閲覧範囲
     */
    @Override
    public AccessScope currentScope() {
        return SCOPE;
    }
}
