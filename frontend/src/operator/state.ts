import type {
  HandlingStatus,
  OperatorDetail,
  OperatorItem,
} from "../api/types.ts";
import { sortUnreadFirstNewest, timeOf } from "../flow/board.ts";
import { OP_TEXT } from "./labels.ts";

/** 担当者のボードの列（対応状況）。左から並べる順。 */
export const OPERATOR_COLUMNS: HandlingStatus[] = [
  "OPEN",
  "IN_PROGRESS",
  "DONE",
];

/** オペレータ画面の状態。 */
export interface OperatorState {
  items: OperatorItem[];
  loaded: boolean;
  onlyUnread: boolean;
  selectedId: string | null;
  detail: OperatorDetail | null;
  detailLoading: boolean;
  /** 直近に届いた通知（押すとその1件を開く） */
  banner: OperatorItem | null;
  /** 一覧の取得に失敗したか */
  failed: boolean;
  /** 開こうとした問い合わせの取得に失敗したか */
  detailFailed: boolean;
  /** 指定された問い合わせが無い（または担当者に送られていない） */
  notFound: boolean;
}

/** 状態を変える操作。 */
export type OperatorAction =
  | { type: "listLoaded"; items: OperatorItem[] }
  | { type: "filed"; item: OperatorItem }
  | { type: "updated"; item: OperatorItem }
  | { type: "statusMoved"; id: string; status: HandlingStatus }
  | { type: "selected"; id: string }
  | { type: "detailLoaded"; detail: OperatorDetail }
  | { type: "detailFailed" }
  | { type: "detailNotFound" }
  | { type: "closeDetail" }
  | { type: "unreadToggled" }
  | { type: "bannerDismissed" }
  | { type: "listFailed" };

/** 最初の状態。 */
export const initialOperatorState: OperatorState = {
  items: [],
  loaded: false,
  onlyUnread: false,
  selectedId: null,
  detail: null,
  detailLoading: false,
  banner: null,
  failed: false,
  detailFailed: false,
  notFound: false,
};

/**
 * 未読の数。
 * @param items 一覧
 */
export function unreadCountOf(items: OperatorItem[]): number {
  return items.filter((item) => !item.read).length;
}

/**
 * ブラウザのタブの題名。未読があれば先頭に件数をつける。
 * @param unread 未読数
 */
export function tabTitleOf(unread: number): string {
  return unread > 0 ? `(${unread}) ${OP_TEXT.tabTitle}` : OP_TEXT.tabTitle;
}

/**
 * 一覧に表示する項目。「未読だけ」でも、いま開いている1件は残す。
 * @param items 一覧
 * @param onlyUnread 未読だけに絞るか
 * @param selectedId 開いている問い合わせの id
 */
export function visibleItems(
  items: OperatorItem[],
  onlyUnread: boolean,
  selectedId: string | null,
): OperatorItem[] {
  return items.filter(
    (item) => item.id === selectedId || !onlyUnread || !item.read,
  );
}

/**
 * 問い合わせが入る担当者のボードの列。対応状況が無い古い応答は「未対応」として扱う。
 * @param item 一覧の1件
 */
export function operatorColumnOf(
  item: Pick<OperatorItem, "handlingStatus">,
): HandlingStatus {
  return item.handlingStatus ?? "OPEN";
}

/**
 * 担当者のボードの列ごとに、問い合わせを振り分けて並べる。
 * 列の中は新しい順（送られた日時）で、未読の件を上に寄せる。日時も同じなら渡された順のまま。
 * @param items 一覧の全件
 */
export function arrangeOperatorBoard(
  items: OperatorItem[],
): Record<HandlingStatus, OperatorItem[]> {
  const board: Record<HandlingStatus, OperatorItem[]> = {
    OPEN: [],
    IN_PROGRESS: [],
    DONE: [],
  };
  for (const item of items) board[operatorColumnOf(item)].push(item);
  for (const column of OPERATOR_COLUMNS) {
    board[column] = sortUnreadFirstNewest(
      board[column],
      (item) => !item.read,
      (item) => timeOf(item.filedAt),
    );
  }
  return board;
}

/**
 * 更新された1件を一覧に反映する。一覧にあれば更新し（未読なら先頭へ動かし）、なければ先頭に足す。
 * @param state 現在の状態
 * @param item 更新された1件
 */
function applyUpdate(state: OperatorState, item: OperatorItem): OperatorState {
  const previous = state.items.find((existing) => existing.id === item.id);
  const items =
    previous === undefined || !item.read
      ? mergeFiled(state.items, item)
      : state.items.map((existing) =>
          existing.id === item.id ? item : existing,
        );
  return { ...state, items };
}

/**
 * 届いた1件を一覧の先頭に足す。すでにあれば置き換える。
 * @param items 一覧
 * @param item 届いた1件
 */
export function mergeFiled(
  items: OperatorItem[],
  item: OperatorItem,
): OperatorItem[] {
  return [item, ...items.filter((existing) => existing.id !== item.id)];
}

/**
 * オペレータ画面の状態遷移。
 * @param state 現在の状態
 * @param action 操作
 */
export function operatorReducer(
  state: OperatorState,
  action: OperatorAction,
): OperatorState {
  switch (action.type) {
    case "listLoaded":
      return {
        ...state,
        // 先に既読になった詳細があれば、一覧の取得が遅れても未読に戻さない
        items:
          state.detail?.read === true
            ? action.items.map((item) =>
                item.id === state.detail?.id ? { ...item, read: true } : item,
              )
            : action.items,
        loaded: true,
        failed: false,
      };
    case "statusMoved":
      // ドラッグでの移動をすぐ反映する。開いている詳細の切り替えも合わせる
      return {
        ...state,
        items: state.items.map((item) =>
          item.id === action.id
            ? { ...item, handlingStatus: action.status }
            : item,
        ),
        detail:
          state.detail?.id === action.id
            ? { ...state.detail, handlingStatus: action.status }
            : state.detail,
      };
    case "updated":
      return applyUpdate(state, action.item);
    case "listFailed":
      return { ...state, loaded: true, failed: true };
    case "filed":
      return { ...applyUpdate(state, action.item), banner: action.item };
    case "selected":
      return {
        ...state,
        selectedId: action.id,
        detail: null,
        detailLoading: true,
        detailFailed: false,
        notFound: false,
        banner: state.banner?.id === action.id ? null : state.banner,
      };
    case "detailLoaded": {
      const { detail } = action;
      return {
        ...state,
        detail,
        detailLoading: false,
        // 対応状況ややり取りの数は、詳細で変わったぶんを一覧の1件にも反映する
        items: state.items.map((item) =>
          item.id === detail.id
            ? {
                ...item,
                read: detail.read ? true : item.read,
                handlingStatus: detail.handlingStatus ?? item.handlingStatus,
                messageCount: detail.messages?.length ?? item.messageCount,
              }
            : item,
        ),
      };
    }
    case "detailFailed":
      return { ...state, detailLoading: false, detailFailed: true };
    case "detailNotFound":
      return {
        ...state,
        selectedId: null,
        detail: null,
        detailLoading: false,
        notFound: true,
      };
    case "closeDetail":
      return {
        ...state,
        selectedId: null,
        detail: null,
        detailLoading: false,
        detailFailed: false,
        notFound: false,
      };
    case "unreadToggled":
      return { ...state, onlyUnread: !state.onlyUnread };
    case "bannerDismissed":
      return { ...state, banner: null };
  }
}
