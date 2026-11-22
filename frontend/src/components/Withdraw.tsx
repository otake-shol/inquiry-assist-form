import { useEffect, useRef, useState } from "react";
import { USER_TEXT } from "../text/inquiries.ts";

/** 取り下げるリンクの props。 */
interface WithdrawProps {
  busy: boolean;
  /** 取り下げる */
  onWithdraw: () => void;
}

/**
 * ページの最下部に置く、控えめなリンク「この問い合わせを取り下げる」。押すと、その場に確認
 * （「取り下げる」「やめる」）が出る。ブラウザの確認の窓は使わない。確認が出たらフォーカスを
 * 確認の文に移し、「やめる」で元のリンクに戻す。
 * @param props 取り下げの受け口
 */
export function Withdraw({ busy, onWithdraw }: WithdrawProps) {
  const [confirming, setConfirming] = useState(false);
  const linkRef = useRef<HTMLButtonElement | null>(null);
  const confirmRef = useRef<HTMLParagraphElement | null>(null);
  // 「やめる」で閉じたときだけ、元のリンクにフォーカスを戻す
  const returnFocus = useRef(false);

  useEffect(() => {
    if (confirming) {
      confirmRef.current?.focus();
    } else if (returnFocus.current) {
      returnFocus.current = false;
      linkRef.current?.focus();
    }
  }, [confirming]);

  if (!confirming) {
    return (
      <div className="withdraw">
        <button
          type="button"
          className="link"
          ref={linkRef}
          onClick={() => setConfirming(true)}
        >
          {USER_TEXT.withdrawLink}
        </button>
      </div>
    );
  }
  return (
    <div
      className="withdraw confirming"
      role="group"
      aria-labelledby="withdraw-confirm"
    >
      <p id="withdraw-confirm" tabIndex={-1} ref={confirmRef}>
        {USER_TEXT.withdrawConfirm}
      </p>
      <div className="actions">
        <button
          type="button"
          className="primary"
          disabled={busy}
          onClick={onWithdraw}
        >
          {USER_TEXT.withdrawDo}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            returnFocus.current = true;
            setConfirming(false);
          }}
        >
          {USER_TEXT.withdrawCancel}
        </button>
      </div>
    </div>
  );
}
