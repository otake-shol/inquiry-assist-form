import type { InquiryListItem } from "../api/types.ts";

/** 本人のボードの列。左から並べる順。 */
export type AskerColumn = "DRAFT" | "WAITING" | "IN_PROGRESS" | "DONE";

/** 本人のボードの列の並び（左から）。 */
export const ASKER_COLUMNS: AskerColumn[] = [
  "DRAFT",
  "WAITING",
  "IN_PROGRESS",
  "DONE",
];

/** 本人のボードの列の見出し。 */
export const ASKER_COLUMN_LABELS: Record<AskerColumn, string> = {
  DRAFT: "入力の途中",
  WAITING: "担当者の確認待ち",
  IN_PROGRESS: "担当者が対応中",
  DONE: "完了",
};

/**
 * 日時の新しさを比べるための数値。読み取れない日時は最も古いものとして扱う。
 * @param iso ISO 8601 の日時
 */
export function timeOf(iso: string): number {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? 0 : time;
}

/**
 * 並べ替えの共通部分。未読を上に寄せ、同じ条件なら新しい順にする。
 * 並べ替えは安定しているので、日時も同じなら渡された順のままになる。
 * @param items 並べる件
 * @param isUnread 未読か
 * @param timeOfItem 日時
 */
export function sortUnreadFirstNewest<T>(
  items: T[],
  isUnread: (item: T) => boolean,
  timeOfItem: (item: T) => number,
): T[] {
  return [...items].sort((a, b) => {
    const unread = Number(isUnread(b)) - Number(isUnread(a));
    return unread !== 0 ? unread : timeOfItem(b) - timeOfItem(a);
  });
}

/**
 * 問い合わせが入る本人のボードの列（docs/ux-principles.md の「列」の表）。
 * 入力の途中（`CLOSED` 以外）が先で、担当者に送った件は対応状況で分かれる。
 * それ以外の終わり方（解決済み、障害の窓口を案内、終了）はすべて完了に入る。
 * 担当者に送った件で対応状況が無い古い応答は、確認待ちとして扱う。
 * @param item 一覧の1件
 */
export function askerColumnOf(
  item: Pick<InquiryListItem, "step" | "outcome" | "handlingStatus">,
): AskerColumn {
  if (item.step !== "CLOSED") return "DRAFT";
  if (item.outcome !== "FILED") return "DONE";
  switch (item.handlingStatus ?? "OPEN") {
    case "OPEN":
      return "WAITING";
    case "IN_PROGRESS":
      return "IN_PROGRESS";
    case "DONE":
      return "DONE";
  }
}

/**
 * 本人のボードの列ごとに、問い合わせを振り分けて並べる。
 * 列の中は新しい順で、未読の返信がある件を上に寄せる。
 * @param items 一覧の全件
 */
export function arrangeAskerBoard(
  items: InquiryListItem[],
): Record<AskerColumn, InquiryListItem[]> {
  const board: Record<AskerColumn, InquiryListItem[]> = {
    DRAFT: [],
    WAITING: [],
    IN_PROGRESS: [],
    DONE: [],
  };
  for (const item of items) board[askerColumnOf(item)].push(item);
  for (const column of ASKER_COLUMNS) {
    board[column] = sortUnreadFirstNewest(
      board[column],
      (item) => item.unreadReply,
      (item) => timeOf(item.createdAt),
    );
  }
  return board;
}
