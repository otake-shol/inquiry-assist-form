import type {
  HandlingCounts,
  HandlingStatus,
  OperatorDetail,
  OperatorItem,
} from "../api/types.ts";
import { OP_TEXT } from "./labels.ts";

/** 対応状況のタブ。`ALL` は「すべて」。 */
export type StatusTab = "ALL" | HandlingStatus;

/** タブの並び順。 */
export const STATUS_TABS: StatusTab[] = ["OPEN", "IN_PROGRESS", "DONE", "ALL"];

/** オペレータ画面の状態。 */
export interface OperatorState {
  items: OperatorItem[];
  /** 開いている対応状況のタブ（既定は「すべて」） */
  tab: StatusTab;
  /** 対応状況ごとの件数（取得できていなければ null） */
  counts: HandlingCounts | null;
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
  | {
      type: "listLoaded";
      items: OperatorItem[];
      counts?: HandlingCounts | null;
    }
  | { type: "filed"; item: OperatorItem }
  | { type: "updated"; item: OperatorItem }
  | { type: "tabChanged"; tab: StatusTab }
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
  tab: "ALL",
  counts: null,
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
  tab: StatusTab = "ALL",
): OperatorItem[] {
  return items.filter(
    (item) =>
      item.id === selectedId ||
      ((!onlyUnread || !item.read) &&
        (tab === "ALL" ||
          item.handlingStatus == null ||
          item.handlingStatus === tab)),
  );
}

/**
 * 件数を、1件の対応状況が変わったぶんだけ直す。件数が取得できていなければ何もしない。
 * @param counts 対応状況ごとの件数
 * @param before 変わる前の対応状況（新しく現れた件なら null / undefined）
 * @param after 変わった後の対応状況
 */
export function adjustCounts(
  counts: HandlingCounts | null,
  before: HandlingStatus | null | undefined,
  after: HandlingStatus | null | undefined,
): HandlingCounts | null {
  if (counts === null || before === after) return counts;
  const next = { ...counts };
  if (before != null) next[before] = Math.max(0, (next[before] ?? 0) - 1);
  if (after != null) next[after] = (next[after] ?? 0) + 1;
  return next;
}

/**
 * 「すべて」の件数。対応状況ごとの件数の合計。
 * @param counts 対応状況ごとの件数
 */
export function totalCount(counts: HandlingCounts): number {
  return (counts.OPEN ?? 0) + (counts.IN_PROGRESS ?? 0) + (counts.DONE ?? 0);
}

/**
 * 更新された1件を一覧に反映する。開いているタブの対応状況から外れたら一覧から除き、
 * 一覧にあれば更新し（未読なら先頭へ動かし）、なければ先頭に足す。
 * 件数は、一覧に前の状態がある場合と、「すべて」のタブで新しく現れた場合だけ直す。
 * @param state 現在の状態
 * @param item 更新された1件
 */
function applyUpdate(state: OperatorState, item: OperatorItem): OperatorState {
  const previous = state.items.find((existing) => existing.id === item.id);
  const counts =
    previous !== undefined || state.tab === "ALL"
      ? adjustCounts(
          state.counts,
          previous?.handlingStatus,
          item.handlingStatus,
        )
      : state.counts;
  const outOfTab =
    state.tab !== "ALL" &&
    item.handlingStatus != null &&
    item.handlingStatus !== state.tab;
  let items: OperatorItem[];
  if (outOfTab) {
    items = state.items.filter((existing) => existing.id !== item.id);
  } else if (previous === undefined || !item.read) {
    items = mergeFiled(state.items, item);
  } else {
    items = state.items.map((existing) =>
      existing.id === item.id ? item : existing,
    );
  }
  return { ...state, items, counts };
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
        counts: action.counts ?? state.counts,
        loaded: true,
        failed: false,
      };
    case "tabChanged":
      return { ...state, tab: action.tab, loaded: false, failed: false };
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
        failed: false,
        notFound: false,
        banner: state.banner?.id === action.id ? null : state.banner,
      };
    case "detailLoaded": {
      const { detail } = action;
      const previous = state.items.find((item) => item.id === detail.id);
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
        counts:
          detail.handlingStatus == null || previous === undefined
            ? state.counts
            : adjustCounts(
                state.counts,
                previous?.handlingStatus,
                detail.handlingStatus,
              ),
      };
    }
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
