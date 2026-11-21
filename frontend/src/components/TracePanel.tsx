import type { TraceEntry } from "../api/types.ts";
import { USER_TEXT } from "../text/inquiries.ts";
import {
  formatDuration,
  implementationLabel,
  summarizeTrace,
  type TraceStage,
} from "../trace.ts";

/**
 * 1段ぶんの表示。まだ動いていない段は薄く示す。同じスキルが複数回動いたときは回数と合計を示す。
 * @param props 集計した1段
 */
function StageRow({ stage }: { stage: TraceStage }) {
  const ran = stage.calls > 0;
  const code = stage.kind === "code";
  return (
    <li className={`trace-stage ${ran ? "ran" : "idle"}`}>
      <p className="trace-head">
        <strong>{stage.label}</strong>
        <span className="trace-kind">
          {code ? "通常のコード" : "AI のスキル"}
        </span>
        {!ran && <span className="trace-idle">まだ動いていません</span>}
        {ran && stage.calls > 1 && (
          <span className="trace-count">{stage.calls}回（合計）</span>
        )}
        {stage.failedCalls > 0 && (
          <span className="trace-failed">失敗 {stage.failedCalls}回</span>
        )}
      </p>
      {ran && !code && (
        <p className="trace-detail">
          {stage.implementations
            .map((i) => implementationLabel(i.provider, i.model))
            .join("、")}
          {" ・ "}
          {formatDuration(stage.durationMillis)}
          {" ・ "}入力 {stage.inputTokens.toLocaleString("ja-JP")} / 出力{" "}
          {stage.outputTokens.toLocaleString("ja-JP")} トークン
          {stage.failedCalls === 0 && " ・ 失敗なし"}
        </p>
      )}
      {ran && code && (
        <p className="trace-detail">調査の結果の根拠を確かめる</p>
      )}
    </li>
  );
}

/**
 * 「裏側を見る（開発者向け）」。スキルの実行記録を、流れの順（受付 → 聞き手 → 言い直し →
 * 調査 → 照合（コード） → 説明）に並べる。既定は閉じておく。
 * 開発者向けなので、この中に限りスキルやモデルの名前を使う。
 * @param props 実行記録（無ければすべての段が「まだ動いていません」になる）
 */
export function TracePanel({
  entries,
}: {
  entries: TraceEntry[] | null | undefined;
}) {
  const stages = summarizeTrace(entries);
  return (
    <details className="trace-panel">
      <summary>{USER_TEXT.traceSummary}</summary>
      <ol className="trace-list">
        {stages.map((stage) => (
          <StageRow key={stage.key} stage={stage} />
        ))}
      </ol>
    </details>
  );
}
