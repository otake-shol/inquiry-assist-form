import type {
  OperatorDetail,
  OperatorItem,
  StateFields,
} from "../api/types.ts";
import { sortUrgentUnreadNewest, timeOf } from "../flow/board.ts";
import type { Messages } from "../i18n/messages.ts";
import { movedText } from "./labels.ts";

/** 担当者のボードの列。誰の番かで分ける。左から並べる順。 */
export type OperatorColumn = "OPERATOR" | "ASKER" | "CLOSED";

/** 担当者のボードの列の並び（左から）。 */
export const OPERATOR_COLUMNS: OperatorColumn[] = [
  "OPERATOR",
  "ASKER",
  "CLOSED",
];

/** カードを落としたときに始める操作。「本人に送る」の入力欄か、「見送る」の入力欄を開く。 */
export type DropIntent = "send" | "decline";

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
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function tabTitleOf(unread: number, op: Messages["op"]): string {
  return unread > 0 ? `(${unread}) ${op.tabTitle}` : op.tabTitle;
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
 * 問い合わせが入る担当者のボードの列（docs/ux-principles.md の「列」の表）。
 * 終わっていれば「終わり」、番が担当者なら「担当者の番」、それ以外（本人の番）は「本人の番」。
 * @param item 一覧の1件
 */
export function operatorColumnOf(
  item: Pick<StateFields, "phase" | "turn">,
): OperatorColumn {
  if (item.phase === "CLOSED") return "CLOSED";
  return item.turn === "OPERATOR" ? "OPERATOR" : "ASKER";
}

/**
 * 担当者のボードの列ごとに、問い合わせを振り分けて並べる。
 * 列の中は新しい順（担当者に初めて渡した日時）で、緊急度が「業務が止まっている」の件、
 * 未読の件の順に上へ寄せる。日時も同じなら渡された順のまま。
 * @param items 一覧の全件
 */
export function arrangeOperatorBoard(
  items: OperatorItem[],
): Record<OperatorColumn, OperatorItem[]> {
  const board: Record<OperatorColumn, OperatorItem[]> = {
    OPERATOR: [],
    ASKER: [],
    CLOSED: [],
  };
  for (const item of items) board[operatorColumnOf(item)].push(item);
  for (const column of OPERATOR_COLUMNS) {
    board[column] = sortUrgentUnreadNewest(
      board[column],
      (item) => item.urgency === "BLOCKED",
      (item) => !item.read,
      (item) => timeOf(item.filedAt),
    );
  }
  return board;
}

/**
 * カードを別の列に落とせるか。落とせるのは「担当者の番」から「本人の番」「終わり」へだけ。
 * 番を戻すのは本人の操作なので、「本人の番」や「終わり」から動かす操作は受け付けない。
 * @param from 掴んだカードがある列
 * @param to 落とす先の列
 */
export function canDropOnColumn(
  from: OperatorColumn,
  to: OperatorColumn,
): boolean {
  return from === "OPERATOR" && to !== "OPERATOR";
}

/**
 * 落とした列に対応する、始める操作。「本人の番」なら「本人に送る」、「終わり」なら「見送る」。
 * @param to 落とす先の列
 */
export function dropIntentOf(to: OperatorColumn): DropIntent | null {
  if (to === "ASKER") return "send";
  if (to === "CLOSED") return "decline";
  return null;
}

/**
 * カードに添える、待っていることや終わり方。「本人の番」の列では何を待っているか
 * （返事待ち、結果の確かめ待ち）、「終わり」の列では終わり方。「担当者の番」の列には添えない。
 * @param item 一覧の1件
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function cardNoteOf(
  item: Pick<StateFields, "phase" | "turn" | "awaiting" | "ending">,
  op: Messages["op"],
): string | null {
  const column = operatorColumnOf(item);
  if (column === "CLOSED") {
    return item.ending === null ? null : op.endings[item.ending];
  }
  if (column === "ASKER") {
    return item.awaiting === null ? null : op.awaiting[item.awaiting];
  }
  return null;
}

/**
 * カードに添える区間。「分かる」「決まる」「行われる」だけを出し、受付と終わりは出さない。
 * @param item 一覧の1件
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function cardPhaseOf(
  item: Pick<StateFields, "phase">,
  op: Messages["op"],
): string | null {
  return item.phase === "KNOW" ||
    item.phase === "DECIDE" ||
    item.phase === "ACT"
    ? op.phases[item.phase]
    : null;
}

/**
 * 一覧が更新されたとき、別の列に移った件を読み上げ用の文にする。複数あれば最後の1件。
 * 前の一覧になかった件（新しく届いた件）は、移ったものとして扱わない。
 * @param before 更新前の一覧
 * @param after 更新後の一覧
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 * @returns 移った件がなければ空文字
 */
export function movedMessageOf(
  before: OperatorItem[],
  after: OperatorItem[],
  op: Messages["op"],
): string {
  const previous = new Map(
    before.map((item) => [item.id, operatorColumnOf(item)]),
  );
  let message = "";
  for (const item of after) {
    const column = operatorColumnOf(item);
    const was = previous.get(item.id);
    if (was !== undefined && was !== column) {
      message = movedText(item.summary, op.columns[column], op);
    }
  }
  return message;
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
        // 状態、置き場所、やり取りの数は、詳細で変わったぶんを一覧の1件にも反映する
        items: state.items.map((item) =>
          item.id === detail.id
            ? {
                ...item,
                read: detail.read ? true : item.read,
                urgency: detail.urgency,
                type: detail.type,
                system: detail.system,
                function: detail.function,
                phase: detail.phase,
                turn: detail.turn,
                awaiting: detail.awaiting,
                ending: detail.ending,
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
