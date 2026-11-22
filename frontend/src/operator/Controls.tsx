import { useId, useState, type FormEvent } from "react";
import type { ReplyKind, PlacementInput } from "../api/client.ts";
import type {
  ConfigSystem,
  DeclineReasonKind,
  DraftResult,
  HistoryEntry,
  OperatorDetail,
  Phase,
  TypeOption,
} from "../api/types.ts";
import { MAX_MESSAGE_LENGTH } from "../components/Exchange.tsx";
import { useMessages } from "../i18n/context.ts";
import { languageName } from "../i18n/messages.ts";
import {
  SHOWN_PHASES,
  eventLabel,
  formatFiledAt,
  placementChangeText,
  stateLine,
} from "./labels.ts";

/** 返信の下書きで、伝えたいことの最大文字数（契約書: 1〜2000文字）。 */
const MAX_DRAFT_NOTE_LENGTH = 2000;

/** 見送りの理由の文章の最大文字数（契約書: 1〜2000文字）。 */
const MAX_DECLINE_REASON_LENGTH = 2000;

/** 区間の表示の props。 */
interface PhaseIndicatorProps {
  /** いまの区間 */
  phase: Phase;
}

/**
 * いまの区間（分かる、決まる、行われる）を示す。見るだけで、押せない（区間は種類で決まり、置き直すと変わる）。
 * いまの区間に `aria-current="step"` を付け、色でも示す。受付と終わりのときは、どれも示さない。
 * @param props いまの区間
 */
export function PhaseIndicator({ phase }: PhaseIndicatorProps) {
  const OP_TEXT = useMessages().op;
  return (
    <ol className="op-phase-list" aria-label={OP_TEXT.phaseLabel}>
      {SHOWN_PHASES.map((candidate) => (
        <li
          key={candidate}
          className={phase === candidate ? "current" : ""}
          aria-current={phase === candidate ? "step" : undefined}
        >
          {OP_TEXT.phases[candidate]}
        </li>
      ))}
    </ol>
  );
}

/** 置き直しの props。 */
interface PlacementFormProps {
  detail: OperatorDetail;
  /** 設定の種類 */
  types: TypeOption[];
  /** 設定のシステム（機能つき） */
  systems: ConfigSystem[];
  busy: boolean;
  /** 置き直す。成功したら true */
  onSubmit: (placement: PlacementInput) => Promise<boolean>;
}

/**
 * 置き直す（折りたたみ）。種類、システム、機能を選び直して「置き直す」。
 * 機能の選択肢は、選んだシステムのもの。システムを変えると、機能は未定に戻る。
 * @param props 詳細と、選択肢、置き直しの受け口
 */
export function PlacementForm({
  detail,
  types,
  systems,
  busy,
  onSubmit,
}: PlacementFormProps) {
  const messages = useMessages();
  const OP_TEXT = messages.op;
  const id = useId();
  const [type, setType] = useState(detail.type?.id ?? "");
  const [systemId, setSystemId] = useState(detail.system?.id ?? "");
  const [functionId, setFunctionId] = useState(detail.function?.id ?? "");
  const functions =
    systems.find((system) => system.id === systemId)?.functions ?? [];

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    void onSubmit({
      type: type === "" ? null : type,
      systemId: systemId === "" ? null : systemId,
      functionId: functionId === "" ? null : functionId,
    });
  };
  return (
    <details className="op-fold">
      <summary>{OP_TEXT.placementSummary}</summary>
      <form className="op-placement" onSubmit={handleSubmit}>
        <label htmlFor={`${id}-type`}>{OP_TEXT.placementType}</label>
        <select
          id={`${id}-type`}
          value={type}
          onChange={(event) => setType(event.target.value)}
        >
          <option value="">{OP_TEXT.placementUnset}</option>
          {types.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-system`}>{OP_TEXT.placementSystem}</label>
        <select
          id={`${id}-system`}
          value={systemId}
          onChange={(event) => {
            setSystemId(event.target.value);
            setFunctionId("");
          }}
        >
          <option value="">{OP_TEXT.placementUnset}</option>
          {systems.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-function`}>{OP_TEXT.placementFunction}</label>
        <select
          id={`${id}-function`}
          value={functionId}
          onChange={(event) => setFunctionId(event.target.value)}
        >
          <option value="">{OP_TEXT.placementUnset}</option>
          {functions.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
        <div className="actions">
          <button type="submit" className="primary" disabled={busy}>
            {OP_TEXT.placementSubmit}
          </button>
        </div>
      </form>
    </details>
  );
}

/** 「本人に送る」の props。 */
interface SendToAskerProps {
  busy: boolean;
  /** 本人に送る。成功したら true */
  onSend: (kind: ReplyKind, body: string) => Promise<boolean>;
  /** 返信の下書きを作らせる。失敗したら null（失敗の表示は呼び出し側） */
  onDraft: (kind: ReplyKind, note: string) => Promise<DraftResult | null>;
}

/**
 * 本人に送る（担当者の番のときだけ）。入力欄1つと、2つのボタン「聞き返す」「結果を伝える」。
 * 押したボタンで、本人の番になったときに本人が何を求められるかが決まる。
 * 入力欄のすぐ上に、返信の下書き（「伝えたいこと（任意）」の1行の入力欄と、2つの小さなボタン）を置く。
 * 下書きは入力欄が空のときだけ作らせられ、入ったら注意書きを出す（送るか、入力欄を空にすると消える）。
 * AI が直接送ることはなく、送るボタンは担当者が選んで押す（下書きの種類では決めない）。
 * @param props 送る処理と、下書きの処理
 */
export function SendToAsker({ busy, onSend, onDraft }: SendToAskerProps) {
  const messages = useMessages();
  const OP_TEXT = messages.op;
  const id = useId();
  const noteId = useId();
  const [text, setText] = useState("");
  const [note, setNote] = useState("");
  const [drafting, setDrafting] = useState(false);
  // 下書きが入っているか。入っていれば、書かれた言語（本人の言語）を持つ
  const [draftLanguage, setDraftLanguage] = useState<string | null>(null);
  const body = text.trim();

  const send = async (kind: ReplyKind) => {
    if (body === "" || busy) return;
    if (await onSend(kind, body)) {
      setText("");
      setDraftLanguage(null);
    }
  };

  const draft = async (kind: ReplyKind) => {
    if (body !== "" || busy || drafting) return;
    setDrafting(true);
    try {
      const result = await onDraft(kind, note.trim());
      if (result !== null) {
        setText(result.body);
        setDraftLanguage(result.language);
      }
    } finally {
      setDrafting(false);
    }
  };
  const draftDisabled = busy || drafting || body !== "";
  return (
    <form
      className="op-send"
      onSubmit={(event) => {
        // Enter だけで送ると、種類が決まらない。ボタンを押して送る
        event.preventDefault();
      }}
    >
      <div className="op-draft">
        <label htmlFor={noteId}>{OP_TEXT.draftNote}</label>
        <input
          id={noteId}
          type="text"
          maxLength={MAX_DRAFT_NOTE_LENGTH}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
        <div className="op-draft-actions">
          <button
            type="button"
            className="small"
            disabled={draftDisabled}
            onClick={() => void draft("ASK")}
          >
            {OP_TEXT.draftAsk}
          </button>
          <button
            type="button"
            className="small"
            disabled={draftDisabled}
            onClick={() => void draft("RESULT")}
          >
            {OP_TEXT.draftResult}
          </button>
        </div>
        {body !== "" && <p className="op-muted">{OP_TEXT.draftHint}</p>}
      </div>
      <label htmlFor={id}>{OP_TEXT.sendLabel}</label>
      <textarea
        id={id}
        rows={4}
        maxLength={MAX_MESSAGE_LENGTH}
        value={text}
        onChange={(event) => {
          setText(event.target.value);
          if (event.target.value.trim() === "") setDraftLanguage(null);
        }}
      />
      {draftLanguage !== null && body !== "" && (
        <p className="op-draft-notice" role="status">
          {OP_TEXT.draftNotice(languageName(draftLanguage))}
        </p>
      )}
      <div className="actions">
        <button
          type="button"
          className="primary"
          disabled={busy || body === ""}
          onClick={() => void send("ASK")}
        >
          {OP_TEXT.sendAsk}
        </button>
        <button
          type="button"
          disabled={busy || body === ""}
          onClick={() => void send("RESULT")}
        >
          {OP_TEXT.sendResult}
        </button>
      </div>
      <p className="op-muted">{OP_TEXT.sendAskHint}</p>
      <p className="op-muted">{OP_TEXT.sendResultHint}</p>
    </form>
  );
}

/** 見送りの props。 */
interface DeclineFormProps {
  busy: boolean;
  /** 見送りとして終える。成功したら true */
  onSubmit: (kind: DeclineReasonKind, reason: string) => Promise<boolean>;
}

/**
 * 見送る（担当者の番のときだけ。折りたたみ）。理由の種類と文章を選んで「見送りとして終える」。
 * @param props 送る処理
 */
export function DeclineForm({ busy, onSubmit }: DeclineFormProps) {
  const messages = useMessages();
  const OP_TEXT = messages.op;
  const id = useId();
  const [kind, setKind] = useState<DeclineReasonKind>("NOT_REPRODUCIBLE");
  const [reason, setReason] = useState("");
  const trimmed = reason.trim();

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (trimmed === "" || busy) return;
    if (await onSubmit(kind, trimmed)) setReason("");
  };
  return (
    <details className="op-fold">
      <summary>{OP_TEXT.declineSummary}</summary>
      <form className="op-decline" onSubmit={(e) => void handleSubmit(e)}>
        <label htmlFor={`${id}-kind`}>{OP_TEXT.declineKind}</label>
        <select
          id={`${id}-kind`}
          value={kind}
          onChange={(event) => setKind(event.target.value as DeclineReasonKind)}
        >
          {(
            Object.entries(OP_TEXT.declineReasons) as [
              DeclineReasonKind,
              string,
            ][]
          ).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-reason`}>{OP_TEXT.declineReason}</label>
        <textarea
          id={`${id}-reason`}
          rows={3}
          maxLength={MAX_DECLINE_REASON_LENGTH}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
        <div className="actions">
          <button
            type="submit"
            className="primary"
            disabled={busy || trimmed === ""}
          >
            {OP_TEXT.declineSubmit}
          </button>
        </div>
      </form>
    </details>
  );
}

/**
 * 状態の履歴（折りたたみ）。いつ、誰が、何をして、誰の番になったかを、古い順に出す。
 * @param props 履歴
 */
export function HistoryList({ history }: { history: HistoryEntry[] }) {
  const messages = useMessages();
  const OP_TEXT = messages.op;
  return (
    <details className="op-fold op-history">
      <summary>{OP_TEXT.historySummary}</summary>
      {history.length === 0 ? (
        <p className="op-muted">{OP_TEXT.historyEmpty}</p>
      ) : (
        <ol>
          {history.map((entry, index) => (
            <li key={`${entry.at}:${index}`}>
              <time dateTime={entry.at}>
                {formatFiledAt(entry.at, messages.meta.locale)}
              </time>
              <span className="op-history-actor">
                {OP_TEXT.actors[entry.actor]}
              </span>
              <span>{eventLabel(entry.event, OP_TEXT)}</span>
              {entry.detail != null && (
                <span className="op-history-detail">
                  {placementChangeText(entry.detail, messages)}
                </span>
              )}
              <span className="op-muted">→ {stateLine(entry, messages)}</span>
            </li>
          ))}
        </ol>
      )}
    </details>
  );
}
