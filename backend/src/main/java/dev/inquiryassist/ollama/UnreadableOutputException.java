package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import java.util.List;
import java.util.stream.IntStream;

/**
 * モデルの出力を、呼び直しても指定の形として読めなかったことを表す。
 *
 * <p>なぜ読めなかったのかを後から調べられるよう、試行ごとの理由と生の出力を持つ。
 *
 * <p>例外のメッセージに入れるのは、理由の種類（{@link #reasons()}）だけにする。メッセージは画面への応答や
 * ログに出ることがある。生の出力（{@link #rawOutputs()}）と、理由の詳しい内容（{@link #details()}）は、
 * モデルの出力の一部を含み、そこには問い合わせの文章が混ざることがあるため、メッセージには入れない。
 * 評価など、調べる目的のコードだけが読む。
 */
public class UnreadableOutputException extends AiUnavailableException {

    private final transient List<String> reasons;
    private final transient List<String> details;
    private final transient List<String> rawOutputs;

    /**
     * @param reasons 試行ごとの、読めなかった理由の種類（試した順）。モデルの出力を含めない
     * @param details 試行ごとの、理由の詳しい内容（試した順）。モデルの出力の一部を含むことがある
     * @param rawOutputs 試行ごとの、モデルの生の出力（試した順）
     * @param usage 呼び直しを含む利用量の合計
     */
    public UnreadableOutputException(
            List<String> reasons, List<String> details, List<String> rawOutputs, Usage usage) {
        super("モデルの出力を指定の形として読めませんでした（" + String.join(" / ", numbered(reasons)) + "）", null, usage);
        this.reasons = List.copyOf(reasons);
        this.details = List.copyOf(details);
        this.rawOutputs = List.copyOf(rawOutputs);
    }

    /**
     * 試行ごとの、読めなかった理由の種類を返す。モデルの出力は含まない。
     *
     * @return 理由の種類（試した順）
     */
    public List<String> reasons() {
        return reasons;
    }

    /**
     * 試行ごとの、理由の詳しい内容を返す。モデルの出力の一部を含むことがあるため、画面やログにそのまま出さない。
     *
     * @return 理由の詳しい内容（試した順）
     */
    public List<String> details() {
        return details;
    }

    /**
     * 試行ごとの、モデルの生の出力を返す。問い合わせの文章を含むことがあるため、画面やログにそのまま出さない。
     *
     * @return 生の出力（試した順）
     */
    public List<String> rawOutputs() {
        return rawOutputs;
    }

    private static List<String> numbered(List<String> reasons) {
        return IntStream.range(0, reasons.size())
                .mapToObj(i -> (i + 1) + "回目: " + reasons.get(i))
                .toList();
    }
}
