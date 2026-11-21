import type { FormEvent } from "react";

/** 入力画面の props。 */
interface InputStepProps {
  draft: string;
  busy: boolean;
  onChange: (draft: string) => void;
  onSubmit: () => void;
}

/** 発話の最大文字数（契約書: 1〜4000文字）。 */
const MAX_LENGTH = 4000;

/**
 * 悩みを自由記述で書く入力画面。形式は強制しない。
 * @param props 入力値と送信の受け口
 */
export function InputStep({ draft, busy, onChange, onSubmit }: InputStepProps) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };
  return (
    <form onSubmit={handleSubmit}>
      <h2>困っていることを書いてください</h2>
      <label htmlFor="utterance">困りごと</label>
      <textarea
        id="utterance"
        rows={8}
        maxLength={MAX_LENGTH}
        value={draft}
        placeholder="困っていることをそのまま書いてください"
        onChange={(event) => onChange(event.target.value)}
      />
      <div className="actions">
        <button type="submit" disabled={busy || draft.trim() === ""}>
          送信する
        </button>
      </div>
    </form>
  );
}
