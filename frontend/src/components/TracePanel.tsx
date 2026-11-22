import type { TraceEntry } from "../api/types.ts";
import { useMessages } from "../i18n/context.ts";
import type { Messages } from "../i18n/messages.ts";
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
function StageRow({
  stage,
  messages,
}: {
  stage: TraceStage;
  messages: Messages;
}) {
  const { trace } = messages;
  const number = (value: number) => value.toLocaleString(messages.meta.locale);
  const ran = stage.calls > 0;
  const code = stage.kind === "code";
  return (
    <li className={`trace-stage ${ran ? "ran" : "idle"}`}>
      <p className="trace-head">
        <strong>{stage.label}</strong>
        <span className="trace-kind">
          {code ? trace.codeKind : trace.skillKind}
        </span>
        {!ran && <span className="trace-idle">{trace.idle}</span>}
        {ran && stage.calls > 1 && (
          <span className="trace-count">{trace.calls(stage.calls)}</span>
        )}
        {stage.failedCalls > 0 && (
          <span className="trace-failed">
            {trace.failedCalls(stage.failedCalls)}
          </span>
        )}
      </p>
      {ran && !code && (
        <p className="trace-detail">
          {stage.implementations
            .map((i) => implementationLabel(i.provider, i.model, messages))
            .join(trace.implJoiner)}
          {messages.meta.separator}
          {formatDuration(stage.durationMillis, trace)}
          {messages.meta.separator}
          {trace.tokens(number(stage.inputTokens), number(stage.outputTokens))}
          {stage.failedCalls === 0 &&
            `${messages.meta.separator}${trace.noFailure}`}
        </p>
      )}
      {ran && code && (
        <p className="trace-detail">
          {stage.key === "adviceVerifier"
            ? trace.adviceCodeDetail
            : trace.codeDetail}
        </p>
      )}
    </li>
  );
}

/**
 * 「裏側を見る（開発者向け）」。スキルの実行記録を、流れの順（受付 → 聞き手 → 言い直し →
 * 調査 → 照合（コード） → 説明）に並べる。担当者の詳細では、さらに 助言 → 案の確認（コード） → 下書き を足す。
 * 既定は閉じておく。
 * 開発者向けなので、この中に限りスキルやモデルの名前を使う。
 * @param props 実行記録（無ければすべての段が「まだ動いていません」になる）
 */
export function TracePanel({
  entries,
  operator = false,
}: {
  entries: TraceEntry[] | null | undefined;
  /** 担当者の詳細か（そうなら、助言、案の確認（コード）、下書きの3段を足す） */
  operator?: boolean;
}) {
  const messages = useMessages();
  const stages = summarizeTrace(entries, messages.trace, operator);
  return (
    <details className="trace-panel">
      <summary>{messages.user.traceSummary}</summary>
      <ol className="trace-list">
        {stages.map((stage) => (
          <StageRow key={stage.key} stage={stage} messages={messages} />
        ))}
      </ol>
    </details>
  );
}
