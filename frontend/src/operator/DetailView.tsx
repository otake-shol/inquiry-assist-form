import { useEffect, useRef, useState } from "react";
import type { PlacementInput, ReplyKind } from "../api/client.ts";
import type {
  ConfigSystem,
  DeclineReasonKind,
  OperatorDetail,
  Phase,
  Placement,
  SystemOption,
  TypeOption,
} from "../api/types.ts";
import { MessageThread } from "../components/Exchange.tsx";
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
  onDecline,
}: DetailViewProps) {
  const dict = useMessages();
  const OP_TEXT = dict.op;
  const { structured, answer } = detail;
  const messages = detail.messages ?? [];
  const askerLang = detail.language;
  const ended = detail.ending !== null;
  const operatorsTurn = !ended && detail.turn === "OPERATOR";

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
            {OP_TEXT.askerLanguage(languageName(detail.language))}
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
        </section>
      )}

      {structured !== null && (
        <section>
          <h3>{OP_TEXT.restatement}</h3>
          <p className="op-pre" lang={askerLang}>
            {structured.summary}
          </p>
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

      <section className="op-exchange">
        <h3>{OP_TEXT.exchange}</h3>
        <MessageThread
          messages={messages}
          mine="OPERATOR"
          askerLanguage={askerLang}
        />
        {messages.length === 0 && (
          <p className="op-muted">{OP_TEXT.noMessages}</p>
        )}
        {operatorsTurn ? (
          <>
            <SendToAsker busy={busy} inputRef={sendRef} onSend={onReply} />
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

      {trace && <TracePanel entries={detail.trace} />}
    </article>
  );
}
