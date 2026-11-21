package dev.inquiryassist.inquiry;

import java.util.UUID;

/** 問い合わせが存在しない（または投稿者本人のものでない）ことを表す。HTTP では 404 {@code NOT_FOUND}。 */
public class InquiryNotFoundException extends RuntimeException {

    /**
     * @param id 探した問い合わせの識別子
     */
    public InquiryNotFoundException(UUID id) {
        super("問い合わせが見つかりません: " + id);
    }
}
