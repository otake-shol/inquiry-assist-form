import type { OperatorDetail, OperatorItem } from "../api/types.ts";
import { OP_TEXT } from "./labels.ts";

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
  failed: boolean;
  /** 指定された問い合わせが無い（または担当者に送られていない） */
  notFound: boolean;
}

/** 状態を変える操作。 */
export type OperatorAction =
  | { type: "listLoaded"; items: OperatorItem[] }
  | { type: "filed"; item: OperatorItem }
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
  return onlyUnread
    ? items.filter((item) => !item.read || item.id === selectedId)
    : items;
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
    case "listFailed":
      return { ...state, loaded: true, failed: true };
    case "filed":
      return {
        ...state,
        items: mergeFiled(state.items, action.item),
        banner: action.item,
      };
    case "selected":
      return {
        ...state,
        selectedId: action.id,
        detail: null,
        detailLoading: true,
        failed: false,
        notFound: false,
        banner: state.banner?.id === action.id ? null : state.banner,
      };
    case "detailLoaded":
      return {
        ...state,
        detail: action.detail,
        detailLoading: false,
        items: action.detail.read
          ? state.items.map((item) =>
              item.id === action.detail.id ? { ...item, read: true } : item,
            )
          : state.items,
      };
    case "detailFailed":
      return { ...state, detailLoading: false, failed: true };
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
        notFound: false,
      };
    case "unreadToggled":
      return { ...state, onlyUnread: !state.onlyUnread };
    case "bannerDismissed":
      return { ...state, banner: null };
  }
}
