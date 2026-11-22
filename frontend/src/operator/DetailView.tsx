import type {
  HandlingStatus,
  OperatorDetail,
  Placement,
  SystemOption,
} from "../api/types.ts";
import { MessageForm, MessageThread } from "../components/Exchange.tsx";
import { TracePanel } from "../components/TracePanel.tsx";
import { SOURCE_TYPE_LABELS } from "../text/labels.ts";
import {
  HANDLING_LABELS,
  OP_TEXT,
  formatFiledAt,
  opAnswerLabel,
  opUrgencyLabel,
} from "./labels.ts";

/** 対応状況の切り替えボタンの並び順。 */
const STATUS_ORDER: HandlingStatus[] = ["OPEN", "IN_PROGRESS", "DONE"];

/** DetailView の props。 */
interface DetailViewProps {
  detail: OperatorDetail;
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
  /** スキルの実行記録を最下部に出すか（`GET /api/config` の `trace`） */
  trace: boolean;
  /** 状況の切り替えや返信を送っている最中か */
  busy: boolean;
  /** 直近の操作が失敗したか */
  failed: boolean;
  /** 対応状況を切り替える。成功したら true */
  onStatus: (status: HandlingStatus) => Promise<boolean>;
  /** 本人に返信する。成功したら true */
  onReply: (body: string) => Promise<boolean>;
}

/**
 * 箇条書きの1区分。項目が無ければ何も出さない。
 * @param props 見出しと項目
 */
function List({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <>
      <h4>{title}</h4>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </>
  );
}

/**
 * 置き場所（システム、機能）の名前。AI が文章から置いたものには、そのことを添える。
 * @param placement 置き場所（決まっていなければ null）
 * @param unknown 決まっていないときの文言
 */
function placementLabel(placement: Placement | null, unknown: string): string {
  if (placement === null) return unknown;
  return placement.label + (placement.by === "AI" ? OP_TEXT.placedByAi : "");
}

/**
 * 1件の問い合わせの全内容。元の発話から調べた結果、出典の照合結果まで。
 * 上部に対応状況の切り替え、下部にやり取りと返信の入力欄、最下部にスキルの実行記録を置く。
 * @param props 問い合わせの全内容と、操作の受け口
 */
export function DetailView({
  detail,
  urgencies,
  trace,
  busy,
  failed,
  onStatus,
  onReply,
}: DetailViewProps) {
  const { structured, answer } = detail;
  const messages = detail.messages ?? [];
  return (
    <article className="op-article">
      <header className="op-article-head">
        <div className="op-badges">
          <span className="op-badge">
            {placementLabel(detail.type, OP_TEXT.typeUnknown)}
          </span>
          <span className="op-badge">
            {placementLabel(detail.system, OP_TEXT.systemUnknown)}
          </span>
          {detail.function !== null && (
            <span className="op-badge">
              {placementLabel(detail.function, "")}
            </span>
          )}
          {detail.urgency !== "NORMAL" && (
            <span className="op-badge warn strong-warn">
              {opUrgencyLabel(detail.urgency, urgencies)}
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
          <time dateTime={detail.filedAt}>{formatFiledAt(detail.filedAt)}</time>
          {" ・ "}
          {detail.askerName ?? detail.askerId}
        </p>
        <div
          className="op-status-switch"
          role="group"
          aria-label={OP_TEXT.statusSwitchLabel}
        >
          {STATUS_ORDER.map((status) => (
            <button
              key={status}
              type="button"
              aria-pressed={detail.handlingStatus === status}
              disabled={busy}
              onClick={() => {
                if (detail.handlingStatus !== status) void onStatus(status);
              }}
            >
              {HANDLING_LABELS[status]}
            </button>
          ))}
        </div>
      </header>

      {detail.utterance !== null && (
        <section>
          <h3>{OP_TEXT.utterance}</h3>
          <p className="op-pre">{detail.utterance}</p>
        </section>
      )}

      {structured !== null && (
        <section>
          <h3>{OP_TEXT.restatement}</h3>
          <p className="op-pre">{structured.summary}</p>
          <dl className="op-fields">
            <dt>対象</dt>
            <dd>{structured.targetSystem ?? "—"}</dd>
            <dt>やりたいこと</dt>
            <dd>{structured.goal}</dd>
            <dt>起きたこと</dt>
            <dd>{structured.whatHappened ?? "—"}</dd>
            <dt>期待した結果</dt>
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
                <span className="op-a">{opAnswerLabel(entry.answer)}</span>
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
              <li key={`${index}:${note}`}>{note}</li>
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
            <p className="op-pre">
              {answer.grounded ? answer.askerExplanation : OP_TEXT.notGrounded}
            </p>
            <List title={OP_TEXT.findings} items={answer.memo.findings} />
            {(answer.memo.unconfirmed ?? []).length > 0 && (
              <div className="op-unconfirmed">
                <List
                  title={OP_TEXT.unconfirmed}
                  items={answer.memo.unconfirmed ?? []}
                />
                <p className="op-muted">{OP_TEXT.unconfirmedNote}</p>
              </div>
            )}
            <List title={OP_TEXT.notFound} items={answer.memo.notFound} />
            <List title={OP_TEXT.nextChecks} items={answer.memo.nextChecks} />
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
                        [{SOURCE_TYPE_LABELS[c.sourceType]}] {c.repository} /{" "}
                        {c.path}:{c.startLine}-{c.endLine}
                        <span className="op-muted"> @{c.revision}</span>
                      </div>
                      <blockquote>{c.quote}</blockquote>
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
        <MessageThread messages={messages} mine="OPERATOR" />
        {messages.length === 0 && (
          <p className="op-muted">{OP_TEXT.noMessages}</p>
        )}
        <MessageForm
          label={OP_TEXT.replyLabel}
          submitLabel={OP_TEXT.replySubmit}
          busy={busy}
          onSubmit={onReply}
        />
        {failed && (
          <p className="op-muted" role="alert">
            {OP_TEXT.actionFailed}
          </p>
        )}
      </section>

      {trace && <TracePanel entries={detail.trace} />}
    </article>
  );
}
