import { useId, useState, type FormEvent } from "react";
import {
  DECISION_KINDS,
  type DecisionKind,
  type DecisionRequest,
} from "../api/types.ts";
import { useMessages } from "../i18n/context.ts";
import { MAX_NAME_LENGTH } from "../operator/consultations.ts";
import { MAX_MESSAGE_LENGTH } from "./Exchange.tsx";

/** 「決めた人」の選択肢で、「ほかの人」を表す値。名前としては使わない。 */
const OTHER_PERSON = "\u0000other";

/** DecisionForm の props。 */
interface DecisionFormProps {
  /** 状況の初期値 */
  initialSituation: string;
  /** 決めた人の選択肢（相談先の名前） */
  names: string[];
  /** 「決定を記録する」の下に添える説明 */
  note: string;
  busy: boolean;
  /** 直近の操作が失敗したか */
  failed: boolean;
  /** 決定を記録する。成功したら true */
  onSubmit: (request: DecisionRequest) => Promise<boolean>;
}

/**
 * 決定を記録するフォーム（仕様の持ち主の「決める」）。
 * 状況、決定の種類、決定の内容、理由、決めた人を入れて「決定を記録する」。どれかが空なら押せない。
 * 決めた人は、相談先の名前から選ぶ（1人なら最初から選んでおく。「ほかの人」で名前を書ける）。
 * @param props 初期値、決めた人の選択肢、説明、送る処理
 */
export function DecisionForm({
  initialSituation,
  names,
  note,
  busy,
  failed,
  onSubmit,
}: DecisionFormProps) {
  const { op: OP_TEXT, owner: OWNER_TEXT } = useMessages();
  const id = useId();
  const [situation, setSituation] = useState(initialSituation);
  const [kind, setKind] = useState<DecisionKind | "">("");
  const [decision, setDecision] = useState("");
  const [reason, setReason] = useState("");
  const [person, setPerson] = useState(names.length === 1 ? names[0] : "");
  const [otherName, setOtherName] = useState("");

  const decidedBy = person === OTHER_PERSON ? otherName.trim() : person;
  const canSubmit =
    !busy &&
    kind !== "" &&
    situation.trim() !== "" &&
    decision.trim() !== "" &&
    reason.trim() !== "" &&
    decidedBy !== "";

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    await onSubmit({
      decisionKind: kind,
      decision: decision.trim(),
      reason: reason.trim(),
      situation: situation.trim(),
      decidedBy,
    });
  };
  return (
    <form className="decision-form" onSubmit={(e) => void handleSubmit(e)}>
      <label htmlFor={`${id}-situation`}>{OWNER_TEXT.situation}</label>
      <p className="op-muted" id={`${id}-situation-hint`}>
        {OWNER_TEXT.situationHint}
      </p>
      <textarea
        id={`${id}-situation`}
        aria-describedby={`${id}-situation-hint`}
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={situation}
        onChange={(event) => setSituation(event.target.value)}
      />
      <label htmlFor={`${id}-kind`}>{OWNER_TEXT.kind}</label>
      <select
        id={`${id}-kind`}
        value={kind}
        onChange={(event) => setKind(event.target.value as DecisionKind | "")}
      >
        <option value="">{OWNER_TEXT.choose}</option>
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
      <select
        id={`${id}-by`}
        value={person}
        onChange={(event) => setPerson(event.target.value)}
      >
        {names.length !== 1 && <option value="">{OWNER_TEXT.choose}</option>}
        {names.map((name) => (
          <option key={name} value={name}>
            {name}
          </option>
        ))}
        <option value={OTHER_PERSON}>{OWNER_TEXT.decidedByOther}</option>
      </select>
      {person === OTHER_PERSON && (
        <>
          <label htmlFor={`${id}-by-name`}>{OWNER_TEXT.decidedByName}</label>
          <input
            id={`${id}-by-name`}
            type="text"
            maxLength={MAX_NAME_LENGTH}
            value={otherName}
            onChange={(event) => setOtherName(event.target.value)}
          />
        </>
      )}
      <div className="actions">
        <button type="submit" className="primary" disabled={!canSubmit}>
          {OWNER_TEXT.submit}
        </button>
      </div>
      <p className="op-muted">{note}</p>
      {failed && (
        <p className="op-muted" role="alert">
          {OP_TEXT.actionFailed}
        </p>
      )}
    </form>
  );
}
