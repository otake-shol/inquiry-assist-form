import type { Awaiting, ClosedBy, Declined, Ending } from "../api/types.ts";
import type { Messages } from "../i18n/messages.ts";

/**
 * 本人のページの「いまの状況」とボードのカードに出す、本人に見せる言葉（docs/ux-principles.md の表）。
 * 終わっていれば終わり方、そうでなければ待っていること。結果を確かめている件は、AI の答えか
 * 担当者の結果かで言葉が分かれる。
 * @param state 待っていることと終わり方
 * @param resultFromOperator 結果を確かめている件の結果が、担当者が伝えたものか
 * @param asker 辞書の、本人の画面の状態を表す言葉（`messages.asker`）
 */
export function askerStatusLabel(
  state: { awaiting: Awaiting | null; ending: Ending | null },
  resultFromOperator: boolean,
  asker: Messages["asker"],
): string {
  if (state.ending !== null) return asker.endings[state.ending];
  if (state.awaiting === null) return asker.endings.WITHDRAWN;
  if (state.awaiting === "RESULT_CONFIRMATION" && resultFromOperator) {
    return asker.operatorResult;
  }
  return asker.awaiting[state.awaiting];
}

/** 終わりの文言。 */
export interface EndingText {
  lead: string;
  /** 続けて示す、担当者が書いた理由（見送りだけ） */
  reason: string | null;
}

/**
 * 終わり方ごとの終わりの文言（docs/ux-principles.md の表）。
 * @param ending 終わり方
 * @param declined 見送りの理由（見送りで終わった件だけ）
 * @param asker 辞書の、本人の画面の状態を表す言葉（`messages.asker`）
 * @param closedBy 終わらせた人。時間で「取り下げ」になった件は、専用の文言にする
 */
export function endingText(
  ending: Ending,
  declined: Declined | null,
  asker: Messages["asker"],
  closedBy: ClosedBy | null = null,
): EndingText {
  return {
    lead:
      ending === "WITHDRAWN" && closedBy === "TIME"
        ? asker.endingTextTimeWithdrawn
        : asker.endingText[ending],
    reason: ending === "DECLINED" ? (declined?.reason ?? null) : null,
  };
}
