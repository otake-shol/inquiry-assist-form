import { useState, type FormEvent } from "react";
import type { InquiryCategory, Question } from "../api/types.ts";
import { MAX_UTTERANCE_LENGTH } from "../flow/page.ts";
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  TEXT,
  questionsIntroText,
  receiptText,
} from "../text/labels.ts";

/** 入力欄の props。 */
interface InputBoxProps {
  draft: string;
  busy: boolean;
  onChange: (draft: string) => void;
  onSubmit: () => void;
}

/**
 * 最初の自由記述の入力欄。分類も件名も求めない。
 * @param props 入力値と送信の受け口
 */
export function InputBox({ draft, busy, onChange, onSubmit }: InputBoxProps) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };
  return (
    <form onSubmit={handleSubmit} className="input-box">
      <label htmlFor="utterance">{TEXT.inputLabel}</label>
      <textarea
        id="utterance"
        data-autofocus
        rows={7}
        maxLength={MAX_UTTERANCE_LENGTH}
        value={draft}
        onChange={(event) => onChange(event.target.value)}
      />
      <div className="actions">
        <button
          type="submit"
          className="primary"
          disabled={busy || draft.trim() === ""}
        >
          {TEXT.send}
        </button>
      </div>
    </form>
  );
}

/**
 * 本人が書いた発話。画面側の言葉と区別できる形で残す。
 * @param props 発話
 */
export function UtteranceRow({ text }: { text: string }) {
  return (
    <div className="utterance">
      <p>{text}</p>
    </div>
  );
}

/** 受け取りの1行の props。 */
interface ReceiptProps {
  category: InquiryCategory | null;
  /** 「変更」を出せるか（追加質問の段階で、受け取った内容があるとき） */
  canChange: boolean;
  open: boolean;
  busy: boolean;
  onToggle: () => void;
  onSelect: (category: InquiryCategory) => void;
}

/**
 * 「〜として受け取りました（変更）」の1行。「変更」を押したときだけ選択肢を出す。
 * @param props 受け取った内容と変更の受け口
 */
export function Receipt({
  category,
  canChange,
  open,
  busy,
  onToggle,
  onSelect,
}: ReceiptProps) {
  return (
    <div className="system">
      <p className="receipt">
        {receiptText(category)}
        {canChange && (
          <>
            {"　"}
            <button
              type="button"
              className="link"
              aria-expanded={open}
              disabled={busy}
              onClick={onToggle}
            >
              {TEXT.change}
            </button>
          </>
        )}
      </p>
      {canChange && open && (
        <div role="group" aria-label={TEXT.changeLegend} className="choices">
          {CATEGORY_ORDER.map((candidate) => (
            <button
              key={candidate}
              type="button"
              className="choice"
              aria-pressed={candidate === category}
              disabled={busy}
              onClick={() => onSelect(candidate)}
            >
              {CATEGORY_LABELS[candidate]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * 追加質問の最初に数を伝える。
 * @param props 質問の数
 */
export function QuestionsIntro({ count }: { count: number }) {
  return (
    <p className="system" role="status">
      {questionsIntroText(count)}
    </p>
  );
}

/** 答えた質問の1行の props。 */
interface AnsweredRowProps {
  question: Question;
  answer: string | null;
  /** 押して答え直せるか */
  interactive: boolean;
  busy: boolean;
  onRewind: () => void;
}

/**
 * 「質問 → 回答」の1行。追加質問の段階では、押すと答え直せる。
 * @param props 質問と回答
 */
export function AnsweredRow({
  question,
  answer,
  interactive,
  busy,
  onRewind,
}: AnsweredRowProps) {
  const body = (
    <>
      <span className="answered-q">{question.text}</span>
      <span className="answered-arrow" aria-hidden="true">
        →
      </span>
      <span className="answered-a">{answer ?? TEXT.unknown}</span>
    </>
  );
  if (!interactive) return <div className="answered-row">{body}</div>;
  return (
    <button
      type="button"
      className="answered-row interactive"
      disabled={busy}
      onClick={onRewind}
    >
      {body}
      <span className="visually-hidden">{TEXT.rewindHint}</span>
    </button>
  );
}

/** 質問の props。 */
interface QuestionPromptProps {
  question: Question;
  busy: boolean;
  /** 回答を確定する。「わからない」は null。 */
  onAnswer: (answer: string | null) => void;
}

/**
 * 1問ぶんの質問。選択肢つきはボタン、自由記述は入力欄と「次へ」。
 * どちらにも「わからない」がある。押すと確定して次へ進む。
 * @param props 質問と回答の受け口
 */
export function QuestionPrompt({
  question,
  busy,
  onAnswer,
}: QuestionPromptProps) {
  const [text, setText] = useState("");
  const labelId = `q-${question.id}`;
  const unknownButton = (
    <button
      type="button"
      className="choice quiet"
      disabled={busy}
      onClick={() => onAnswer(null)}
    >
      {TEXT.unknown}
    </button>
  );

  if (question.options.length > 0) {
    return (
      <div className="system">
        <p className="question" id={labelId} tabIndex={-1} data-autofocus>
          {question.text}
        </p>
        <div className="choices" role="group" aria-labelledby={labelId}>
          {question.options.map((option) => (
            <button
              key={option}
              type="button"
              className="choice"
              disabled={busy}
              onClick={() => onAnswer(option)}
            >
              {option}
            </button>
          ))}
          {unknownButton}
        </div>
      </div>
    );
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (text.trim() !== "") onAnswer(text.trim());
  };
  return (
    <form className="system" onSubmit={handleSubmit}>
      <label className="question" htmlFor={`${labelId}-text`}>
        {question.text}
      </label>
      <textarea
        id={`${labelId}-text`}
        data-autofocus
        rows={3}
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      <div className="actions">
        <button
          type="submit"
          className="primary"
          disabled={busy || text.trim() === ""}
        >
          {TEXT.next}
        </button>
        {unknownButton}
      </div>
    </form>
  );
}
