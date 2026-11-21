import type { InquiryCategory } from "../api/types.ts";

/**
 * オペレータ画面の文言。担当者は開発チームなので、内部の言葉（種別・出典・技術メモ）を使ってよい。
 */

/** 種別の呼び名。 */
export const OP_CATEGORY_LABELS: Record<InquiryCategory, string> = {
  SPEC_QUESTION: "仕様確認・相談",
  SUSPECTED_BUG: "不具合の疑い",
  WORK_REQUEST: "作業依頼",
  INCIDENT: "障害",
};

/**
 * 種別の呼び名を返す。判定できなかった問い合わせは「種別未判定」。
 * @param category 種別（判定できなかった場合は null）
 */
export function opCategoryLabel(category: InquiryCategory | null): string {
  return category === null ? "種別未判定" : OP_CATEGORY_LABELS[category];
}

/** 画面の固定文言。 */
export const OP_TEXT = {
  title: "問い合わせ受付",
  tabTitle: "問い合わせ受付",
  unreadOnly: "未読だけ",
  listLabel: "受付一覧",
  detailLabel: "問い合わせの詳細",
  empty: "担当者に送られた問い合わせはまだありません。",
  emptyUnread: "未読の問い合わせはありません。",
  noSelection: "左の一覧から問い合わせを選んでください。",
  back: "一覧に戻る",
  arrived: "新しい問い合わせが届きました",
  dismiss: "通知を閉じる",
  unread: "未読",
  sentImmediately: "今すぐ送る",
  systemUnknown: "システム未定",
  systemGuessed: "（AIの推測）",
  slackOk: "Slack に投稿済み",
  slackFailed: "Slack への投稿に失敗しました",
  inquiryNotFound: "この問い合わせは見つかりませんでした",
  utterance: "元の発話",
  restatement: "言い直し",
  followUps: "追加質問と回答",
  unknownAnswer: "わからない",
  revisionNotes: "作り直しで伝えられた一言",
  result: "調べた結果",
  noResult: "調べた結果はありません（調査の前に送られました）",
  notGrounded: "一次情報から答えを特定できませんでした",
  findings: "分かったこと",
  notFound: "調べたが見つからなかったこと",
  nextChecks: "次に確認すること",
  citations: "出典",
  verified: "照合済み",
  unverified: "照合できず",
  loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
} as const;

/**
 * 一覧が空のときの文言。
 * @param onlyUnread 未読だけに絞っているか
 */
export function emptyText(onlyUnread: boolean): string {
  return onlyUnread ? OP_TEXT.emptyUnread : OP_TEXT.empty;
}

/**
 * 送られた時刻を「月/日 時:分」で表す。
 * @param iso ISO 8601 の日時
 */
export function formatFiledAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("ja-JP", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
