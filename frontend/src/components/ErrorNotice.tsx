import type { FlowError } from "../flow/reducer.ts";
import {
  AI_ERROR_BEFORE_CREATE_HINT,
  AI_ERROR_TEXT,
  GENERIC_ERROR_TEXT,
} from "../text/labels.ts";

/** エラー表示の props。 */
interface ErrorNoticeProps {
  error: FlowError;
  /** 送る先の問い合わせがすでにあるか（あれば「今すぐ担当者に送る」へ誘導する） */
  hasInquiry: boolean;
  /** 0次回答の再試行（再試行できる場面でのみ渡す） */
  onRetry?: () => void;
  /** 再試行ボタンの文言 */
  retryLabel?: string;
  busy: boolean;
}

/**
 * エラーを平易に伝える。AI 起因のエラーは「今すぐ担当者に送る」へ誘導する。
 * @param props エラーと誘導の有無
 */
export function ErrorNotice({
  error,
  hasInquiry,
  onRetry,
  retryLabel = "もう一度試す",
  busy,
}: ErrorNoticeProps) {
  const aiText = AI_ERROR_TEXT[error.code];
  return (
    <div className="notice error" role="alert">
      <p>{aiText ?? GENERIC_ERROR_TEXT}</p>
      {aiText !== undefined && (
        <p>
          {hasInquiry
            ? "ページの下の「今すぐ担当者に送る」から送れます。"
            : AI_ERROR_BEFORE_CREATE_HINT}
        </p>
      )}
      {onRetry !== undefined && (
        <button type="button" onClick={onRetry} disabled={busy}>
          {retryLabel}
        </button>
      )}
    </div>
  );
}
