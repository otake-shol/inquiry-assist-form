import type { FlowError } from "../flow/reducer.ts";
import {
  AI_ERROR_BEFORE_CREATE_HINT,
  AI_ERROR_SEND_NOW_HINT,
  AI_ERROR_TEXT,
  GENERIC_ERROR_TEXT,
  TEXT,
} from "../text/labels.ts";

/** エラー表示の props。 */
interface ErrorNoticeProps {
  error: FlowError;
  /** 送る先の問い合わせがすでにあるか（あれば、送る方法へ誘導する） */
  hasInquiry: boolean;
  /** 再試行（再試行できる場面でのみ渡す） */
  onRetry?: () => void;
  /** 再試行ボタンの文言 */
  retryLabel?: string;
  /**
   * 「担当者に送る」。入口の最後の答えのあとに問いを組み立てられなかったなど、ページの下に
   * 「答えを待たずに担当者に送る」を置かない場面で、エラーの表示の中に出す。
   */
  onSendNow?: () => void;
  busy: boolean;
}

/**
 * エラーを平易に伝える。AI 起因のエラーは、担当者に直接送る方法へ誘導する。
 * @param props エラーと誘導の有無
 */
export function ErrorNotice({
  error,
  hasInquiry,
  onRetry,
  retryLabel = TEXT.retryCompose,
  onSendNow,
  busy,
}: ErrorNoticeProps) {
  const aiText = AI_ERROR_TEXT[error.code];
  return (
    <div className="notice error" role="alert">
      <p>{aiText ?? GENERIC_ERROR_TEXT}</p>
      {aiText !== undefined && onSendNow === undefined && (
        <p>
          {hasInquiry ? AI_ERROR_SEND_NOW_HINT : AI_ERROR_BEFORE_CREATE_HINT}
        </p>
      )}
      {(onRetry !== undefined || onSendNow !== undefined) && (
        <div className="actions">
          {onRetry !== undefined && (
            <button type="button" onClick={onRetry} disabled={busy}>
              {retryLabel}
            </button>
          )}
          {onSendNow !== undefined && (
            <button type="button" onClick={onSendNow} disabled={busy}>
              {TEXT.sendToStaff}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
