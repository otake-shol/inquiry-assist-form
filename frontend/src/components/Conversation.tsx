import { useId, useState, type FormEvent, type ReactNode } from "react";
import type {
  IntakeAnswerRequest,
  IntakeEntry,
  IntakeOption,
  IntakeQuestion,
  SystemOption,
  Urgency,
} from "../api/types.ts";
import { MAX_UTTERANCE_LENGTH } from "../flow/page.ts";
import { TEXT } from "../text/labels.ts";

/** 文章で答える質問の最大文字数（契約書: 1〜1000文字）。補足は 4000 文字まで。 */
const MAX_ANSWER_LENGTH = 1000;

/**
 * 本人が書いた文章。画面側の言葉と区別できる形で残す。
 * @param props 文章
 */
export function UtteranceRow({ text }: { text: string }) {
  return (
    <div className="utterance">
      <p>{text}</p>
    </div>
  );
}

/**
 * 答えの表示用の文字列。選んだものは名前、文章はその本文、わからないはその言葉で示す。
 * @param entry 答え終えた質問
 */
function answerLabelOf(entry: IntakeEntry): string {
  switch (entry.answer.kind) {
    case "OPTION":
    case "INFERRED":
      return entry.answer.label;
    case "TEXT":
      return entry.answer.text;
    case "UNKNOWN":
      return TEXT.unknown;
    case "SKIPPED":
      return TEXT.skippedAnswer;
  }
}

/** 答えた質問の1行の props。 */
interface EntryRowProps {
  entry: IntakeEntry;
  /** 押して答え直せるか（入口の質問の間だけ） */
  interactive: boolean;
  busy: boolean;
  onBack: (questionId: string) => void;
}

/**
 * 「質問 → 答え」の1行。入口の質問の間は、押すとその質問に戻れる（それより後の答えは消える）。
 * AI が文章から置いた答えには「（文章から読み取りました）」と添える。
 * @param props 答え終えた質問と、戻る操作の受け口
 */
export function EntryRow({ entry, interactive, busy, onBack }: EntryRowProps) {
  const body = (
    <>
      <span className="answered-q">{entry.text}</span>
      <span className="answered-arrow" aria-hidden="true">
        →
      </span>
      <span className="answered-a">{answerLabelOf(entry)}</span>
      {entry.answer.kind === "INFERRED" && (
        <span className="answered-note">{TEXT.inferredNote}</span>
      )}
    </>
  );
  if (!interactive) return <div className="answered-row">{body}</div>;
  return (
    <button
      type="button"
      className="answered-row interactive"
      disabled={busy}
      onClick={() => onBack(entry.questionId)}
    >
      {body}
      <span className="visually-hidden">{TEXT.rewindHint}</span>
    </button>
  );
}

/** 文章で答える欄の props。 */
interface TextAnswerProps {
  /** 欄の見出し（質問文か、選択肢にない場合の案内） */
  label: string;
  /** 見出しを質問文として太く見せるか */
  asQuestion: boolean;
  maxLength: number;
  busy: boolean;
  /** 質問が現れたときにフォーカスを移す欄か */
  autofocus: boolean;
  /** 「次へ」の右に並べるボタン（わからない、書かずに進む） */
  children?: ReactNode;
  onSubmit: (text: string) => void;
}

/**
 * 文章で答える入力欄と「次へ」。空（空白だけを含む）では送れない。
 * @param props 見出し、上限、送る処理
 */
function TextAnswer({
  label,
  asQuestion,
  maxLength,
  busy,
  autofocus,
  children,
  onSubmit,
}: TextAnswerProps) {
  const id = useId();
  const [text, setText] = useState("");
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (text.trim() !== "" && !busy) onSubmit(text.trim());
  };
  return (
    <form onSubmit={handleSubmit} className="text-answer">
      <label
        className={asQuestion ? "question" : "text-answer-label"}
        htmlFor={id}
      >
        {label}
      </label>
      <textarea
        id={id}
        data-autofocus={autofocus ? true : undefined}
        rows={3}
        maxLength={maxLength}
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
        {children}
      </div>
    </form>
  );
}

/** 質問の props。 */
interface QuestionPromptProps {
  question: IntakeQuestion;
  busy: boolean;
  /**
   * 種類の選択肢に添える短い説明（種類の id から説明へ）。API の選択肢に `hint` が無いときの補いで、
   * 種類の質問（`TYPE`）だけに使う。
   */
  typeHints: Record<string, string>;
  /** 答えを確定する。 */
  onAnswer: (answer: IntakeAnswerRequest) => void;
}

/**
 * 1問ぶんの質問。API が返した質問（または作成前の1問目）を描くだけで、順番や内容は決めない。
 * - 選択肢つき: 質問文とひも付けたボタンの並び。最後に控えめな「わからない」（補足を除く）。
 *   種類の選択肢は、名前と短い説明を縦に並べる。`allowsText` なら文章で答える欄も出す。
 * - 文章で答える: 入力欄と「次へ」「わからない」。
 * - 補足: 入力欄と「次へ」「書かずに進む」。書かずには進めない場合（`canSkip` が偽）は「書かずに進む」を出さない。
 * @param props 質問と、答えの受け口
 */
export function QuestionPrompt({
  question,
  busy,
  typeHints,
  onAnswer,
}: QuestionPromptProps) {
  const baseId = useId();
  const labelId = `${baseId}-label`;
  const isNote = question.kind === "NOTE";
  const hintOf = (option: IntakeOption): string | undefined =>
    option.hint ??
    (question.kind === "TYPE" ? typeHints[option.id] : undefined);

  const unknownButton = isNote ? null : (
    <button
      type="button"
      className="choice quiet"
      disabled={busy}
      onClick={() => onAnswer({ unknown: true })}
    >
      {TEXT.unknown}
    </button>
  );

  if (question.options.length > 0) {
    const stacked = question.options.some(
      (option) => hintOf(option) !== undefined,
    );
    return (
      <div className="system">
        <p className="question" id={labelId} tabIndex={-1} data-autofocus>
          {question.text}
        </p>
        <div
          className={`choices ${stacked ? "stacked" : ""}`}
          role="group"
          aria-labelledby={labelId}
        >
          {question.options.map((option) => {
            const hint = hintOf(option);
            const nameId = `${baseId}-${option.id}-name`;
            const hintId = `${baseId}-${option.id}-hint`;
            return (
              <button
                key={option.id}
                type="button"
                className={`choice ${hint === undefined ? "" : "with-hint"}`}
                disabled={busy}
                aria-labelledby={nameId}
                aria-describedby={hint === undefined ? undefined : hintId}
                onClick={() => onAnswer({ optionId: option.id })}
              >
                <span id={nameId} className="choice-name">
                  {option.label}
                </span>
                {hint !== undefined && (
                  <span id={hintId} className="choice-hint">
                    {hint}
                  </span>
                )}
              </button>
            );
          })}
          {unknownButton}
        </div>
        {question.allowsText && (
          <TextAnswer
            label={TEXT.alsoWriteLabel}
            asQuestion={false}
            maxLength={MAX_ANSWER_LENGTH}
            busy={busy}
            autofocus={false}
            onSubmit={(text) => onAnswer({ text })}
          />
        )}
      </div>
    );
  }

  return (
    <div className="system">
      <TextAnswer
        label={question.text}
        asQuestion
        maxLength={isNote ? MAX_UTTERANCE_LENGTH : MAX_ANSWER_LENGTH}
        busy={busy}
        autofocus
        onSubmit={(text) => onAnswer({ text })}
      >
        {isNote
          ? question.canSkip && (
              <button
                type="button"
                className="choice quiet"
                disabled={busy}
                onClick={() => onAnswer({ skipped: true })}
              >
                {TEXT.skip}
              </button>
            )
          : unknownButton}
      </TextAnswer>
    </div>
  );
}

/** 緊急度の選択の props。 */
interface UrgencyChoiceProps {
  /** 選択肢（API が返す名前をそのまま出す。空なら何も出さない） */
  options: SystemOption[];
  /** 選ばれている緊急度 */
  value: Urgency;
  busy: boolean;
  onChange: (urgency: Urgency) => void;
}

/**
 * 「急ぎですか」と、3つの選択（通常、急ぎ、業務が止まっている）。質問の下に小さく置く。
 * 選ばれているものが読み上げでも分かるよう、ラジオボタンのグループにする。
 * @param props 選択肢と選ばれている値
 */
export function UrgencyChoice({
  options,
  value,
  busy,
  onChange,
}: UrgencyChoiceProps) {
  const name = useId();
  if (options.length === 0) return null;
  return (
    <fieldset className="urgency">
      <legend>{TEXT.urgencyLegend}</legend>
      <div className="urgency-options">
        {options.map((option) => (
          <label key={option.id} className="urgency-option">
            <input
              type="radio"
              name={name}
              value={option.id}
              checked={value === option.id}
              disabled={busy}
              onChange={() => onChange(option.id as Urgency)}
            />
            <span>{option.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** 「文章で書く」の props。 */
interface WriteByTextProps {
  /** 入力欄を開いているか */
  open: boolean;
  draft: string;
  busy: boolean;
  onOpen: () => void;
  onChange: (draft: string) => void;
  onSubmit: () => void;
}

/**
 * 質問の下に置く、控えめなリンク「文章で書く」。押すと案内文つきの入力欄と「送信する」が出る。
 * 文章で書きたい人のために、どの質問の下にも残す。
 * @param props 入力欄の開閉と、入力値、送る処理
 */
export function WriteByText({
  open,
  draft,
  busy,
  onOpen,
  onChange,
  onSubmit,
}: WriteByTextProps) {
  const id = useId();
  if (!open) {
    return (
      <p className="write-link">
        <button type="button" className="link" onClick={onOpen}>
          {TEXT.writeLink}
        </button>
      </p>
    );
  }
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (draft.trim() !== "" && !busy) onSubmit();
  };
  return (
    <form onSubmit={handleSubmit} className="input-box">
      <label htmlFor={id}>{TEXT.inputLabel}</label>
      <textarea
        id={id}
        rows={7}
        maxLength={MAX_UTTERANCE_LENGTH}
        autoFocus
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
