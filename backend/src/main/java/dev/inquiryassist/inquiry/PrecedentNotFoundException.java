package dev.inquiryassist.inquiry;

import java.util.UUID;

/** 存在しない先例を指定したことを表す。HTTP では 404 {@code NOT_FOUND}。 */
public class PrecedentNotFoundException extends RuntimeException {

    /**
     * @param id 存在しなかった先例の識別子
     */
    public PrecedentNotFoundException(UUID id) {
        super("先例が見つかりません: " + id);
    }
}
