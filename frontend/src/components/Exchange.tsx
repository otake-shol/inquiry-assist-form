import { useId, useState, type FormEvent } from "react";
import type {
  Awaiting,
  InquiryMessage,
  MessageAuthor,
  NoReply,
  Translation,
} from "../api/types.ts";
import { useMessages } from "../i18n/context.ts";
import { formatFiledAt, formatMonthDay } from "../operator/labels.ts";
import type { HandoverText } from "../text/labels.ts";

/** 本文の最大文字数（契約書: 1〜4000文字）。 */
export const MAX_MESSAGE_LENGTH = 4000;

/** やり取りの一覧の props。 */
interface MessageThreadProps {
  messages: InquiryMessage[];
  /** 画面を見ている側（この側の発言を右に出す） */
  mine: MessageAuthor;
  /** 本人の言語。本人の追記に `lang` 属性として付ける（担当者の発言は言語が分からないので付けない） */
  askerLanguage?: string;
  /** 担当者に添える訳（担当者の画面だけ。本人の発言のすぐ下に添える） */
  translation?: Translation | null;
}

/**
 * 訳（AI）。原文のすぐ下に添える、控えめな見た目の注記。原文は置き換えない。
 * `lang` は訳した先の言語。該当する訳がなければ何も出さない。
 * @param props 訳の文章と、訳した先の言語
 */
export function TranslationNote({
  text,
  language,
}: {
  text: string | undefined;
  language: string;
}) {
  const { op } = useMessages();
  if (text === undefined) return null;
  return (
    <div className="op-translation">
      <p className="op-translation-label">{op.translationLabel}</p>
      <p className="op-translation-body" lang={language}>
        {text}
      </p>
    </div>
  );
}

/**
 * やり取りを古い順に並べる。見ている側の発言は右、相手の発言は左に出し、色でも分ける
 * （担当者の返信は暖色、本人の追記は青系）。新しい1通が加わったときは読み上げにも伝える。
 * 担当者の発言には、聞き返しか、結果か、見送りの連絡かが分かる小さな見出しをつける。
 * 一覧は空でも描き、あとから加わったものが伝わるようにする。
 * @param props やり取りと、見ている側
 */
export function MessageThread({
  messages,
  mine,
  askerLanguage,
  translation = null,
}: MessageThreadProps) {
  const { user: USER_TEXT, asker, meta } = useMessages();
  return (
    <ol
      className="thread"
      aria-label={USER_TEXT.exchange}
      aria-live="polite"
      aria-relevant="additions"
    >
      {messages.map((message) => (
        <li
          key={message.id}
          className={`message ${
            message.from === "OPERATOR" ? "by-operator" : "by-asker"
          } ${message.from === mine ? "mine" : "theirs"}`}
        >
          <p className="message-meta">
            {asker.messageKinds[message.kind] !== null && (
              <span className="message-kind">
                {asker.messageKinds[message.kind]}
              </span>
            )}
            <span className="message-author">{message.authorName}</span>
            <time dateTime={message.createdAt}>
              {formatFiledAt(message.createdAt, meta.locale)}
            </time>
          </p>
          <p
            className="message-body"
            lang={message.from === "ASKER" ? askerLanguage : undefined}
          >
            {message.body}
          </p>
          {message.from === "ASKER" && translation !== null && (
            <TranslationNote
              text={
                translation.items.find(
                  (item) => item.key === `message:${message.id}`,
                )?.translated
              }
              language={translation.to}
            />
          )}
        </li>
      ))}
    </ol>
  );
}

/** 本文の入力欄の props。 */
interface MessageFormProps {
  /** 入力欄の見出し */
  label: string;
  /** 送るボタンの文言 */
  submitLabel: string;
  busy: boolean;
  /** 送る。成功したら true を返す（入力欄を空にする） */
  onSubmit: (body: string) => Promise<boolean>;
}

/**
 * 本文の入力欄と送るボタン。空（空白だけを含む）の本文は送れない。
 * @param props 見出しと送る処理
 */
export function MessageForm({
  label,
  submitLabel,
  busy,
  onSubmit,
}: MessageFormProps) {
  const id = useId();
  const [text, setText] = useState("");
  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const body = text.trim();
    if (body === "" || busy) return;
    if (await onSubmit(body)) setText("");
  };
  return (
    <form className="message-form" onSubmit={(e) => void handleSubmit(e)}>
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      <div className="actions">
        <button
          type="submit"
          className="primary"
          disabled={busy || text.trim() === ""}
        >
          {submitLabel}
        </button>
      </div>
    </form>
  );
}

/**
 * 返事がないことの知らせ。知らせた後（`remindedAt` がある間）だけ、結果を確かめる選択肢、または
 * 聞き返しへの返事の入力欄のすぐ上に出す。終わり方（回答済み、取り下げ）で文言が変わる。
 * 知らせる前は何も出さない（急かさない）。日付は終わる予定の日を、見る人の言語の書式で（月と日）。
 * @param props 返事がないときの予定
 */
export function NoReplyNotice({ noReply }: { noReply: NoReply | null }) {
  const { user: USER_TEXT, meta } = useMessages();
  if (noReply === null || noReply.remindedAt === null) return null;
  if (noReply.closesAt === null) return null;
  const date = formatMonthDay(noReply.closesAt, meta.locale);
  return (
    <p className="notice-inline no-reply-notice">
      {noReply.endsAs === "WITHDRAWN"
        ? USER_TEXT.noReplyNoticeWithdrawn(date)
        : USER_TEXT.noReplyNotice(date)}
    </p>
  );
}

/** 担当者の結果の確かめの props。 */
interface ResultCheckProps {
  busy: boolean;
  /** 結果を確かめる。解決していないときは、任意の一言を付けられる */
  onResolve: (resolved: boolean, note?: string) => void;
  /** 返事がないときの予定 */
  noReply: NoReply | null;
}

/**
 * 担当者が結果を伝えた後の確かめ。「やりたかったことは、進められそうですか。」と、
 * 「解決しました」「まだ解決していません」を出す。後者を押すと、どこが解決していないかを書く欄
 * （任意）と「担当者に送る」が出る。
 * @param props 操作の受け口
 */
function ResultCheck({ busy, onResolve, noReply }: ResultCheckProps) {
  const { text: TEXT, user: USER_TEXT } = useMessages();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = note.trim();
    onResolve(false, trimmed === "" ? undefined : trimmed);
  };
  return (
    <div className="result-check">
      <NoReplyNotice noReply={noReply} />
      <p className="lead">{USER_TEXT.progressQuestion}</p>
      <div className="actions">
        <button
          type="button"
          className="primary"
          disabled={busy}
          onClick={() => onResolve(true)}
        >
          {TEXT.resolved}
        </button>
        {!open && (
          <button type="button" disabled={busy} onClick={() => setOpen(true)}>
            {USER_TEXT.stillUnresolved}
          </button>
        )}
      </div>
      {open && (
        <form className="revision-form" onSubmit={handleSubmit}>
          <label htmlFor={id}>{USER_TEXT.unresolvedNoteLabel}</label>
          <textarea
            id={id}
            rows={3}
            maxLength={MAX_MESSAGE_LENGTH}
            autoFocus
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>
              {USER_TEXT.unresolvedSend}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => setOpen(false)}
            >
              {TEXT.cancel}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/** 「いまの状況」の props。 */
interface FollowUpProps {
  /** 「いまの状況」の1行（本人に見せる言葉） */
  statusLabel: string;
  messages: InquiryMessage[];
  /** 待っていること（聞き返されているときは入力欄の見出しが変わり、結果が届いているときは確かめを出す） */
  awaiting: Awaiting | null;
  /** 追記の入力欄を出すか（担当者に渡したことがあり、取り下げていない件） */
  canAppend: boolean;
  /** 終わっている件か（追記で開き直せる旨を添える） */
  ended: boolean;
  /** 担当者に渡した直後の案内。担当者の発言がまだない間だけ出す */
  handover: HandoverText | null;
  busy: boolean;
  /** 追記か、聞き返しへの返事を送る。成功したら true */
  onSend: (body: string) => Promise<boolean>;
  /** 担当者の結果を確かめる */
  onResolve: (resolved: boolean, note?: string) => void;
  /** 新しい問い合わせを書き始める */
  onRestart: () => void;
  /** 本人の言語（本人の追記の `lang` 属性） */
  language?: string;
  /** 返事がないときの予定 */
  noReply?: NoReply | null;
  /** AI が引き継いだ理由の1文（「担当者が確認しています」のすぐ下）。無ければ添えない */
  handoverNote?: string | null;
}

/**
 * 担当者に渡した問い合わせの「いまの状況」とやり取り、追記の入力欄（本人向け）。
 * 担当者の発言がまだなければ、渡した直後の案内と、返信がここに出ることを伝える。
 * 聞き返されているときは入力欄の見出しを「担当者の質問に答える」にし、結果が届いているときは
 * 解決したかの確かめを出す。終わっていても、追記すれば担当者の番で開き直せる。
 * @param props 状況とやり取り
 */
export function FollowUp({
  statusLabel,
  messages,
  awaiting,
  canAppend,
  ended,
  handover,
  busy,
  onSend,
  onResolve,
  onRestart,
  language,
  noReply = null,
  handoverNote = null,
}: FollowUpProps) {
  const { user: USER_TEXT } = useMessages();
  const hasReply = messages.some((message) => message.from === "OPERATOR");
  return (
    <div className="system followup">
      <h2 tabIndex={-1} data-autofocus>
        {USER_TEXT.currentStatus}
      </h2>
      <p className="handling-status" aria-live="polite">
        {statusLabel}
      </p>
      {handoverNote !== null && (
        <p className="handover-reason">{handoverNote}</p>
      )}
      {!hasReply && handover !== null && (
        <div className="handover">
          <p>{handover.lead}</p>
          {handover.guidance !== null && <p>{handover.guidance}</p>}
        </div>
      )}
      <MessageThread
        messages={messages}
        mine="ASKER"
        askerLanguage={language}
      />
      {!hasReply && handover !== null && (
        <p className="muted">{USER_TEXT.noReplyYet}</p>
      )}
      {awaiting === "RESULT_CONFIRMATION" && (
        <ResultCheck busy={busy} onResolve={onResolve} noReply={noReply} />
      )}
      {canAppend && (
        <>
          {ended && <p className="muted">{USER_TEXT.reopenNote}</p>}
          {awaiting === "ASKER_REPLY" && <NoReplyNotice noReply={noReply} />}
          <MessageForm
            label={
              awaiting === "ASKER_REPLY"
                ? USER_TEXT.replyLabel
                : USER_TEXT.appendLabel
            }
            submitLabel={USER_TEXT.appendSubmit}
            busy={busy}
            onSubmit={onSend}
          />
        </>
      )}
      {/* やり取りが主役の場面なので、別件の入口は最後に、控えめに置く */}
      <div className="actions followup-restart">
        <button type="button" onClick={onRestart}>
          {USER_TEXT.restartOther}
        </button>
      </div>
    </div>
  );
}
