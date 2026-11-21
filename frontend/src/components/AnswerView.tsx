import type { ZeroAnswer } from "../api/types.ts";
import { SOURCE_TYPE_LABELS } from "../text/labels.ts";

/** 0次回答表示の props。 */
interface AnswerViewProps {
  answer: ZeroAnswer;
}

/**
 * メモの1区分（見つかったこと・見つからなかったこと・次に確認すること）。
 * @param props 見出しと項目
 */
function MemoList({ title, items }: { title: string; items: string[] }) {
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
 * 0次回答の表示。根拠ありは本人向けの説明を主に、出典と担当者向けメモを折りたたみで出す。
 * 根拠なしは「特定できませんでした」と示し、メモを折りたたみで出す。
 * @param props 0次回答
 */
export function AnswerView({ answer }: AnswerViewProps) {
  return (
    <section className="answer" aria-label="0次回答">
      {answer.grounded ? (
        <p className="answer-body">{answer.askerExplanation}</p>
      ) : (
        <p className="answer-body">一次情報からは答えを特定できませんでした</p>
      )}
      {answer.grounded && answer.citations.length > 0 && (
        <details>
          <summary>出典（{answer.citations.length}件）</summary>
          <ul className="citations">
            {answer.citations.map((c) => (
              <li key={`${c.repository}:${c.path}:${c.startLine}`}>
                <div>
                  [{SOURCE_TYPE_LABELS[c.sourceType]}] {c.repository} / {c.path}
                  :{c.startLine}-{c.endLine}
                </div>
                <blockquote>{c.quote}</blockquote>
              </li>
            ))}
          </ul>
        </details>
      )}
      <details>
        <summary>技術的な詳細（担当者向けメモ）</summary>
        <MemoList title="分かったこと" items={answer.memo.findings} />
        <MemoList title="見つからなかったこと" items={answer.memo.notFound} />
        <MemoList title="次に確認すること" items={answer.memo.nextChecks} />
      </details>
    </section>
  );
}
