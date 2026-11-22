import type { HandlingStatus, InquiryListItem } from "../api/types.ts";

/**
 * 送った後の画面（ページの上部、一覧、やり取り）の文言。言葉づかいの仕様は docs/ux-principles.md。
 * 本人向けの画面なので、内部の言葉（「0次回答」「種別」「起票」「出典」「スキル」「モデル」）は使わない。
 */
export const USER_TEXT = {
  navLabel: "ページの移動",
  myInquiries: "送った問い合わせ",
  unreadReplies: (count: number) => `未読の返信 ${count}件`,
  loading: "読み込んでいます…",
  loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
  notFound: "この問い合わせは見つかりませんでした",
  listEmpty: "送った問い合わせはまだありません。",
  unreadReplyMark: "未読の返信",
  currentStatus: "いまの状況",
  exchange: "やり取り",
  noReplyYet: "担当者からの返信は、ここに表示されます。",
  appendLabel: "追加で伝える",
  appendSubmit: "送る",
  restartOther: "別の問い合わせを書く",
  traceSummary: "裏側を見る（開発者向け）",
  devToOperator: "開発用: 担当者の画面へ",
  devToAsker: "開発用: 本人の画面へ",
} as const;

/** 本人向けの画面での、対応状況の言葉。 */
export const HANDLING_STATUS_LABELS: Record<HandlingStatus, string> = {
  OPEN: "担当者の確認を待っています",
  IN_PROGRESS: "担当者が対応しています",
  DONE: "対応が完了しました",
};

/**
 * 「いまの状況」の1行。対応状況が無い（古い応答）ときは、担当者の確認待ちとして扱う。
 * @param status 対応状況
 */
export function handlingStatusLabel(
  status: HandlingStatus | null | undefined,
): string {
  return HANDLING_STATUS_LABELS[status ?? "OPEN"];
}

/** 担当者に送った問い合わせの、一覧での状態の言葉。 */
const FILED_STATUS_LABELS: Record<HandlingStatus, string> = {
  OPEN: "担当者の確認待ち",
  IN_PROGRESS: "担当者が対応中",
  DONE: "対応完了",
};

/**
 * 一覧に出す、問い合わせの状態の言葉（docs/ux-principles.md の「状態の言葉」）。
 * 表にない `ABANDONED` は「終了」と示す。
 * @param item 一覧の1件
 */
export function listStatusLabel(
  item: Pick<InquiryListItem, "step" | "outcome" | "handlingStatus">,
): string {
  if (item.step !== "CLOSED") return "途中（続きから再開できます）";
  switch (item.outcome) {
    case "SELF_RESOLVED":
      return "解決済み";
    case "FILED":
      return FILED_STATUS_LABELS[item.handlingStatus ?? "OPEN"];
    default:
      return "終了";
  }
}
