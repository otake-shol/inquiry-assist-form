package dev.inquiryassist.eval;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 評価の入口（{@code npm run eval}、Gradle の {@code evalRun}）。{@code check} からは呼ばれない。
 *
 * <p>引数は {@code --名前=値}（{@code skill}、{@code split}、{@code cases}、{@code promptVersion}、{@code model}、
 * {@code think}、{@code temperature}、{@code repeat}、{@code label}、{@code compareWith}）。
 * 終了コード: 0 は完了（落ちた例があっても 0）、1 は Ollama に接続できない、2 は引数か例のファイルの誤り。
 */
public final class EvalMain {

    private EvalMain() {}

    /**
     * @param args {@code --名前=値} の引数
     * @throws Exception 結果を書き込めない場合
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("eval.root", ".")).toAbsolutePath().normalize();
        try {
            EvalConfig config = EvalConfig.parse(root, Arrays.asList(args));
            new EvalRunner(config, System.out).run();
        } catch (EvalConfigException e) {
            System.err.println("評価を始められません。次の誤りを直してください:");
            e.errors().forEach(error -> System.err.println("  - " + error));
            System.exit(2);
        } catch (EvalRunner.OllamaUnavailableException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
