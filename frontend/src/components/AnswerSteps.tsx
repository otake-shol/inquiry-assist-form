import type { ZeroAnswer } from "../api/types.ts";
import { AnswerView } from "./AnswerView.tsx";

/** 生成中画面の props。 */
interface AnsweringStepProps {
  progress: string[];
  /** 失敗して止まっているか（進捗の「実行中」表示を止める） */
  stopped: boolean;
}

/**
 * 0次回答の生成中画面。進捗を順に表示する。
 * @param props 進捗メッセージ
 */
export function AnsweringStep({ progress, stopped }: AnsweringStepProps) {
  return (
    <div>
      <h2>0次回答を作っています</h2>
      <ol
        className="progress-list"
        aria-live="polite"
        aria-label="0次回答の進捗"
      >
        {progress.map((message, index) => (
          <li key={`${index}:${message}`}>{message}</li>
        ))}
      </ol>
      {!stopped && <p className="muted">しばらくお待ちください…</p>}
    </div>
  );
}

/** 解決判定画面の props。 */
interface AnsweredStepProps {
  answer: ZeroAnswer;
  busy: boolean;
  onResolve: (resolved: boolean) => void;
}

/**
 * 0次回答を見せ、「解決した」「解決しない」を選んでもらう画面。
 * @param props 0次回答と判定の受け口
 */
export function AnsweredStep({ answer, busy, onResolve }: AnsweredStepProps) {
  return (
    <div>
      <h2>0次回答</h2>
      <AnswerView answer={answer} />
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => onResolve(true)}>
          解決した
        </button>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() => onResolve(false)}
        >
          解決しない（担当者に送る）
        </button>
      </div>
    </div>
  );
}
