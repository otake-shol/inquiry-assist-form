import { useId, useState, type FormEvent } from "react";
import type {
  HandlingStatus,
  InquiryMessage,
  MessageAuthor,
} from "../api/types.ts";
import { formatFiledAt } from "../operator/labels.ts";
import { USER_TEXT, handlingStatusLabel } from "../text/inquiries.ts";

/** 本文の最大文字数（契約書: 1〜4000文字）。 */
export const MAX_MESSAGE_LENGTH = 4000;

/** やり取りの一覧の props。 */
interface MessageThreadProps {
  messages: InquiryMessage[];
  /** 画面を見ている側（この側の発言を右に出す） */
  mine: MessageAuthor;
}

/**
 * やり取りを古い順に並べる。見ている側の発言は右、相手の発言は左に出し、色でも分ける
 * （担当者の返信は暖色、本人の追記は青系）。新しい1通が加わったときは読み上げにも伝える。
 * 一覧は空でも描き、あとから加わったものが伝わるようにする。
 * @param props やり取りと、見ている側
 */
export function MessageThread({ messages, mine }: MessageThreadProps) {
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
            <span className="message-author">{message.authorName}</span>
            <time dateTime={message.createdAt}>
              {formatFiledAt(message.createdAt)}
            </time>
          </p>
          <p className="message-body">{message.body}</p>
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

/** 「いまの状況」の props。 */
interface FollowUpProps {
  status: HandlingStatus | null | undefined;
  messages: InquiryMessage[];
  busy: boolean;
  /** 追記を送る。成功したら true */
  onSend: (body: string) => Promise<boolean>;
  /** 新しい問い合わせを書き始める */
  onRestart: () => void;
}

/**
 * 担当者に送った問い合わせの「いまの状況」とやり取り、追記の入力欄（本人向け）。
 * 担当者からの返信がまだなければ、返信がここに出ることを伝える。
 * @param props 対応状況とやり取り
 */
export function FollowUp({
  status,
  messages,
  busy,
  onSend,
  onRestart,
}: FollowUpProps) {
  const hasReply = messages.some((message) => message.from === "OPERATOR");
  return (
    <div className="system followup">
      <h2 tabIndex={-1} data-autofocus>
        {USER_TEXT.currentStatus}
      </h2>
      <p className="handling-status">{handlingStatusLabel(status)}</p>
      <MessageThread messages={messages} mine="ASKER" />
      {!hasReply && <p className="muted">{USER_TEXT.noReplyYet}</p>}
      <MessageForm
        label={USER_TEXT.appendLabel}
        submitLabel={USER_TEXT.appendSubmit}
        busy={busy}
        onSubmit={onSend}
      />
      {/* やり取りが主役の場面なので、別件の入口は最後に、控えめに置く */}
      <div className="actions followup-restart">
        <button type="button" onClick={onRestart}>
          {USER_TEXT.restartOther}
        </button>
      </div>
    </div>
  );
}
