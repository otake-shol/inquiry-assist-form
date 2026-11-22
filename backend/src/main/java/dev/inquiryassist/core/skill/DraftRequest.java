package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.lifecycle.ReplyKind;
import java.util.List;

/**
 * 下書きの依頼。
 *
 * @param kind 何を書くか（{@code ASK} 聞き返し、{@code RESULT} 結果）。必須
 * @param inquiry 言い直した問い。言い直しがなければ {@code null}
 * @param confirmedFindings 確かめられた事実。なければ空
 * @param judgment 判断の案の文章。なければ {@code null}
 * @param messages それまでのやり取り（古い順）
 * @param note 担当者が伝えたいこと。なければ {@code null}
 * @param language 本人の言語。下書きはこの言語で書く
 */
public record DraftRequest(
        ReplyKind kind,
        StructuredInquiry inquiry,
        List<Finding> confirmedFindings,
        String judgment,
        List<Message> messages,
        String note,
        String language) {

    /** やり取りの発言者。 */
    public enum Author {
        /** 本人。 */
        ASKER,
        /** 担当者。 */
        OPERATOR
    }

    /**
     * やり取りの1件。
     *
     * @param author 発言者
     * @param body 本文
     */
    public record Message(Author author, String body) {}

    public DraftRequest {
        if (kind == null) {
            throw new IllegalArgumentException("kind は必須");
        }
        confirmedFindings = confirmedFindings == null ? List.of() : List.copyOf(confirmedFindings);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
