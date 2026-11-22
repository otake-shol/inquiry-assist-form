import { useId, useState, type FormEvent, type RefObject } from "react";
import type { Consultation, Precedent } from "../api/types.ts";
import { MAX_MESSAGE_LENGTH } from "../components/Exchange.tsx";
import { useMessages } from "../i18n/context.ts";
import { MAX_CONSULTANTS, MAX_NAME_LENGTH } from "./consultations.ts";
import { formatFiledAt } from "./labels.ts";

/** ConsultForm の props。 */
interface ConsultFormProps {
  /** 折りたたみを開いているか */
  open: boolean;
  onToggle: (open: boolean) => void;
  busy: boolean;
  /** 既定の相談先の名前（チェックを入れた状態で並べる） */
  defaultConsultants: string[];
  /** 相談したいことの入力欄（ドラッグで落としたときにフォーカスを移す） */
  questionRef: RefObject<HTMLTextAreaElement | null>;
  /** 相談する。成功したら true */
  onSubmit: (to: string[], question: string) => Promise<boolean>;
}

/**
 * 相談する（担当者の番のときだけ。折りたたみ）。相談先（既定の相談先はチェックを入れた状態。外せる。
 * 「ほかの人」の1行の入力欄で名前を足せる）と、相談したいことを書いて「仕様の持ち主に相談する」。
 * 相談先が1人もいない、または相談したいことが空のときは押せない。
 * @param props 開閉、既定の相談先、入力欄の参照、送る処理
 */
export function ConsultForm({
  open,
  onToggle,
  busy,
  defaultConsultants,
  questionRef,
  onSubmit,
}: ConsultFormProps) {
  const OP_TEXT = useMessages().op;
  const id = useId();
  // 外した既定の相談先の名前
  const [unchecked, setUnchecked] = useState<string[]>([]);
  const [other, setOther] = useState("");
  const [question, setQuestion] = useState("");
  const trimmedOther = other.trim();
  const targets = [
    ...defaultConsultants.filter((name) => !unchecked.includes(name)),
    ...(trimmedOther === "" || defaultConsultants.includes(trimmedOther)
      ? []
      : [trimmedOther]),
  ];
  const trimmedQuestion = question.trim();
  const canSubmit =
    !busy &&
    trimmedQuestion !== "" &&
    targets.length >= 1 &&
    targets.length <= MAX_CONSULTANTS;

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    if (await onSubmit(targets, trimmedQuestion)) {
      setQuestion("");
      setOther("");
    }
  };
  return (
    <details
      className="op-fold op-consult"
      open={open}
      onToggle={(event) => onToggle(event.currentTarget.open)}
    >
      <summary>{OP_TEXT.consultSummary}</summary>
      <form className="op-consult-form" onSubmit={(e) => void handleSubmit(e)}>
        <p className="op-muted">{OP_TEXT.consultHint}</p>
        <fieldset className="op-consult-targets">
          <legend>{OP_TEXT.consultTargets}</legend>
          {defaultConsultants.map((name) => (
            <label key={name} className="op-consult-target">
              <input
                type="checkbox"
                checked={!unchecked.includes(name)}
                onChange={(event) =>
                  setUnchecked((current) =>
                    event.target.checked
                      ? current.filter((n) => n !== name)
                      : [...current, name],
                  )
                }
              />
              {name}
            </label>
          ))}
          <label htmlFor={`${id}-other`}>{OP_TEXT.consultOther}</label>
          <input
            id={`${id}-other`}
            type="text"
            maxLength={MAX_NAME_LENGTH}
            value={other}
            onChange={(event) => setOther(event.target.value)}
          />
        </fieldset>
        <label htmlFor={`${id}-question`}>{OP_TEXT.consultQuestion}</label>
        <textarea
          id={`${id}-question`}
          ref={questionRef}
          rows={4}
          maxLength={MAX_MESSAGE_LENGTH}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
        />
        <div className="actions">
          <button type="submit" className="primary" disabled={!canSubmit}>
            {OP_TEXT.consultSubmit}
          </button>
        </div>
      </form>
    </details>
  );
}

/** ConsultationWaiting の props。 */
interface ConsultationWaitingProps {
  /** 決定を待っている相談（取れなければ null） */
  consultation: Consultation | null;
  busy: boolean;
  /** 相談を取り下げる。成功したら true */
  onCancel: () => Promise<boolean>;
}

/**
 * 相談している間（`awaiting: CONSULTATION`）の表示。「本人に送る」「見送る」の代わりに、
 * 待っていること、相談した文章、「相談を取り下げる」を出す。
 * @param props 相談と、取り下げの受け口
 */
export function ConsultationWaiting({
  consultation,
  busy,
  onCancel,
}: ConsultationWaitingProps) {
  const OP_TEXT = useMessages().op;
  return (
    <div className="op-consult-waiting">
      <p className="op-waiting" aria-live="polite">
        {consultation === null
          ? OP_TEXT.consultWaitingPlain
          : OP_TEXT.consultWaiting(OP_TEXT.nameList(consultation.to))}
      </p>
      {consultation !== null && (
        <p className="op-pre op-consult-question">{consultation.question}</p>
      )}
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void onCancel()}>
          {OP_TEXT.consultCancel}
        </button>
      </div>
    </div>
  );
}

/**
 * 仕様の持ち主の決定（先例）の1件。実線の枠で示し、AI の案（点線の枠）と見分ける。
 * 決定の文章は、書かれたときの言語のまま出す。
 * @param props 先例
 */
function DecisionCard({ decision }: { decision: Precedent }) {
  const { op: OP_TEXT, meta } = useMessages();
  return (
    <article className="op-decision">
      <h4>
        <span className="op-decision-kind">
          {OP_TEXT.decisionKinds[decision.decisionKind]}
        </span>
      </h4>
      <dl className="op-fields" lang={decision.language}>
        <dt lang={meta.code}>{OP_TEXT.decisionContent}</dt>
        <dd className="op-pre">{decision.decision}</dd>
        <dt lang={meta.code}>{OP_TEXT.decisionReason}</dt>
        <dd className="op-pre">{decision.reason}</dd>
        <dt lang={meta.code}>{OP_TEXT.decisionSituation}</dt>
        <dd className="op-pre">{decision.situation}</dd>
      </dl>
      <p className="op-muted">
        {decision.decidedBy}
        {meta.separator}
        <time dateTime={decision.decidedAt}>
          {formatFiledAt(decision.decidedAt, meta.locale)}
        </time>
      </p>
    </article>
  );
}

/**
 * 仕様の持ち主の決定の節（「AI の案」のすぐ下）。決定がなければ何も出さない。
 * 人が下した決定なので、AI の案とは違う、実線の枠で示す。
 * @param props 決定（先例）の一覧（古い順）
 */
export function DecisionsSection({ decisions }: { decisions: Precedent[] }) {
  const OP_TEXT = useMessages().op;
  if (decisions.length === 0) return null;
  return (
    <section className="op-decisions">
      <h3>{OP_TEXT.decisionTitle}</h3>
      {decisions.map((decision) => (
        <DecisionCard key={decision.id} decision={decision} />
      ))}
    </section>
  );
}
