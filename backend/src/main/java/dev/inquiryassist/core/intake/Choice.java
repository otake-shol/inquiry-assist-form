package dev.inquiryassist.core.intake;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * 質問の選択肢。
 *
 * @param id 識別子
 * @param label 画面に出す名前（見る人の言語）
 * @param aliases 選択肢を文章から読み取るために使う、ほかの言語の名前も含めたすべての名前。画面には出さない
 */
public record Choice(String id, String label, @JsonIgnore List<String> aliases) {

    /**
     * 名前が1つだけの選択肢を作る。
     *
     * @param id 識別子
     * @param label 画面に出す名前
     */
    public Choice(String id, String label) {
        this(id, label, List.of(label));
    }

    public Choice {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id は必須");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label は必須");
        }
        aliases = aliases == null || aliases.isEmpty() ? List.of(label) : List.copyOf(aliases);
    }
}
