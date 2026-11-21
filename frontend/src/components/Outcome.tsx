import type { InquiryView, ZeroAnswer } from "../api/types.ts";
import { SOURCE_TYPE_LABELS, TEXT, closingText } from "../text/labels.ts";

/**
 * 調べている間の進捗。済んだ行にはチェック、最新の行には動きのある印をつける。
 * @param props 進捗メッセージと、止まっているか
 */
export function Researching({
  progress,
  stopped,
}: {
  progress: string[];
  stopped: boolean;
}) {
  return (
    <div className="system">
      <h2 tabIndex={-1} data-autofocus>
        {TEXT.researching}
      </h2>
      <ol
        className="progress-list"
        aria-live="polite"
        aria-label="調べている内容"
      >
        {progress.map((message, index) => {
          const latest = index === progress.length - 1 && !stopped;
          return (
            <li
              key={`${index}:${message}`}
              className={latest ? "latest" : "done"}
            >
              <span className="mark" aria-hidden="true" />
              {message}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/**
 * 箇条書きの1区分。項目が無ければ何も出さない。
 * @param props 見出しと項目
 */
function MemoList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <>
      <h4>{title}</h4>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </>
  );
}

/** 調べた結果の props。 */
interface ResultProps {
  answer: ZeroAnswer;
  /** 本人の解決判定を待っているか（待っていれば操作ボタンを出す） */
  awaitingResolution: boolean;
  busy: boolean;
  onResolve: (resolved: boolean) => void;
}

/**
 * 調べた結果。根拠があれば本人向けの説明を主に示し、資料と詳しい内容は折りたたむ。
 * 根拠がなければ見つからなかったと伝え、担当者に送ることを主のボタンにする。
 * @param props 結果と操作の受け口
 */
export function Result({
  answer,
  awaitingResolution,
  busy,
  onResolve,
}: ResultProps) {
  return (
    <div className="system result">
      <h2 tabIndex={-1} data-autofocus>
        {TEXT.resultTitle}
      </h2>
      <p className="answer-body">
        {answer.grounded ? answer.askerExplanation : TEXT.notFound}
      </p>
      {answer.grounded && answer.citations.length > 0 && (
        <details>
          <summary>{TEXT.citationsTitle(answer.citations.length)}</summary>
          <ul className="citations">
            {answer.citations.map((c) => (
              <li key={`${c.repository}:${c.path}:${c.startLine}`}>
                <div>
                  [{SOURCE_TYPE_LABELS[c.sourceType]}] {c.repository} / {c.path}
                  :{c.startLine}-{c.endLine}
                </div>
                <blockquote>{c.quote}</blockquote>
              </li>
            ))}
          </ul>
        </details>
      )}
      <details>
        <summary>{TEXT.memoTitle}</summary>
        <MemoList title={TEXT.memoFindings} items={answer.memo.findings} />
        {(answer.memo.unconfirmed ?? []).length > 0 && (
          <div className="memo-unconfirmed">
            <MemoList
              title={TEXT.memoUnconfirmed}
              items={answer.memo.unconfirmed ?? []}
            />
            <p className="memo-note">{TEXT.memoUnconfirmedNote}</p>
          </div>
        )}
        <MemoList title={TEXT.memoNotFound} items={answer.memo.notFound} />
        <MemoList title={TEXT.memoNextChecks} items={answer.memo.nextChecks} />
      </details>
      {awaitingResolution && (
        <div className="actions">
          {answer.grounded ? (
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => onResolve(true)}
              >
                {TEXT.resolved}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onResolve(false)}
              >
                {TEXT.notResolved}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => onResolve(false)}
              >
                {TEXT.sendToStaff}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onResolve(true)}
              >
                {TEXT.resolved}
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 不具合かもしれない件で、調べた結果を添えて担当者に送ったことを伝える。
 */
export function FiledNotice() {
  return <p className="system notice-inline">{TEXT.bugFiled}</p>;
}

/** 終わりの props。 */
interface ClosingProps {
  view: InquiryView;
  filedGuidance: string | null;
  onRestart: () => void;
}

/**
 * 結果ごとの終わりの文言と、送った後の案内。
 * @param props 終了した問い合わせと「新しい問い合わせ」の受け口
 */
export function Closing({ view, filedGuidance, onRestart }: ClosingProps) {
  const text = closingText(view, filedGuidance);
  return (
    <div className="system closing">
      <div tabIndex={-1} data-autofocus className="focus-target">
        <p className="lead">{text.lead}</p>
        {text.incident !== null && (
          <div className="incident" role="region" aria-label="障害の案内">
            <p>{text.incident}</p>
          </div>
        )}
        {text.guidance !== null && <p>{text.guidance}</p>}
      </div>
      <div className="actions">
        <button type="button" className="primary" onClick={onRestart}>
          {TEXT.restart}
        </button>
      </div>
    </div>
  );
}
