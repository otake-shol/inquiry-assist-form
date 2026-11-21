import { useState, type FormEvent } from "react";
import type {
  AnswerInput,
  InquiryCategory,
  InquiryView,
  Question,
} from "../api/types.ts";
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  CATEGORY_TREATMENT,
} from "../text/labels.ts";

/** 「わからない」の表示名。回答としては null で送る。 */
const UNKNOWN_LABEL = "わからない";

/** 1問ぶんの入力状態。 */
interface QuestionInput {
  text: string;
  unknown: boolean;
}

/** 追加質問画面の props。 */
interface TriageStepProps {
  view: InquiryView;
  busy: boolean;
  onChangeCategory: (category: InquiryCategory) => void;
  onSubmit: (answers: AnswerInput[]) => void;
}

/**
 * 1問ぶんが回答済み（入力あり、または「わからない」）か。
 * @param input 入力状態
 */
function isAnswered(input: QuestionInput | undefined): boolean {
  return input !== undefined && (input.unknown || input.text.trim() !== "");
}

/** 質問フォームの props。 */
interface QuestionFormProps {
  questions: Question[];
  busy: boolean;
  onSubmit: (answers: AnswerInput[]) => void;
}

/**
 * 追加質問のフォーム。選択肢つきはラジオ、options が空なら自由記述。
 * どの質問にも「わからない」を用意する。
 * @param props 質問と送信の受け口
 */
function QuestionForm({ questions, busy, onSubmit }: QuestionFormProps) {
  const [inputs, setInputs] = useState<Record<string, QuestionInput>>({});

  const update = (id: string, input: QuestionInput) =>
    setInputs((current) => ({ ...current, [id]: input }));

  const complete = questions.every((q) => isAnswered(inputs[q.id]));

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit(
      questions.map((q) => {
        const input = inputs[q.id];
        return {
          questionId: q.id,
          answer: input.unknown ? null : input.text.trim(),
        };
      }),
    );
  };

  return (
    <form onSubmit={handleSubmit}>
      {questions.length === 0 && <p>追加の質問はありません。</p>}
      {questions.map((q) => {
        const input = inputs[q.id] ?? { text: "", unknown: false };
        const textId = `question-${q.id}`;
        return (
          <fieldset key={q.id}>
            <legend>{q.text}</legend>
            {q.options.length > 0 ? (
              <>
                {q.options.map((option) => (
                  <label key={option} className="choice">
                    <input
                      type="radio"
                      name={textId}
                      checked={!input.unknown && input.text === option}
                      onChange={() =>
                        update(q.id, { text: option, unknown: false })
                      }
                    />
                    {option}
                  </label>
                ))}
                <label className="choice">
                  <input
                    type="radio"
                    name={textId}
                    checked={input.unknown}
                    onChange={() => update(q.id, { text: "", unknown: true })}
                  />
                  {UNKNOWN_LABEL}
                </label>
              </>
            ) : (
              <>
                <label htmlFor={textId} className="visually-hidden">
                  {q.text}（自由記述）
                </label>
                <textarea
                  id={textId}
                  rows={3}
                  value={input.text}
                  disabled={input.unknown}
                  onChange={(event) =>
                    update(q.id, { text: event.target.value, unknown: false })
                  }
                />
                <label className="choice">
                  <input
                    type="checkbox"
                    checked={input.unknown}
                    onChange={(event) =>
                      update(q.id, {
                        text: event.target.checked ? "" : input.text,
                        unknown: event.target.checked,
                      })
                    }
                  />
                  {UNKNOWN_LABEL}
                </label>
              </>
            )}
          </fieldset>
        );
      })}
      <div className="actions">
        <button type="submit" disabled={busy || !complete}>
          {questions.length === 0 ? "次へ進む" : "回答して次へ進む"}
        </button>
      </div>
    </form>
  );
}

/**
 * 種別の確認と追加質問の画面。種別を変えると質問が差し替わる。
 * @param props 問い合わせと操作の受け口
 */
export function TriageStep({
  view,
  busy,
  onChangeCategory,
  onSubmit,
}: TriageStepProps) {
  const formKey = `${view.category}:${view.questions.map((q) => q.id).join(",")}`;
  return (
    <div>
      <h2>内容を確認します</h2>
      <p className="treatment">
        AIの判定:{" "}
        <strong>
          {view.category === null
            ? "種別を判定できませんでした"
            : CATEGORY_TREATMENT[view.category]}
        </strong>
        {view.categoryByAi !== null && view.category !== view.categoryByAi && (
          <span>
            （AIの判定は「{CATEGORY_LABELS[view.categoryByAi]}」でした）
          </span>
        )}
      </p>
      <fieldset>
        <legend>種別が違う場合は選び直してください</legend>
        {CATEGORY_ORDER.map((category) => (
          <label key={category} className="choice">
            <input
              type="radio"
              name="category"
              checked={view.category === category}
              disabled={busy}
              onChange={() => onChangeCategory(category)}
            />
            {CATEGORY_LABELS[category]}
          </label>
        ))}
      </fieldset>
      <QuestionForm
        key={formKey}
        questions={view.questions}
        busy={busy}
        onSubmit={onSubmit}
      />
    </div>
  );
}
