import { useEffect, useRef, useState } from "react";
import type { PlacementInput, ReplyKind } from "../api/client.ts";
import type {
  Advice,
  ConfigSystem,
  DeclineReasonKind,
  DraftResult,
  Investigation,
  OperatorDetail,
  Translation,
  Phase,
  Placement,
  SystemOption,
  TypeOption,
} from "../api/types.ts";
import { MessageThread, TranslationNote } from "../components/Exchange.tsx";
import { AnswerSteps } from "../components/Outcome.tsx";
import { TracePanel } from "../components/TracePanel.tsx";
import { useMessages } from "../i18n/context.ts";
import { languageName } from "../i18n/messages.ts";
import {
  DeclineForm,
  HistoryList,
  PhaseSwitch,
  PlacementForm,
  SendToAsker,
} from "./Controls.tsx";
import {
  formatFiledAt,
  formatMonthDay,
  opAnswerLabel,
  opUrgencyLabel,
  stateLine,
  waitingText,
} from "./labels.ts";
import type { Messages } from "../i18n/messages.ts";
import type { DropIntent } from "./state.ts";

/** カードをドラッグで落としたときの、入力欄へのフォーカスの要求。 */
export interface FocusRequest {
  /** 問い合わせの id */
  id: string;
  /** 「本人に送る」か「見送る」の入力欄 */
  target: DropIntent;
  /** 同じ要求を続けて受けても、もう一度フォーカスを移せるようにする通し番号 */
  token: number;
}

/** DetailView の props。 */
interface DetailViewProps {
  detail: OperatorDetail;
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
  /** 設定の種類（置き直しの選択肢。取得前は空） */
  types: TypeOption[];
  /** 設定のシステム（置き直しの選択肢。取得前は空） */
  systems: ConfigSystem[];
  /** スキルの実行記録を最下部に出すか（`GET /api/config` の `trace`） */
  trace: boolean;
  /** 操作を送っている最中か */
  busy: boolean;
  /** 直近の操作が失敗したか */
  failed: boolean;
  /** ドラッグで落とされたときの、入力欄へのフォーカスの要求 */
  focusRequest: FocusRequest | null;
  /** フォーカスを移し終えた（要求を消す） */
  onFocusHandled: () => void;
  /** 区間を切り替える。成功したら true */
  onPhase: (phase: Phase) => Promise<boolean>;
  /** 置き直す。成功したら true */
  onPlacement: (placement: PlacementInput) => Promise<boolean>;
  /** 本人に聞き返す、または結果を伝える。成功したら true */
  onReply: (kind: ReplyKind, body: string) => Promise<boolean>;
  /** AI の案の文章の言語（既定の言語。`GET /api/config` の `languages` の先頭） */
  adviceLanguage?: string;
  /** 担当者に添える訳（出している間だけ。無ければ null） */
  translation: Translation | null;
  /** 訳を取りにいっている最中か */
  translating: boolean;
  /** 訳を見る／隠す */
  onToggleTranslation: () => void;
  /** 返信の下書きを作らせる。失敗したら null */
  onDraft: (kind: ReplyKind, note: string) => Promise<DraftResult | null>;
  /** AI に調べさせる（調べ直す）。成功したら true */
  onInvestigate: () => Promise<boolean>;
  /** 見送りとして終える。成功したら true */
  onDecline: (kind: DeclineReasonKind, reason: string) => Promise<boolean>;
}

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
  const { status, judgment, procedure } = advice;
  const hasSuggestion = judgment !== null || procedure !== null;
  if (status === "NOT_RUN" || (status === "DONE" && !hasSuggestion)) {
    return null;
  }
  const basedOn = (facts: string[]) => (
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
          {basedOn(judgment.basedOn)}
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
          {basedOn(procedure.basedOn)}
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

/**
 * 1件の問い合わせの全内容。元の発話から調べた結果、出典の照合結果まで。
 * 上部にいまの状態と区間の切り替え、下部にやり取りと、本人に送る・見送る操作、状態の履歴、
 * 最下部にスキルの実行記録を置く。担当者の番でないときは、操作の代わりに待っていることを示す。
 * @param props 問い合わせの全内容と、操作の受け口
 */
export function DetailView({
  detail,
  urgencies,
  types,
  systems,
  trace,
  busy,
  failed,
  focusRequest,
  onFocusHandled,
  onPhase,
  onPlacement,
  onReply,
  adviceLanguage,
  translation,
  translating,
  onToggleTranslation,
  onDraft,
  onInvestigate,
  onDecline,
}: DetailViewProps) {
  const dict = useMessages();
  const OP_TEXT = dict.op;
  const { structured, answer } = detail;
  const messages = detail.messages ?? [];
  const askerLang = detail.language;
  /** 訳があれば、`key` の文章のすぐ下に添える（対応する文章が画面にない `key` は、呼ばれないので無視される） */
  const noteOf = (key: string) =>
    translation === null ? null : (
      <TranslationNote
        text={translation.items.find((item) => item.key === key)?.translated}
        language={translation.to}
      />
    );
  const ended = detail.ending !== null;
  const operatorsTurn = !ended && detail.turn === "OPERATOR";
  // 調べ直せるのは、終わっていない、言い直しがある、いま調べていない件
  const canInvestigate =
    !ended && structured !== null && detail.investigation.status !== "RUNNING";

  // ドラッグで落とされたら、対応する入力欄にフォーカスを移す（見送るは、折りたたみを開いてから）
  const sendRef = useRef<HTMLTextAreaElement | null>(null);
  const declineRef = useRef<HTMLTextAreaElement | null>(null);
  const [declineOpen, setDeclineOpen] = useState(false);
  const target =
    focusRequest !== null && focusRequest.id === detail.id
      ? focusRequest.target
      : null;
  const token = focusRequest?.token;
  // 「見送る」への要求が来たら、描画のうちに折りたたみを開く（要求ごとに1回）
  const [openedFor, setOpenedFor] = useState<number | undefined>(undefined);
  if (target === "decline" && operatorsTurn && token !== openedFor) {
    setOpenedFor(token);
    setDeclineOpen(true);
  }
  useEffect(() => {
    if (target === null) return;
    if (!operatorsTurn) {
      onFocusHandled();
      return;
    }
    if (target === "send") sendRef.current?.focus();
    else declineRef.current?.focus();
    onFocusHandled();
  }, [target, token, operatorsTurn, onFocusHandled]);
  return (
    <article className="op-article">
      <header className="op-article-head">
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
        <p className="op-state" aria-live="polite">
          <span className="op-state-label">{OP_TEXT.stateLabel}</span>
          <strong>{stateLine(detail, dict)}</strong>
        </p>
        {operatorsTurn && detail.handoverReason !== null && (
          <p className="op-handover-reason">
            {OP_TEXT.handoverReasonLabel(
              OP_TEXT.handoverReasons[detail.handoverReason],
            )}
          </p>
        )}
        {detail.noReply !== null && (
          <p className="op-no-reply">
            {detail.noReply.remindedAt !== null &&
            detail.noReply.closesAt !== null
              ? (detail.noReply.endsAs === "WITHDRAWN"
                  ? OP_TEXT.noReplyRemindedWithdrawn
                  : OP_TEXT.noReplyReminded)(
                  formatMonthDay(detail.noReply.remindedAt, dict.meta.locale),
                  formatMonthDay(detail.noReply.closesAt, dict.meta.locale),
                )
              : OP_TEXT.noReplyPlanned(
                  formatMonthDay(detail.noReply.remindAt, dict.meta.locale),
                )}
          </p>
        )}
        {detail.language !== dict.meta.code && (
          <p className="op-asker-language">
            {OP_TEXT.askerLanguage(languageName(detail.language))}{" "}
            <button
              type="button"
              className="small"
              disabled={translating}
              onClick={onToggleTranslation}
            >
              {translation === null
                ? OP_TEXT.translationShow
                : OP_TEXT.translationHide}
            </button>
          </p>
        )}
        <PhaseSwitch
          phase={detail.phase}
          enabled={operatorsTurn}
          busy={busy}
          onChange={(phase) => void onPhase(phase)}
        />
        {!ended && (
          <PlacementForm
            key={`${detail.type?.id}:${detail.system?.id}:${detail.function?.id}`}
            detail={detail}
            types={types}
            systems={systems}
            busy={busy}
            onSubmit={onPlacement}
          />
        )}
      </header>

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
        {canInvestigate && (
          <div className="op-investigate">
            <button
              type="button"
              disabled={busy}
              onClick={() => void onInvestigate()}
            >
              {detail.investigation.status === "NOT_RUN"
                ? OP_TEXT.investigationStart
                : OP_TEXT.investigationRetry}
            </button>
            <p className="op-muted">{OP_TEXT.investigationHint}</p>
          </div>
        )}
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

      <AdviceSection
        advice={detail.advice}
        adviceLanguage={adviceLanguage}
        factsLanguage={askerLang}
        op={OP_TEXT}
      />

      <section className="op-exchange">
        <h3>{OP_TEXT.exchange}</h3>
        <MessageThread
          messages={messages}
          mine="OPERATOR"
          askerLanguage={askerLang}
          translation={translation}
        />
        {messages.length === 0 && (
          <p className="op-muted">{OP_TEXT.noMessages}</p>
        )}
        {operatorsTurn ? (
          <>
            <SendToAsker
              busy={busy}
              inputRef={sendRef}
              onSend={onReply}
              onDraft={onDraft}
            />
            <DeclineForm
              open={declineOpen}
              onToggle={setDeclineOpen}
              busy={busy}
              reasonRef={declineRef}
              onSubmit={onDecline}
            />
          </>
        ) : (
          <p className="op-waiting">{waitingText(detail, OP_TEXT)}</p>
        )}
        {failed && (
          <p className="op-muted" role="alert">
            {OP_TEXT.actionFailed}
          </p>
        )}
      </section>

      <HistoryList history={detail.history} />

      {trace && <TracePanel entries={detail.trace} operator />}
    </article>
  );
}
