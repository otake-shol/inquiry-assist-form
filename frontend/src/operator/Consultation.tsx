import { useId, useState, type FormEvent, type ReactNode } from "react";
import type {
  Consultation,
  OperatorDetail,
  Precedent,
  SystemOption,
} from "../api/types.ts";
import { MAX_MESSAGE_LENGTH } from "../components/Exchange.tsx";
import { NavLink } from "../components/Navigation.tsx";
import { useMessages } from "../i18n/context.ts";
import { precedentsPathOf } from "../path.ts";
import { MAX_CONSULTANTS, MAX_NAME_LENGTH } from "./consultations.ts";
import { formatFiledAt } from "./labels.ts";

/** ConsultForm の props。 */
interface ConsultFormProps {
  busy: boolean;
  /** 設定の相談先の名前（チェックを入れた状態で並べる） */
  consultants: string[];
  /** 相談する。成功したら true */
  onSubmit: (to: string[], question: string) => Promise<boolean>;
}

/**
 * 相談する（担当者の番のときだけ。折りたたみ）。相談先（設定の相談先はチェックを入れた状態。外せる。
 * 「ほかの人」の1行の入力欄で名前を足せる）と、相談したいことを書いて「仕様の持ち主に相談する」。
 * 相談先が1人もいない、または相談したいことが空のときは押せない。
 * @param props 相談先の一覧、送る処理
 */
export function ConsultForm({ busy, consultants, onSubmit }: ConsultFormProps) {
  const OP_TEXT = useMessages().op;
  const id = useId();
  // 外した設定の相談先の名前
  const [unchecked, setUnchecked] = useState<string[]>([]);
  const [other, setOther] = useState("");
  const [question, setQuestion] = useState("");
  const trimmedOther = other.trim();
  const targets = [
    ...consultants.filter((name) => !unchecked.includes(name)),
    ...(trimmedOther === "" || consultants.includes(trimmedOther)
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
    <details className="op-fold op-consult">
      <summary>{OP_TEXT.consultSummary}</summary>
      <form className="op-consult-form" onSubmit={(e) => void handleSubmit(e)}>
        <p className="op-muted">{OP_TEXT.consultHint}</p>
        <fieldset className="op-consult-targets">
          <legend>{OP_TEXT.consultTargets}</legend>
          {consultants.map((name) => (
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

/** DecisionCard の props。 */
interface DecisionCardProps {
  decision: Precedent;
  /** 置き場所（種類、システム、機能）の行を出すか（先例の一覧） */
  showPlace?: boolean;
  /** 先例の機能の名前（この問い合わせの機能と違うときだけ渡す） */
  functionName?: string | null;
  /** 先例の下に置くもの（操作、リンク） */
  children?: ReactNode;
}

/**
 * 仕様の持ち主の決定（先例）の1件。実線の枠で示し、AI の案（点線の枠）と見分ける。
 * 決定の文章は、書かれたときの言語のまま出す。
 * @param props 先例と、添えるもの
 */
export function DecisionCard({
  decision,
  showPlace = false,
  functionName = null,
  children,
}: DecisionCardProps) {
  const { op: OP_TEXT, meta } = useMessages();
  const place = [decision.type, decision.system, decision.function]
    .filter((option): option is SystemOption => option !== null)
    .map((option) => option.label)
    .join(meta.joiner);
  return (
    <article className="op-decision">
      <h4>
        <span className="op-decision-kind">
          {OP_TEXT.decisionKinds[decision.decisionKind]}
        </span>
      </h4>
      {showPlace && place !== "" && <p className="op-muted">{place}</p>}
      {functionName !== null && (
        <p className="op-muted">{OP_TEXT.precedentFunction(functionName)}</p>
      )}
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
      {children}
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

/** PrecedentsSection の props。 */
interface PrecedentsSectionProps {
  /** 先例を探す元になった問い合わせ（置き場所と、機能の比べ先） */
  detail: OperatorDetail;
}

/**
 * 同じ置き場所の先例の節（「調べた結果」のすぐ下、「AI の案」の上）。先例ごとに、決定の種類、内容、
 * 状況、理由、決めた人と日付。機能が違う先例には、その機能の名前を添える。確かめられていない先例には印を付ける。
 * なければ「まだありません」と示す。節の末尾に、この置き場所で絞った先例の一覧へのリンク。
 * @param props 問い合わせ
 */
export function PrecedentsSection({ detail }: PrecedentsSectionProps) {
  const OP_TEXT = useMessages().op;
  const precedents = detail.precedents ?? [];
  const link = precedentsPathOf({
    systemId: detail.system?.id,
    functionId: detail.function?.id,
    type: detail.type?.id,
  });
  return (
    <section className="op-decisions op-precedents">
      <h3>{OP_TEXT.precedentsTitle}</h3>
      {precedents.length === 0 ? (
        <p className="op-muted">{OP_TEXT.precedentsEmpty}</p>
      ) : (
        precedents.map((precedent) => (
          <DecisionCard
            key={precedent.id}
            decision={precedent}
            functionName={
              precedent.function !== null &&
              precedent.function.id !== detail.function?.id
                ? precedent.function.label
                : null
            }
          />
        ))
      )}
      <p>
        <NavLink to={link}>{OP_TEXT.precedentsLink}</NavLink>
      </p>
    </section>
  );
}
