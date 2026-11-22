package dev.inquiryassist.core.intake;

/**
 * 質問の選択肢。
 *
 * @param id 識別子
 * @param label 画面に出す名前
 */
public record Choice(String id, String label) {

    public Choice {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
    }
}
