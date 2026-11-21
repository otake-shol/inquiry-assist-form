import {
  type DragEvent,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from "react";
import * as api from "../api/client.ts";
import {
  subscribeOperatorStream,
  type SubscribeOperatorStream,
} from "../api/stream.ts";
import type {
  AppConfig,
  HandlingStatus,
  OperatorDetail,
  OperatorItem,
} from "../api/types.ts";
import { BoardColumn } from "../components/BoardColumn.tsx";
import { DevLink } from "../components/Navigation.tsx";
import { NEW_INQUIRY_PATH } from "../path.ts";
import { USER_TEXT } from "../text/inquiries.ts";
import { DetailView } from "./DetailView.tsx";
import {
  HANDLING_LABELS,
  OP_TEXT,
  formatFiledAt,
  movedText,
  opCategoryLabel,
} from "./labels.ts";
import {
  OPERATOR_COLUMNS,
  arrangeOperatorBoard,
  initialOperatorState,
  operatorColumnOf,
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

/** OperatorCard の props。 */
interface OperatorCardProps {
  item: OperatorItem;
  /** 詳細のパネルで開いているか */
  selected: boolean;
  /** いま掴まれているか */
  dragging: boolean;
  onOpen: () => void;
  onDragStart: () => void;
  onDragEnd: () => void;
}

/**
 * ボードのカード1枚。押すと詳細のパネルを開き、ドラッグすると別の列へ移せる。
 * 中身は押せる部分（ボタン）だけにし、ドラッグはその外側の `li` で受ける。
 * 未読は太字と印で示し、読み上げにも「未読」を含める。
 * @param props 1件と、操作の受け口
 */
function OperatorCard({
  item,
  selected,
  dragging,
  onOpen,
  onDragStart,
  onDragEnd,
}: OperatorCardProps) {
  const messageCount = item.messageCount ?? 0;
  return (
    <li
      className={`board-card-item ${dragging ? "dragging" : ""}`}
      data-card-id={item.id}
      draggable
      onDragStart={(event) => {
        // Firefox はデータを持たせないとドラッグが始まらない
        event.dataTransfer?.setData("text/plain", item.id);
        if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
        onDragStart();
      }}
      onDragEnd={onDragEnd}
    >
      <button
        type="button"
        className={`board-card op-card ${item.read ? "" : "unread"} ${selected ? "selected" : ""}`}
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
        </span>
        <span className="op-summary">{item.summary}</span>
        <span className="op-row-top op-card-foot">
          <time className="op-time" dateTime={item.filedAt}>
            {formatFiledAt(item.filedAt)}
          </time>
          {messageCount > 0 && (
            <span>{OP_TEXT.messageCount(messageCount)}</span>
          )}
        </span>
      </button>
    </li>
  );
}

/**
 * オペレータ画面。担当者へ送られた問い合わせを、対応状況ごとの列（未対応・対応中・完了）に
 * カードとして並べるボード。カードをドラッグすると対応状況が変わり、押すと右に詳細のパネルが開く。
 * 新しい問い合わせは通知ストリームで届き、「未対応」の列に足される。
 * @param props 購読関数の差し替え口と、URL との受け渡し
 */
export function OperatorApp({
  subscribe = subscribeOperatorStream,
  routeId,
  onNavigate,
}: OperatorAppProps) {
  const [state, dispatch] = useReducer(operatorReducer, initialOperatorState);
  const { items, onlyUnread, selectedId, detail, detailLoading, banner } =
    state;
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [acting, setActing] = useState(false);
  // 操作に失敗した問い合わせの id（別の問い合わせを開いたら出さない）
  const [failedId, setFailedId] = useState<string | null>(null);
  // ドラッグ中の問い合わせの id と、いま重なっている列
  const [dragId, setDragId] = useState<string | null>(null);
  const [overColumn, setOverColumn] = useState<HandlingStatus | null>(null);
  // ドラッグでの対応状況の変更に失敗したか
  const [moveFailed, setMoveFailed] = useState(false);
  // 読み上げ環境に伝える、対応状況が変わったことの文
  const [announcement, setAnnouncement] = useState("");
  const boardRef = useRef<HTMLElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  // 詳細のパネルを開いていた問い合わせ（閉じたときにそのカードへフォーカスを戻す）
  const openerId = useRef<string | null>(null);
  useEffect(() => {
    // 取得に失敗したら、実行記録も開発用のリンクも出さずに続行する
    api
      .getConfig()
      .then(setConfig)
      .catch(() => {});
  }, []);
  const unread = unreadCountOf(items);
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);
  const selectedRef = useRef<string | null>(null);

  // 古い応答が、新しい応答の一覧を上書きしないようにする
  const listRequest = useRef(0);
  const loadList = useCallback(() => {
    const request = ++listRequest.current;
    api
      .getOperatorList()
      .then((list) => {
        if (request === listRequest.current) {
          dispatch({ type: "listLoaded", items: list.items });
        }
      })
      .catch(() => {
        if (request === listRequest.current) dispatch({ type: "listFailed" });
      });
  }, []);

  useEffect(() => {
    loadList();
  }, [loadList]);

  /**
   * 開いている詳細を取り直す。本人の追記などで未読に戻った件は、読んでいる最中なので既読にして受け取る。
   * @param id 問い合わせの id
   * @param read 通知の時点で既読か
   */
  const refreshDetail = useCallback(async (id: string, read: boolean) => {
    try {
      const refreshed = read
        ? await api.getOperatorDetail(id)
        : await api.markRead(id);
      if (selectedRef.current === id) {
        dispatch({ type: "detailLoaded", detail: refreshed });
      }
    } catch {
      // 取り直せなくても、いま見えている内容はそのまま残す
    }
  }, []);

  useEffect(
    () =>
      subscribe({
        onFiled: (item) => dispatch({ type: "filed", item }),
        onUpdated: (item) => {
          dispatch({ type: "updated", item });
          if (selectedRef.current === item.id) {
            void refreshDetail(item.id, item.read);
          }
        },
        // 切断中に届いた分を取りこぼさないよう、再接続後に取り直す
        onReconnected: loadList,
      }),
    [subscribe, loadList, refreshDetail],
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

  // URL（直接開いた場合、戻る・進む、カードでの選択）に合わせて詳細を開閉する
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

  // パネルを開いたらパネルの先頭へ、閉じたら開いていたカードへフォーカスを移す
  useEffect(() => {
    if (selectedId !== null) {
      openerId.current = selectedId;
      panelRef.current?.focus();
      return;
    }
    const closed = openerId.current;
    openerId.current = null;
    if (closed === null || routeId !== null) return;
    const card = Array.from(
      boardRef.current?.querySelectorAll<HTMLElement>("[data-card-id]") ?? [],
    ).find((element) => element.dataset.cardId === closed);
    card?.querySelector("button")?.focus();
  }, [selectedId, routeId]);

  // Esc でパネルを閉じる
  useEffect(() => {
    if (selectedId === null) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) onNavigate(null);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [selectedId, onNavigate]);

  /**
   * 詳細に対する操作（状況の切り替え、返信）を実行し、返ってきた全内容で詳細を置き換える。
   * @param id 操作する問い合わせの id
   * @param operation 実行する API 呼び出し
   * @returns 成功したか
   */
  const act = useCallback(
    async (id: string, operation: () => Promise<OperatorDetail>) => {
      setActing(true);
      setFailedId(null);
      try {
        const updated = await operation();
        if (selectedRef.current === id) {
          dispatch({ type: "detailLoaded", detail: updated });
        }
        return true;
      } catch {
        setFailedId(id);
        return false;
      } finally {
        setActing(false);
      }
    },
    [],
  );

  /**
   * カードを別の列へ移す（ドラッグ）。画面にはすぐ反映し、API が失敗したら元の列に戻して伝える。
   * 同じ列への移動では API を呼ばない。
   * @param id 問い合わせの id
   * @param status 移し先の対応状況
   */
  const moveItem = useCallback(async (id: string, status: HandlingStatus) => {
    const item = itemsRef.current.find((i) => i.id === id);
    if (item === undefined) return;
    const before = operatorColumnOf(item);
    if (before === status) return;
    setMoveFailed(false);
    dispatch({ type: "statusMoved", id, status });
    try {
      const updated = await api.setHandlingStatus(id, status);
      if (selectedRef.current === id) {
        dispatch({ type: "detailLoaded", detail: updated });
      }
      setAnnouncement(movedText(item.summary, status));
    } catch {
      dispatch({ type: "statusMoved", id, status: before });
      setMoveFailed(true);
    }
  }, []);

  /**
   * 詳細のパネルでの対応状況の切り替え。成功したらカードも列を移り、読み上げにも伝える。
   * @param current 開いている詳細
   * @param status 切り替え先
   */
  const switchStatus = async (
    current: OperatorDetail,
    status: HandlingStatus,
  ) => {
    setMoveFailed(false);
    const ok = await act(current.id, () =>
      api.setHandlingStatus(current.id, status),
    );
    if (ok) {
      const summary =
        itemsRef.current.find((i) => i.id === current.id)?.summary ??
        current.utterance;
      setAnnouncement(movedText(summary, status));
    }
    return ok;
  };

  const visible = useMemo(
    () => visibleItems(items, onlyUnread, selectedId),
    [items, onlyUnread, selectedId],
  );
  const board = useMemo(() => arrangeOperatorBoard(visible), [visible]);
  const draggedColumn = useMemo(() => {
    const dragged = items.find((item) => item.id === dragId);
    return dragged === undefined ? null : operatorColumnOf(dragged);
  }, [items, dragId]);
  const showDevLink = config !== null && (config.mock || config.trace === true);
  const showColumns = state.loaded && !state.failed;

  /**
   * 列がドラッグを受けるための受け口を作る。
   * @param column 列の対応状況
   */
  const dropPropsOf = (column: HandlingStatus) => ({
    onDragOver: (event: DragEvent<HTMLElement>) => {
      if (dragId === null) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
      if (overColumn !== column) setOverColumn(column);
    },
    onDragLeave: (event: DragEvent<HTMLElement>) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
        setOverColumn((current) => (current === column ? null : current));
      }
    },
    onDrop: (event: DragEvent<HTMLElement>) => {
      if (dragId === null) return;
      event.preventDefault();
      const id = dragId;
      setDragId(null);
      setOverColumn(null);
      void moveItem(id, column);
    },
  });

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
        <section
          className="op-board"
          aria-label={OP_TEXT.listLabel}
          ref={boardRef}
        >
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
          {state.failed && (
            <p className="op-muted" role="alert">
              {OP_TEXT.loadFailed}
            </p>
          )}
          {moveFailed && (
            <p className="op-notfound" role="alert">
              {OP_TEXT.moveFailed}
            </p>
          )}
          {!state.loaded && <p className="op-muted">{OP_TEXT.loading}</p>}
          {showColumns && (
            <div className="board-columns op-columns">
              {OPERATOR_COLUMNS.map((column) => (
                <BoardColumn
                  key={column}
                  columnId={column}
                  title={HANDLING_LABELS[column]}
                  count={board[column].length}
                  dropState={
                    dragId === null || column === draggedColumn
                      ? undefined
                      : column === overColumn
                        ? "over"
                        : "droppable"
                  }
                  dropProps={dropPropsOf(column)}
                >
                  {board[column].map((item) => (
                    <OperatorCard
                      key={item.id}
                      item={item}
                      selected={item.id === selectedId}
                      dragging={item.id === dragId}
                      onOpen={() => onNavigate(item.id)}
                      onDragStart={() => {
                        setMoveFailed(false);
                        setDragId(item.id);
                      }}
                      onDragEnd={() => {
                        setDragId(null);
                        setOverColumn(null);
                      }}
                    />
                  ))}
                </BoardColumn>
              ))}
            </div>
          )}
        </section>
        {selectedId !== null && (
          <section
            className="op-panel"
            aria-label={OP_TEXT.detailLabel}
            tabIndex={-1}
            ref={panelRef}
          >
            <button
              type="button"
              className="link op-close"
              onClick={() => onNavigate(null)}
            >
              {OP_TEXT.close}
            </button>
            {detailLoading && <p className="op-muted">{OP_TEXT.loading}</p>}
            {state.detailFailed && (
              <p className="op-muted" role="alert">
                {OP_TEXT.loadFailed}
              </p>
            )}
            {detail !== null && (
              <DetailView
                detail={detail}
                trace={config?.trace === true}
                busy={acting}
                failed={failedId === detail.id}
                onStatus={(status) => switchStatus(detail, status)}
                onReply={(body) =>
                  act(detail.id, () => api.postReply(detail.id, body))
                }
              />
            )}
          </section>
        )}
      </div>
      {showDevLink && (
        <DevLink to={NEW_INQUIRY_PATH}>{USER_TEXT.devToAsker}</DevLink>
      )}
      <div className="visually-hidden" aria-live="polite">
        {announcement}
      </div>
    </div>
  );
}
