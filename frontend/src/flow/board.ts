import type { InquiryListItem } from "../api/types.ts";

/** 本人のボードの列。左から並べる順。 */
export type AskerColumn = "YOURS" | "THEIRS" | "CLOSED";

/** 本人のボードの列の並び（左から）。 */
export const ASKER_COLUMNS: AskerColumn[] = ["YOURS", "THEIRS", "CLOSED"];

/** 本人のボードの列の見出し。 */
export const ASKER_COLUMN_LABELS: Record<AskerColumn, string> = {
  YOURS: "あなたの番",
  THEIRS: "相手の番",
  CLOSED: "終わり",
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
  return sortUrgentUnreadNewest(items, () => false, isUnread, timeOfItem);
}

/**
 * 緊急度が「業務が止まっている」の件、未読の件の順に上へ寄せ、同じ条件なら新しい順にする。
 * 並べ替えは安定しているので、日時も同じなら渡された順のままになる。
 * @param items 並べる件
 * @param isUrgent 業務が止まっている件か
 * @param isUnread 未読か
 * @param timeOfItem 日時
 */
export function sortUrgentUnreadNewest<T>(
  items: T[],
  isUrgent: (item: T) => boolean,
  isUnread: (item: T) => boolean,
  timeOfItem: (item: T) => number,
): T[] {
  return [...items].sort((a, b) => {
    const urgent = Number(isUrgent(b)) - Number(isUrgent(a));
    if (urgent !== 0) return urgent;
    const unread = Number(isUnread(b)) - Number(isUnread(a));
    return unread !== 0 ? unread : timeOfItem(b) - timeOfItem(a);
  });
}

/**
 * 問い合わせが入る本人のボードの列（docs/ux-principles.md の「列」の表）。
 * 終わっていれば「終わり」、番が本人なら「あなたの番」、番が AI か担当者なら「相手の番」。
 * @param item 一覧の1件
 */
export function askerColumnOf(
  item: Pick<InquiryListItem, "phase" | "turn">,
): AskerColumn {
  if (item.phase === "CLOSED") return "CLOSED";
  return item.turn === "ASKER" ? "YOURS" : "THEIRS";
}

/**
 * 本人のボードの列ごとに、問い合わせを振り分けて並べる。
 * 列の中は新しい順で、緊急度が「業務が止まっている」の件、未読の返信がある件の順に上に寄せる。
 * @param items 一覧の全件
 */
export function arrangeAskerBoard(
  items: InquiryListItem[],
): Record<AskerColumn, InquiryListItem[]> {
  const board: Record<AskerColumn, InquiryListItem[]> = {
    YOURS: [],
    THEIRS: [],
    CLOSED: [],
  };
  for (const item of items) board[askerColumnOf(item)].push(item);
  for (const column of ASKER_COLUMNS) {
    board[column] = sortUrgentUnreadNewest(
      board[column],
      (item) => item.urgency === "BLOCKED",
      (item) => item.unreadReply,
      (item) => timeOf(item.createdAt),
    );
  }
  return board;
}
