package dev.inquiryassist.eval;

import java.util.List;

/** 評価の設定や例のファイルに誤りがあることを表す。実行を始める前に、すべての誤りをまとめて伝える。 */
final class EvalConfigException extends RuntimeException {

    private final transient List<String> errors;

    EvalConfigException(List<String> errors) {
        super(String.join("\n", errors));
        this.errors = List.copyOf(errors);
    }

    List<String> errors() {
        return errors;
    }
}
