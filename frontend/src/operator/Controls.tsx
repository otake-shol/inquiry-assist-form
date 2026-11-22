import { useId, useState, type FormEvent, type RefObject } from "react";
import type { ReplyKind, PlacementInput } from "../api/client.ts";
import type {
  ConfigSystem,
  DeclineReasonKind,
  HistoryEntry,
  OperatorDetail,
  Phase,
  TypeOption,
} from "../api/types.ts";
import { MAX_MESSAGE_LENGTH } from "../components/Exchange.tsx";
import {
  ACTOR_LABELS,
  DECLINE_REASON_LABELS,
  OP_TEXT,
  PHASE_LABELS,
  SWITCHABLE_PHASES,
  eventLabel,
  formatFiledAt,
  placementChangeText,
  stateLine,
} from "./labels.ts";

/** 見送りの理由の文章の最大文字数（契約書: 1〜2000文字）。 */
const MAX_DECLINE_REASON_LENGTH = 2000;

/** 区間の切り替えの props。 */
interface PhaseSwitchProps {
  /** いまの区間 */
  phase: Phase;
  /** 切り替えられるか（担当者の番のときだけ） */
  enabled: boolean;
  busy: boolean;
  onChange: (phase: Phase) => void;
}

/**
 * 区間（分かる、決まる、行われる）の切り替え。担当者の番のときだけ押せる。
 * 押せないときは、その理由を添える。
 * @param props いまの区間と切り替えの受け口
 */
export function PhaseSwitch({
  phase,
  enabled,
  busy,
  onChange,
}: PhaseSwitchProps) {
  return (
    <div className="op-phase">
      <div
        className="op-status-switch"
        role="group"
        aria-label={OP_TEXT.phaseSwitchLabel}
      >
        {SWITCHABLE_PHASES.map((candidate) => (
          <button
            key={candidate}
            type="button"
            aria-pressed={phase === candidate}
            disabled={busy || !enabled}
            onClick={() => {
              if (phase !== candidate) onChange(candidate);
            }}
          >
            {PHASE_LABELS[candidate]}
          </button>
        ))}
      </div>
      {!enabled && <p className="op-muted">{OP_TEXT.phaseSwitchHint}</p>}
    </div>
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
  /** 入力欄（ドラッグで落としたときにフォーカスを移す） */
  inputRef: RefObject<HTMLTextAreaElement | null>;
  /** 本人に送る。成功したら true */
  onSend: (kind: ReplyKind, body: string) => Promise<boolean>;
}

/**
 * 本人に送る（担当者の番のときだけ）。入力欄1つと、2つのボタン「聞き返す」「結果を伝える」。
 * 押したボタンで、本人の番になったときに本人が何を求められるかが決まる。
 * @param props 入力欄の参照と、送る処理
 */
export function SendToAsker({ busy, inputRef, onSend }: SendToAskerProps) {
  const id = useId();
  const [text, setText] = useState("");
  const body = text.trim();

  const send = async (kind: ReplyKind) => {
    if (body === "" || busy) return;
    if (await onSend(kind, body)) setText("");
  };
  return (
    <form
      className="op-send"
      onSubmit={(event) => {
        // Enter だけで送ると、種類が決まらない。ボタンを押して送る
        event.preventDefault();
      }}
    >
      <label htmlFor={id}>{OP_TEXT.sendLabel}</label>
      <textarea
        id={id}
        ref={inputRef}
        rows={4}
        maxLength={MAX_MESSAGE_LENGTH}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
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
  /** 折りたたみを開いているか */
  open: boolean;
  onToggle: (open: boolean) => void;
  busy: boolean;
  /** 理由の文章の入力欄（ドラッグで落としたときにフォーカスを移す） */
  reasonRef: RefObject<HTMLTextAreaElement | null>;
  /** 見送りとして終える。成功したら true */
  onSubmit: (kind: DeclineReasonKind, reason: string) => Promise<boolean>;
}

/**
 * 見送る（担当者の番のときだけ。折りたたみ）。理由の種類と文章を選んで「見送りとして終える」。
 * @param props 開閉と、入力欄の参照、送る処理
 */
export function DeclineForm({
  open,
  onToggle,
  busy,
  reasonRef,
  onSubmit,
}: DeclineFormProps) {
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
    <details
      className="op-fold"
      open={open}
      onToggle={(event) => onToggle(event.currentTarget.open)}
    >
      <summary>{OP_TEXT.declineSummary}</summary>
      <form className="op-decline" onSubmit={(e) => void handleSubmit(e)}>
        <label htmlFor={`${id}-kind`}>{OP_TEXT.declineKind}</label>
        <select
          id={`${id}-kind`}
          value={kind}
          onChange={(event) => setKind(event.target.value as DeclineReasonKind)}
        >
          {(
            Object.entries(DECLINE_REASON_LABELS) as [
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
          ref={reasonRef}
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
  return (
    <details className="op-fold op-history">
      <summary>{OP_TEXT.historySummary}</summary>
      {history.length === 0 ? (
        <p className="op-muted">{OP_TEXT.historyEmpty}</p>
      ) : (
        <ol>
          {history.map((entry, index) => (
            <li key={`${entry.at}:${index}`}>
              <time dateTime={entry.at}>{formatFiledAt(entry.at)}</time>
              <span className="op-history-actor">
                {ACTOR_LABELS[entry.actor]}
              </span>
              <span>{eventLabel(entry.event)}</span>
              {entry.detail != null && (
                <span className="op-history-detail">
                  {placementChangeText(entry.detail)}
                </span>
              )}
              <span className="op-muted">→ {stateLine(entry)}</span>
            </li>
          ))}
        </ol>
      )}
    </details>
  );
}
