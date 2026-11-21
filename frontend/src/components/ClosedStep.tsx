import type { InquiryView } from "../api/types.ts";
import { OUTCOME_TEXT, SENT_IMMEDIATELY_TEXT } from "../text/labels.ts";
import { AnswerView } from "./AnswerView.tsx";

/** 完了画面の props。 */
interface ClosedStepProps {
  view: InquiryView;
  onRestart: () => void;
}

/**
 * 結果ごとの文言を出す完了画面。
 * 担当者に送った場合も、0次回答があれば表示を残す。
 * @param props 問い合わせと「新しい問い合わせ」の受け口
 */
export function ClosedStep({ view, onRestart }: ClosedStepProps) {
  const text =
    view.outcome === "FILED" && view.sentImmediately
      ? SENT_IMMEDIATELY_TEXT
      : OUTCOME_TEXT[view.outcome];
  return (
    <div>
      <h2>{text.title}</h2>
      {text.body !== "" && <p>{text.body}</p>}
      {view.outcome === "INCIDENT_REDIRECTED" &&
        view.incidentGuidance !== null && (
          <div
            className="notice incident"
            role="region"
            aria-label="障害の案内"
          >
            <p>{view.incidentGuidance}</p>
          </div>
        )}
      {view.outcome === "FILED" && view.answer !== null && (
        <>
          <h3>0次回答（参考）</h3>
          <AnswerView answer={view.answer} />
        </>
      )}
      <div className="actions">
        <button type="button" onClick={onRestart}>
          新しい問い合わせを書く
        </button>
      </div>
    </div>
  );
}
