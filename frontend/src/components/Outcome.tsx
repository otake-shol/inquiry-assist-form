import type {
  ClosedBy,
  Declined,
  Ending,
  NoReply,
  ZeroAnswer,
} from "../api/types.ts";
import { NoReplyNotice } from "./Exchange.tsx";
import { useMessages } from "../i18n/context.ts";
import { endingText } from "../text/inquiries.ts";

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
  const { text: TEXT } = useMessages();
  return (
    <div className="system">
      <h2 tabIndex={-1} data-autofocus>
        {TEXT.researching}
      </h2>
      <ol
        className="progress-list"
        aria-live="polite"
        aria-label={TEXT.researchingLabel}
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
function MemoList({
  title,
  items,
  language,
}: {
  title: string;
  items: string[];
  /** 項目（AI が書いた文）の言語 */
  language?: string;
}) {
  if (items.length === 0) return null;
  return (
    <>
      <h4>{title}</h4>
      <ul>
        {items.map((item) => (
          <li key={item} lang={language}>
            {item}
          </li>
        ))}
      </ul>
    </>
  );
}

/** 調べた結果の props。 */
interface ResultProps {
  answer: ZeroAnswer;
  /** 本人が AI の答えを確かめる番か（そうなら、進められそうかの問いと操作ボタンを出す） */
  awaitingResolution: boolean;
  busy: boolean;
  /** 「やってほしい」か（解決の判定を「自分でできました」「担当者に頼む」で聞く） */
  doForMe?: boolean;
  /** 説明（AI が書いた文）の言語。問い合わせの `language` */
  language?: string;
  /** 返事がないときの予定（知らせた後は、確かめる選択肢のすぐ上に知らせを出す） */
  noReply?: NoReply | null;
  onResolve: (resolved: boolean) => void;
}

/**
 * 調べた結果。根拠があれば本人向けの説明を主に示し、資料と詳しい内容は折りたたむ。
 * 根拠がなければ見つからなかったと伝え、担当者に送ることを主のボタンにする。
 * 「やってほしい」で自分でできる手順が見つかったときは、「自分でできました」「担当者に頼む」を出す。
 * @param props 結果と操作の受け口
 */
export function Result({
  answer,
  awaitingResolution,
  busy,
  doForMe = false,
  language,
  noReply = null,
  onResolve,
}: ResultProps) {
  const { text: TEXT, user: USER_TEXT, sources } = useMessages();
  // 古い応答（answered が無い）では grounded と同じ値として扱う
  const answered = answer.answered ?? answer.grounded;
  return (
    <div className="system result">
      <h2 tabIndex={-1} data-autofocus>
        {TEXT.resultTitle}
      </h2>
      {answer.grounded && answered && (
        <p className="answer-body" lang={language}>
          {answer.askerExplanation}
        </p>
      )}
      {answer.grounded && !answered && (
        <>
          <p className="answer-body">{TEXT.notAnswered}</p>
          <h3 className="related-title">{TEXT.relatedTitle}</h3>
          <p className="answer-body related" lang={language}>
            {answer.askerExplanation}
          </p>
        </>
      )}
      {!answer.grounded && <p className="answer-body">{TEXT.notFound}</p>}
      {answer.grounded && answer.citations.length > 0 && (
        <details>
          <summary>{TEXT.citationsTitle(answer.citations.length)}</summary>
          <ul className="citations">
            {answer.citations.map((c) => (
              <li key={`${c.repository}:${c.path}:${c.startLine}`}>
                <div>
                  [{sources[c.sourceType]}] {c.repository} / {c.path}:
                  {c.startLine}-{c.endLine}
                </div>
                <blockquote lang="ja">{c.quote}</blockquote>
              </li>
            ))}
          </ul>
        </details>
      )}
      <details>
        <summary>{TEXT.memoTitle}</summary>
        <MemoList
          title={TEXT.memoFindings}
          items={answer.memo.findings}
          language={language}
        />
        <MemoList
          title={TEXT.memoNotFound}
          items={answer.memo.notFound}
          language={language}
        />
        <MemoList
          title={TEXT.memoNextChecks}
          items={answer.memo.nextChecks}
          language={language}
        />
      </details>
      {awaitingResolution && <NoReplyNotice noReply={noReply} />}
      {awaitingResolution && (
        <p className="progress-question">{USER_TEXT.progressQuestion}</p>
      )}
      {awaitingResolution && (
        <div className="actions">
          {answered ? (
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => onResolve(true)}
              >
                {doForMe ? TEXT.doneByMyself : TEXT.resolved}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onResolve(false)}
              >
                {doForMe ? TEXT.askStaff : TEXT.notResolved}
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
                {doForMe ? TEXT.askStaff : TEXT.sendToStaff}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onResolve(true)}
              >
                {doForMe ? TEXT.doneByMyself : TEXT.resolved}
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 調べた内容を添えて担当者に送ったことを、結果の上で伝える。
 * 「おかしいかもしれない」は必ず送る。「やってほしい」は自分でできる手順が見つからなかったとき。
 * @param props 案内の種類
 */
export function FiledNotice({ text }: { text: "lookWrong" | "doForMe" }) {
  const { text: TEXT } = useMessages();
  return (
    <p className="system notice-inline">
      {text === "lookWrong" ? TEXT.bugFiled : TEXT.doForMeFiled}
    </p>
  );
}

/** 終わりの props。 */
interface ClosingProps {
  ending: Ending;
  /** 見送りの理由（見送りで終わった件だけ） */
  declined: Declined | null;
  /** 終わらせた人（時間で「取り下げ」になった件は、専用の終わりの文言） */
  closedBy?: ClosedBy | null;
  onRestart: () => void;
  /** 「新しい問い合わせを書く」をここに出すか。やり取りが続く場合は、やり取りの下に出すので false */
  showRestart?: boolean;
}

/**
 * 終わり方ごとの終わりの文言（解決済み、回答済み、見送り、取り下げ）。見送りは担当者が書いた理由を続ける。
 * 担当者に渡しただけでは出さない（問い合わせは終わっていないため）。
 * @param props 終わり方と「新しい問い合わせ」の受け口
 */
export function Closing({
  ending,
  declined,
  closedBy = null,
  onRestart,
  showRestart = true,
}: ClosingProps) {
  const { text: TEXT, asker } = useMessages();
  const text = endingText(ending, declined, asker, closedBy);
  return (
    <div className="system closing">
      <div tabIndex={-1} data-autofocus className="focus-target">
        <p className="lead">{text.lead}</p>
        {text.reason !== null && (
          <p className="decline-reason">{text.reason}</p>
        )}
      </div>
      {showRestart && (
        <div className="actions">
          <button type="button" className="primary" onClick={onRestart}>
            {TEXT.restart}
          </button>
        </div>
      )}
    </div>
  );
}
