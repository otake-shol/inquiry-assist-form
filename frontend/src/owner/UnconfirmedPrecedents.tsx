import { useId, useState, type FormEvent } from "react";
import {
  DECISION_KINDS,
  type DecisionKind,
  type Precedent,
  type PrecedentUpdate,
} from "../api/types.ts";
import { DecisionCard } from "../operator/Consultation.tsx";
import { MAX_NAME_LENGTH } from "../operator/consultations.ts";
import { MAX_MESSAGE_LENGTH } from "../components/Exchange.tsx";
import { useMessages } from "../i18n/context.ts";

/** PrecedentEditor の props。 */
interface PrecedentEditorProps {
  precedent: Precedent;
  busy: boolean;
  failed: boolean;
  onSave: (update: PrecedentUpdate) => void;
  onCancel: () => void;
}

/**
 * 先例をその場で直すフォーム。状況、決定の種類、決定の内容、理由、決めた人を、いまの内容から始めて直す。
 * どれかが空なら保存できない。
 * @param props 先例と、保存、やめる操作
 */
function PrecedentEditor({
  precedent,
  busy,
  failed,
  onSave,
  onCancel,
}: PrecedentEditorProps) {
  const { op: OP_TEXT, owner: OWNER_TEXT } = useMessages();
  const id = useId();
  const [situation, setSituation] = useState(precedent.situation);
  const [kind, setKind] = useState<DecisionKind>(precedent.decisionKind);
  const [decision, setDecision] = useState(precedent.decision);
  const [reason, setReason] = useState(precedent.reason);
  const [decidedBy, setDecidedBy] = useState(precedent.decidedBy);
  const canSave =
    !busy &&
    situation.trim() !== "" &&
    decision.trim() !== "" &&
    reason.trim() !== "" &&
    decidedBy.trim() !== "";

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSave) return;
    onSave({
      situation: situation.trim(),
      decisionKind: kind,
      decision: decision.trim(),
      reason: reason.trim(),
      decidedBy: decidedBy.trim(),
    });
  };
  return (
    <form className="decision-form" onSubmit={handleSubmit}>
      <label htmlFor={`${id}-situation`}>{OWNER_TEXT.situation}</label>
      <textarea
        id={`${id}-situation`}
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={situation}
        onChange={(event) => setSituation(event.target.value)}
      />
      <label htmlFor={`${id}-kind`}>{OWNER_TEXT.kind}</label>
      <select
        id={`${id}-kind`}
        value={kind}
        onChange={(event) => setKind(event.target.value as DecisionKind)}
      >
        {DECISION_KINDS.map((value) => (
          <option key={value} value={value}>
            {OP_TEXT.decisionKinds[value]}
          </option>
        ))}
      </select>
      <label htmlFor={`${id}-decision`}>{OWNER_TEXT.decision}</label>
      <textarea
        id={`${id}-decision`}
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={decision}
        onChange={(event) => setDecision(event.target.value)}
      />
      <label htmlFor={`${id}-reason`}>{OWNER_TEXT.reason}</label>
      <textarea
        id={`${id}-reason`}
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
      />
      <label htmlFor={`${id}-by`}>{OWNER_TEXT.decidedBy}</label>
      <input
        id={`${id}-by`}
        type="text"
        maxLength={MAX_NAME_LENGTH}
        value={decidedBy}
        onChange={(event) => setDecidedBy(event.target.value)}
      />
      <div className="actions">
        <button type="submit" className="primary" disabled={!canSave}>
          {OWNER_TEXT.save}
        </button>
        <button type="button" disabled={busy} onClick={onCancel}>
          {OWNER_TEXT.cancel}
        </button>
      </div>
      {failed && (
        <p className="op-muted" role="alert">
          {OP_TEXT.actionFailed}
        </p>
      )}
    </form>
  );
}

/** UnconfirmedPrecedents の props。 */
interface UnconfirmedPrecedentsProps {
  /** 確かめられていない先例 */
  items: Precedent[];
  /** 確かめた。成功したら true */
  onConfirm: (id: string) => Promise<boolean>;
  /** 直した（保存すると、確かめたことになる）。成功したら true */
  onUpdate: (id: string, update: PrecedentUpdate) => Promise<boolean>;
}

/** UnconfirmedCard の props。 */
interface UnconfirmedCardProps {
  precedent: Precedent;
  onConfirm: UnconfirmedPrecedentsProps["onConfirm"];
  onUpdate: UnconfirmedPrecedentsProps["onUpdate"];
}

/**
 * 確かめてほしい先例の1件。内容と、「このとおりです」「直す」。「直す」を押すと、その場で項目を直して保存する。
 * @param props 先例と、操作の受け口
 */
function UnconfirmedCard({
  precedent,
  onConfirm,
  onUpdate,
}: UnconfirmedCardProps) {
  const OWNER_TEXT = useMessages().owner;
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  /** 操作を実行する。成功すると一覧から消えるので、失敗したときだけ印を残す。 */
  const run = async (operation: () => Promise<boolean>) => {
    setBusy(true);
    setFailed(false);
    const ok = await operation();
    if (!ok) {
      setFailed(true);
      setBusy(false);
    }
  };
  return (
    <DecisionCard decision={precedent} showPlace>
      {editing ? (
        <PrecedentEditor
          precedent={precedent}
          busy={busy}
          failed={failed}
          onSave={(update) => void run(() => onUpdate(precedent.id, update))}
          onCancel={() => {
            setEditing(false);
            setFailed(false);
          }}
        />
      ) : (
        <div className="actions">
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => void run(() => onConfirm(precedent.id))}
          >
            {OWNER_TEXT.confirm}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => setEditing(true)}
          >
            {OWNER_TEXT.edit}
          </button>
        </div>
      )}
      {!editing && failed && <FailedNote />}
    </DecisionCard>
  );
}

/** 操作に失敗したときの1行。 */
function FailedNote() {
  const OP_TEXT = useMessages().op;
  return (
    <p className="op-muted" role="alert">
      {OP_TEXT.actionFailed}
    </p>
  );
}

/**
 * 確かめてほしい先例の節（相談の一覧の下）。担当者が代わりに記録した、確かめられていない先例を並べる。
 * なければ、この節を出さない。
 * @param props 先例と、操作の受け口
 */
export function UnconfirmedPrecedents({
  items,
  onConfirm,
  onUpdate,
}: UnconfirmedPrecedentsProps) {
  const OWNER_TEXT = useMessages().owner;
  if (items.length === 0) return null;
  return (
    <section
      className="owner-unconfirmed"
      aria-label={OWNER_TEXT.unconfirmedTitle}
    >
      <h2>{OWNER_TEXT.unconfirmedTitle}</h2>
      {items.map((precedent) => (
        <UnconfirmedCard
          key={precedent.id}
          precedent={precedent}
          onConfirm={onConfirm}
          onUpdate={onUpdate}
        />
      ))}
    </section>
  );
}
