import type { OperatorDetail } from "../api/types.ts";
import { SOURCE_TYPE_LABELS } from "../text/labels.ts";
import { OP_TEXT, formatFiledAt, opCategoryLabel } from "./labels.ts";

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
 * 1件の問い合わせの全内容。元の発話から調べた結果、出典の照合結果まで。
 * @param props 問い合わせの全内容
 */
export function DetailView({ detail }: { detail: OperatorDetail }) {
  const { structured, answer } = detail;
  return (
    <article className="op-article">
      <header className="op-article-head">
        <div className="op-badges">
          <span className="op-badge">{opCategoryLabel(detail.category)}</span>
          <span className="op-badge">
            {detail.system === null
              ? OP_TEXT.systemUnknown
              : detail.system.label +
                (detail.systemGuessed ? OP_TEXT.systemGuessed : "")}
          </span>
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
          {detail.askerId}
        </p>
      </header>

      <section>
        <h3>{OP_TEXT.utterance}</h3>
        <p className="op-pre">{detail.utterance}</p>
      </section>

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
            <dt>緊急度</dt>
            <dd>{structured.urgency}</dd>
          </dl>
        </section>
      )}

      {detail.followUps.length > 0 && (
        <section>
          <h3>{OP_TEXT.followUps}</h3>
          <ul className="op-qa">
            {detail.followUps.map((f) => (
              <li key={f.text}>
                <span className="op-q">{f.text}</span>
                <span className="op-a">
                  {f.answer ?? OP_TEXT.unknownAnswer}
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
            <p className="op-pre">
              {answer.grounded ? answer.askerExplanation : OP_TEXT.notGrounded}
            </p>
            <List title={OP_TEXT.findings} items={answer.memo.findings} />
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
    </article>
  );
}
