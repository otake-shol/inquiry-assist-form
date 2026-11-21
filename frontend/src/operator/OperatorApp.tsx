import { useCallback, useEffect, useReducer, useRef } from "react";
import * as api from "../api/client.ts";
import {
  subscribeOperatorStream,
  type SubscribeOperatorStream,
} from "../api/stream.ts";
import type { OperatorDetail, OperatorItem } from "../api/types.ts";
import { DetailView } from "./DetailView.tsx";
import {
  OP_TEXT,
  emptyText,
  formatFiledAt,
  opCategoryLabel,
} from "./labels.ts";
import {
  initialOperatorState,
  operatorReducer,
  tabTitleOf,
  unreadCountOf,
  visibleItems,
} from "./state.ts";

/** オペレータ画面の props。 */
interface OperatorAppProps {
  /** 通知ストリームの購読関数。テストでは偽物を渡す。 */
  subscribe?: SubscribeOperatorStream;
  /** URL で指定された問い合わせの id（一覧だけなら null） */
  routeId: string | null;
  /** 開く問い合わせを変える（URL を更新する）。一覧に戻るときは null。 */
  onNavigate: (id: string | null) => void;
}

/**
 * 一覧の1行。未読は太字と印をつける。
 * @param props 1件と選択状態
 */
function ListRow({
  item,
  selected,
  onOpen,
}: {
  item: OperatorItem;
  selected: boolean;
  onOpen: () => void;
}) {
  return (
    <li>
      <button
        type="button"
        className={`op-row ${item.read ? "" : "unread"} ${selected ? "selected" : ""}`}
        aria-current={selected ? "true" : undefined}
        onClick={onOpen}
      >
        <span className="op-row-top">
          {!item.read && (
            <>
              <span className="op-dot" aria-hidden="true" />
              <span className="visually-hidden">{OP_TEXT.unread}</span>
            </>
          )}
          <span className="op-badge">{opCategoryLabel(item.category)}</span>
          <span className="op-system">
            {item.system?.label ?? OP_TEXT.systemUnknown}
          </span>
          {item.sentImmediately && (
            <span className="op-badge strong">{OP_TEXT.sentImmediately}</span>
          )}
          <time className="op-time" dateTime={item.filedAt}>
            {formatFiledAt(item.filedAt)}
          </time>
        </span>
        <span className="op-summary">{item.summary}</span>
      </button>
    </li>
  );
}

/**
 * オペレータ画面。左に担当者へ送られた問い合わせの一覧、右に選んだ1件の詳細を出す。
 * 新しい問い合わせは通知ストリームで届き、一覧の先頭に足される。
 * @param props 購読関数の差し替え口
 */
export function OperatorApp({
  subscribe = subscribeOperatorStream,
  routeId,
  onNavigate,
}: OperatorAppProps) {
  const [state, dispatch] = useReducer(operatorReducer, initialOperatorState);
  const { items, onlyUnread, selectedId, detail, detailLoading, banner } =
    state;
  const unread = unreadCountOf(items);
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);
  const selectedRef = useRef<string | null>(null);

  const loadList = useCallback(() => {
    api
      .getOperatorList()
      .then((list) => dispatch({ type: "listLoaded", items: list.items }))
      .catch(() => dispatch({ type: "listFailed" }));
  }, []);

  useEffect(() => {
    loadList();
  }, [loadList]);

  useEffect(
    () =>
      subscribe({
        onFiled: (item) => dispatch({ type: "filed", item }),
        // 切断中に届いた分を取りこぼさないよう、再接続後に取り直す
        onReconnected: loadList,
      }),
    [subscribe, loadList],
  );

  useEffect(() => {
    const previous = document.title;
    document.title = tabTitleOf(unread);
    return () => {
      document.title = previous;
    };
  }, [unread]);

  /**
   * 1件を開く。未読なら既読にしながら全内容を受け取る。
   * @param id 問い合わせの id
   */
  const open = useCallback(async (id: string) => {
    selectedRef.current = id;
    dispatch({ type: "selected", id });
    try {
      const item = itemsRef.current.find((i) => i.id === id);
      let loaded: OperatorDetail =
        item !== undefined && !item.read
          ? await api.markRead(id)
          : await api.getOperatorDetail(id);
      if (!loaded.read) loaded = await api.markRead(id);
      if (selectedRef.current === id) {
        dispatch({ type: "detailLoaded", detail: loaded });
      }
    } catch (e) {
      if (selectedRef.current !== id) return;
      // 形式の崩れた id（途中で切れたリンクなど）には API が 400 VALIDATION を返す。
      // 開こうとした問い合わせが無い点は同じなので、404 と同じく「見つからない」として示す。
      const missing =
        e instanceof api.ApiError &&
        (e.code === "NOT_FOUND" || e.code === "VALIDATION");
      dispatch(missing ? { type: "detailNotFound" } : { type: "detailFailed" });
    }
  }, []);

  // URL（直接開いた場合、戻る・進む、一覧での選択）に合わせて詳細を開閉する
  const openedRoute = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (openedRoute.current === routeId) return;
    openedRoute.current = routeId;
    if (routeId === null) {
      selectedRef.current = null;
      dispatch({ type: "closeDetail" });
    } else {
      void open(routeId);
    }
  }, [routeId, open]);

  const visible = visibleItems(items, onlyUnread, selectedId);

  return (
    <div className="operator">
      <header className="op-head">
        <h1>{OP_TEXT.title}</h1>
        {unread > 0 && (
          <span className="op-unread" aria-label={`未読 ${unread}件`}>
            {OP_TEXT.unread} {unread}
          </span>
        )}
      </header>
      {banner !== null && (
        <div className="op-banner" role="status">
          <button
            type="button"
            className="op-banner-open"
            onClick={() => onNavigate(banner.id)}
          >
            <strong>{OP_TEXT.arrived}</strong>
            <span>{banner.summary}</span>
          </button>
          <button
            type="button"
            className="link"
            aria-label={OP_TEXT.dismiss}
            onClick={() => dispatch({ type: "bannerDismissed" })}
          >
            ×
          </button>
        </div>
      )}
      <div
        className={`op-layout ${selectedId !== null ? "has-selection" : ""}`}
      >
        <section className="op-list" aria-label={OP_TEXT.listLabel}>
          <label className="op-filter">
            <input
              type="checkbox"
              checked={onlyUnread}
              onChange={() => dispatch({ type: "unreadToggled" })}
            />
            {OP_TEXT.unreadOnly}
          </label>
          {state.notFound && (
            <p className="op-notfound" role="alert">
              {OP_TEXT.inquiryNotFound}
            </p>
          )}
          {state.failed && selectedId === null && (
            <p className="op-muted" role="alert">
              {OP_TEXT.loadFailed}
            </p>
          )}
          {state.loaded && !state.failed && visible.length === 0 && (
            <p className="op-muted">{emptyText(onlyUnread)}</p>
          )}
          <ul className="op-rows">
            {visible.map((item) => (
              <ListRow
                key={item.id}
                item={item}
                selected={item.id === selectedId}
                onOpen={() => onNavigate(item.id)}
              />
            ))}
          </ul>
        </section>
        <section className="op-detail" aria-label={OP_TEXT.detailLabel}>
          {selectedId === null && (
            <p className="op-muted op-placeholder">{OP_TEXT.noSelection}</p>
          )}
          {selectedId !== null && (
            <button
              type="button"
              className="link op-back"
              onClick={() => onNavigate(null)}
            >
              {OP_TEXT.back}
            </button>
          )}
          {detailLoading && <p className="op-muted">読み込んでいます…</p>}
          {selectedId !== null && state.failed && (
            <p className="op-muted" role="alert">
              {OP_TEXT.loadFailed}
            </p>
          )}
          {detail !== null && <DetailView detail={detail} />}
        </section>
      </div>
    </div>
  );
}
