import type { ReactNode } from "react";
import type {
  Advice,
  Investigation,
  OperatorDetail,
  Placement,
  PrecedentRef,
  SystemOption,
} from "../api/types.ts";
import { MessageThread } from "../components/Exchange.tsx";
import { AnswerSteps } from "../components/Outcome.tsx";
import { useMessages } from "../i18n/context.ts";
import type { Messages } from "../i18n/messages.ts";
import { PrecedentsSection } from "./Consultation.tsx";
import { formatFiledAt, opAnswerLabel, opUrgencyLabel } from "./labels.ts";

/**
 * 担当者の詳細のパネルと、仕様の持ち主の画面が共有する、読み取りだけの部品。
 * 材料（置き場所、本人が書いた文章、入口の質問と答え、言い直し、調べた結果、AI の案、やり取り）を、
 * 操作のボタンなしで描く。操作は、使う側が差し込み口（`resultAction`、`children`）に渡す。
 */

/**
 * 箇条書きの1区分。項目が無ければ何も出さない。
 * @param props 見出しと項目
 */
function List({
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

/**
 * AI の案（判断の案、手順の案）。「調べた結果」のすぐ下に出す。事実と見分けがつくように、枠を点線にし、
 * 見出しに「案」の印をつける。作っている最中と失敗は1行で示し、作っていない件や案が1つもない件は節を出さない。
 * 案の文章は書かれたときの言語（既定の言語）のまま出す。基づく事実は、調べた結果の事実と同じ言語。
 * @param props 案と、案の文章の言語、事実の言語
 */
function AdviceSection({
  advice,
  adviceLanguage,
  factsLanguage,
  op,
}: {
  advice: Advice;
  adviceLanguage?: string;
  factsLanguage?: string;
  op: Messages["op"];
}) {
  const { meta } = useMessages();
  const { status, judgment, procedure } = advice;
  const hasSuggestion = judgment !== null || procedure !== null;
  if (status === "NOT_RUN" || (status === "DONE" && !hasSuggestion)) {
    return null;
  }
  const basedOn = (facts: string[], precedents: PrecedentRef[] = []) => (
    <>
      {facts.length > 0 && (
        <>
          <h5>{op.adviceBasedOn}</h5>
          <ul>
            {facts.map((fact) => (
              <li key={fact} lang={factsLanguage}>
                {fact}
              </li>
            ))}
          </ul>
        </>
      )}
      {precedents.length > 0 && (
        <>
          <h5>{op.adviceBasedOnPrecedents}</h5>
          <ul className="op-based-precedents">
            {precedents.map((precedent) => (
              <li key={precedent.id}>
                <span>{precedent.decision}</span>
                <span className="op-muted">
                  {meta.separator}
                  {precedent.decidedBy}
                  {meta.separator}
                  <time dateTime={precedent.decidedAt}>
                    {formatFiledAt(precedent.decidedAt, meta.locale)}
                  </time>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
  return (
    <section className="op-advice" aria-live="polite">
      <h3>
        <span className="op-advice-mark" aria-hidden="true">
          {op.adviceMark}
        </span>
        {op.adviceTitle}
      </h3>
      <p className="op-muted">{op.adviceIntro}</p>
      {status === "RUNNING" && <p>{op.adviceRunning}</p>}
      {status === "FAILED" && <p>{op.adviceFailed}</p>}
      {status === "DONE" && judgment !== null && (
        <div className="op-advice-part">
          <h4>{op.judgmentTitle}</h4>
          <p className="op-pre" lang={adviceLanguage}>
            {judgment.text}
          </p>
          <p>
            {judgment.needsConsultation ? (
              <>
                {op.consultationNeeded}:{" "}
                <span lang={adviceLanguage}>{judgment.consultationReason}</span>
              </>
            ) : (
              op.consultationNotNeeded
            )}
          </p>
          {basedOn(judgment.basedOn, judgment.basedOnPrecedents)}
        </div>
      )}
      {status === "DONE" && procedure !== null && (
        <div className="op-advice-part">
          <h4>{op.procedureTitle}</h4>
          <ol className="op-steps" lang={adviceLanguage}>
            {procedure.steps.map((step, index) => (
              <li key={`${index}:${step.action}`}>
                <dl>
                  <dt>{op.stepTarget}</dt>
                  <dd>{step.target}</dd>
                  <dt>{op.stepAction}</dt>
                  <dd>{step.action}</dd>
                  <dt>{op.stepCheck}</dt>
                  <dd>{step.check}</dd>
                </dl>
              </li>
            ))}
          </ol>
          {procedure.rollback !== null && (
            <>
              <h5>{op.adviceRollback}</h5>
              <p lang={adviceLanguage}>{procedure.rollback}</p>
            </>
          )}
          {basedOn(procedure.basedOn, procedure.basedOnPrecedents)}
          <p className="op-muted">{op.adviceStaffCarryOut}</p>
        </div>
      )}
    </section>
  );
}

/**
 * 調査の状況の1行。結果を本人に見せたかどうか（`DONE`）まで含めて示す。
 * @param investigation 調査の状況
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
function investigationLine(
  investigation: Investigation,
  op: Messages["op"],
): string {
  const texts = op.investigationStatus;
  if (investigation.status === "DONE") {
    return investigation.audience === "OPERATOR"
      ? texts.DONE_OPERATOR
      : texts.DONE_ASKER;
  }
  return texts[investigation.status];
}

/**
 * 置き場所（システム、機能）の名前。AI が文章から置いたものには、そのことを添える。
 * @param placement 置き場所（決まっていなければ null）
 * @param unknown 決まっていないときの文言
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
function placementLabel(
  placement: Placement | null,
  unknown: string,
  op: Messages["op"],
): string {
  if (placement === null) return unknown;
  const mark =
    placement.by === "AI"
      ? op.placedByAi
      : placement.by === "OPERATOR"
        ? op.placedByOperator
        : "";
  return placement.label + mark;
}

/** PlacementBadges の props。 */
interface PlacementBadgesProps {
  detail: OperatorDetail;
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
}

/**
 * 置き場所（種類、システム、機能）、緊急度、Slack の状態の印と、送られた日時・投稿者。
 * @param props 詳細と、緊急度の選択肢
 */
export function PlacementBadges({ detail, urgencies }: PlacementBadgesProps) {
  const dict = useMessages();
  const OP_TEXT = dict.op;
  return (
    <>
      <div className="op-badges">
        <span className="op-badge">
          {placementLabel(detail.type, OP_TEXT.typeUnknown, OP_TEXT)}
        </span>
        <span className="op-badge">
          {placementLabel(detail.system, OP_TEXT.systemUnknown, OP_TEXT)}
        </span>
        {detail.function !== null && (
          <span className="op-badge">
            {placementLabel(detail.function, "", OP_TEXT)}
          </span>
        )}
        {detail.urgency !== "NORMAL" && (
          <span className="op-badge warn strong-warn">
            {opUrgencyLabel(detail.urgency, urgencies, OP_TEXT)}
          </span>
        )}
        {detail.sentImmediately && (
          <span className="op-badge strong">{OP_TEXT.sentImmediately}</span>
        )}
        {detail.slackStatus === "POSTED" && (
          <span className="op-badge quiet">{OP_TEXT.slackOk}</span>
        )}
        {detail.slackStatus === "FAILED" && (
          <span className="op-badge warn strong-warn">
            {OP_TEXT.slackFailed}
          </span>
        )}
      </div>
      <p className="op-meta">
        <time dateTime={detail.filedAt}>
          {formatFiledAt(detail.filedAt, dict.meta.locale)}
        </time>
        {dict.meta.separator}
        {detail.askerName ?? detail.askerId}
      </p>
    </>
  );
}

/** MaterialSections の props。 */
interface MaterialSectionsProps {
  detail: OperatorDetail;
  /** AI の案の文章の言語（既定の言語。`GET /api/config` の `languages` の先頭） */
  adviceLanguage?: string;
  /** 訳があれば、`key` の文章のすぐ下に添える（担当者の画面だけ） */
  noteOf?: (key: string) => ReactNode;
  /** 「調べた結果」の、調査の状況の下に差し込む操作（担当者の画面だけ。「調べ直す」など） */
  resultAction?: ReactNode;
}

/**
 * 材料の節（本人が書いた文章、言い直し、入口の質問と答え、作り直しの一言、調べた結果、AI の案）。
 * 読み取り専用。
 * @param props 詳細と、差し込み口
 */
export function MaterialSections({
  detail,
  adviceLanguage,
  noteOf = () => null,
  resultAction = null,
}: MaterialSectionsProps) {
  const dict = useMessages();
  const OP_TEXT = dict.op;
  const { structured, answer } = detail;
  const askerLang = detail.language;
  return (
    <>
      {detail.utterance !== null && (
        <section>
          <h3>{OP_TEXT.utterance}</h3>
          <p className="op-pre" lang={askerLang}>
            {detail.utterance}
          </p>
          {noteOf("utterance")}
        </section>
      )}

      {structured !== null && (
        <section>
          <h3>{OP_TEXT.restatement}</h3>
          <p className="op-pre" lang={askerLang}>
            {structured.summary}
          </p>
          {noteOf("structured.summary")}
          <dl className="op-fields">
            <dt>{OP_TEXT.fieldTarget}</dt>
            <dd>{structured.targetSystem ?? "—"}</dd>
            <dt>{OP_TEXT.fieldGoal}</dt>
            <dd>{structured.goal}</dd>
            <dt>{OP_TEXT.fieldWhatHappened}</dt>
            <dd>{structured.whatHappened ?? "—"}</dd>
            <dt>{OP_TEXT.fieldExpected}</dt>
            <dd>{structured.expectedResult ?? "—"}</dd>
          </dl>
        </section>
      )}

      {detail.intake.length > 0 && (
        <section>
          <h3>{OP_TEXT.intake}</h3>
          <ul className="op-qa">
            {detail.intake.map((entry) => (
              <li key={entry.questionId}>
                <span className="op-q">{entry.text}</span>
                <span
                  className="op-a"
                  lang={entry.answer.kind === "TEXT" ? askerLang : undefined}
                >
                  {opAnswerLabel(entry.answer, OP_TEXT)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {detail.revisionNotes.length > 0 && (
        <section>
          <h3>{OP_TEXT.revisionNotes}</h3>
          <ul>
            {detail.revisionNotes.map((note, index) => (
              <li key={`${index}:${note}`} lang={askerLang}>
                {note}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <h3>{OP_TEXT.result}</h3>
        <p className="op-investigation" aria-live="polite">
          {investigationLine(detail.investigation, OP_TEXT)}
        </p>
        {detail.investigation.unconfirmedQuestion && (
          <p className="op-muted">{OP_TEXT.investigationUnconfirmed}</p>
        )}
        {resultAction}
        {answer === null ? (
          <p className="op-muted">{OP_TEXT.noResult}</p>
        ) : (
          <>
            {answer.grounded &&
              (answer.answered ?? answer.grounded) === false && (
                <p>
                  <span className="op-badge warn">{OP_TEXT.offTopic}</span>
                </p>
              )}
            <p
              className="op-pre"
              lang={answer.grounded ? askerLang : undefined}
            >
              {answer.grounded ? answer.askerExplanation : OP_TEXT.notGrounded}
            </p>
            {answer.grounded && noteOf("answer.askerExplanation")}
            <AnswerSteps steps={answer.steps} language={askerLang} level={4} />
            <List
              title={OP_TEXT.findings}
              items={answer.memo.findings}
              language={askerLang}
            />
            {(answer.memo.unconfirmed ?? []).length > 0 && (
              <div className="op-unconfirmed">
                <List
                  title={OP_TEXT.unconfirmed}
                  items={answer.memo.unconfirmed ?? []}
                  language={askerLang}
                />
                <p className="op-muted">{OP_TEXT.unconfirmedNote}</p>
              </div>
            )}
            <List
              title={OP_TEXT.notFound}
              items={answer.memo.notFound}
              language={askerLang}
            />
            <List
              title={OP_TEXT.nextChecks}
              items={answer.memo.nextChecks}
              language={askerLang}
            />
            {answer.citations.length > 0 && (
              <>
                <h4>{OP_TEXT.citations}</h4>
                <ul className="op-citations">
                  {answer.citations.map((c) => (
                    <li
                      key={`${c.repository}:${c.path}:${c.startLine}`}
                      className={c.verified ? "verified" : "unverified"}
                    >
                      <div className="op-cite-head">
                        <span
                          className={`op-badge ${c.verified ? "" : "warn"}`}
                        >
                          {c.verified ? OP_TEXT.verified : OP_TEXT.unverified}
                        </span>
                        [{dict.sources[c.sourceType]}] {c.repository} / {c.path}
                        :{c.startLine}-{c.endLine}
                        <span className="op-muted"> @{c.revision}</span>
                      </div>
                      <blockquote lang="ja">{c.quote}</blockquote>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </>
        )}
      </section>

      <PrecedentsSection detail={detail} />

      <AdviceSection
        advice={detail.advice}
        adviceLanguage={adviceLanguage}
        factsLanguage={askerLang}
        op={OP_TEXT}
      />
    </>
  );
}

/** ExchangeSection の props。 */
interface ExchangeSectionProps {
  detail: OperatorDetail;
  /** 訳（担当者の画面だけ） */
  translation?: Parameters<typeof MessageThread>[0]["translation"];
  /** やり取りの下に置く操作（担当者の画面だけ） */
  children?: ReactNode;
}

/**
 * やり取りの節。発言を古い順に並べ、なければ「やり取りはまだありません」と示す。
 * @param props 詳細と、訳、下に置く操作
 */
export function ExchangeSection({
  detail,
  translation = null,
  children,
}: ExchangeSectionProps) {
  const OP_TEXT = useMessages().op;
  const messages = detail.messages ?? [];
  return (
    <section className="op-exchange">
      <h3>{OP_TEXT.exchange}</h3>
      <MessageThread
        messages={messages}
        mine="OPERATOR"
        askerLanguage={detail.language}
        translation={translation}
      />
      {messages.length === 0 && (
        <p className="op-muted">{OP_TEXT.noMessages}</p>
      )}
      {children}
    </section>
  );
}
