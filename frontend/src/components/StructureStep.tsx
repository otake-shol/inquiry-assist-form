import { useState, type FormEvent } from "react";
import type { StructuredInquiry, Urgency } from "../api/types.ts";
import {
  fromStructuredDraft,
  toStructuredDraft,
  type StructuredDraft,
} from "../flow/screen.ts";
import { URGENCY_LABELS } from "../text/labels.ts";

/** 整形結果確認画面の props。 */
interface StructureStepProps {
  structured: StructuredInquiry;
  busy: boolean;
  onConfirm: (structured: StructuredInquiry) => void;
}

/**
 * 整形された問いを編集・確定する画面。やりたいことは必須。
 * @param props 整形結果と確定の受け口
 */
export function StructureStep({
  structured,
  busy,
  onConfirm,
}: StructureStepProps) {
  const [draft, setDraft] = useState<StructuredDraft>(() =>
    toStructuredDraft(structured),
  );
  const set = <K extends keyof StructuredDraft>(
    key: K,
    value: StructuredDraft[K],
  ) => setDraft((current) => ({ ...current, [key]: value }));

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onConfirm(fromStructuredDraft(draft));
  };

  return (
    <form onSubmit={handleSubmit}>
      <h2>この内容で進めてよいか確認してください</h2>
      <p>AIが問いを整理しました。違うところは直せます。</p>
      <label htmlFor="targetSystem">対象</label>
      <input
        id="targetSystem"
        type="text"
        value={draft.targetSystem}
        onChange={(event) => set("targetSystem", event.target.value)}
      />
      <label htmlFor="goal">やりたいこと（必須）</label>
      <textarea
        id="goal"
        rows={3}
        required
        value={draft.goal}
        onChange={(event) => set("goal", event.target.value)}
      />
      <label htmlFor="whatHappened">起きたこと</label>
      <textarea
        id="whatHappened"
        rows={3}
        value={draft.whatHappened}
        onChange={(event) => set("whatHappened", event.target.value)}
      />
      <label htmlFor="expectedResult">期待した結果</label>
      <textarea
        id="expectedResult"
        rows={3}
        value={draft.expectedResult}
        onChange={(event) => set("expectedResult", event.target.value)}
      />
      <label htmlFor="urgency">緊急度</label>
      <select
        id="urgency"
        value={draft.urgency}
        onChange={(event) => set("urgency", event.target.value as Urgency)}
      >
        {(Object.keys(URGENCY_LABELS) as Urgency[]).map((urgency) => (
          <option key={urgency} value={urgency}>
            {URGENCY_LABELS[urgency]}
          </option>
        ))}
      </select>
      <div className="actions">
        <button type="submit" disabled={busy || draft.goal.trim() === ""}>
          この内容で進める
        </button>
      </div>
    </form>
  );
}
