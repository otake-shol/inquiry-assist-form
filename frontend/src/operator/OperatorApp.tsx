import {
  type DragEvent,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from "react";
import { ApiError } from "../api/client.ts";
import { useApi } from "../api/useApi.ts";
import {
  subscribeOperatorStream,
  type SubscribeOperatorStream,
} from "../api/stream.ts";
import type {
  AppConfig,
  OperatorDetail,
  OperatorItem,
  SystemOption,
  Translation,
} from "../api/types.ts";
import { BoardColumn } from "../components/BoardColumn.tsx";
import { DevLink } from "../components/Navigation.tsx";
import { NEW_INQUIRY_PATH } from "../path.ts";
import { useI18n, useMessages } from "../i18n/context.ts";
import { LanguageSwitch } from "../components/LanguageSwitch.tsx";
import { DetailView, type FocusRequest } from "./DetailView.tsx";
import { formatFiledAt, opTypeLabel, opUrgencyLabel } from "./labels.ts";
import {
  OPERATOR_COLUMNS,
  arrangeOperatorBoard,
  canDropOnColumn,
  cardNoteOf,
  cardPhaseOf,
  dropIntentOf,
  initialOperatorState,
  movedMessageOf,
  operatorColumnOf,
  operatorReducer,
  tabTitleOf,
  unreadCountOf,
  visibleItems,
  type OperatorColumn,
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
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
  /** 詳細のパネルで開いているか */
  selected: boolean;
  /** いま掴まれているか */
  dragging: boolean;
  onOpen: () => void;
  onDragStart: () => void;
  onDragEnd: () => void;
}

/**
 * ボードのカード1枚。押すと詳細のパネルを開く。ドラッグして別の列に落とすと、状態は変えずに
 * パネルを開き、対応する入力欄を開く。カードには区間、待っていること（本人の番の列）、
 * 終わり方（終わりの列）を添える。
 * 中身は押せる部分（ボタン）だけにし、ドラッグはその外側の `li` で受ける。
 * 未読は太字と印で示し、読み上げにも「未読」を含める。
 * @param props 1件と、操作の受け口
 */
function OperatorCard({
  item,
  urgencies,
  selected,
  dragging,
  onOpen,
  onDragStart,
  onDragEnd,
}: OperatorCardProps) {
  const messageCount = item.messageCount;
  const { op: OP_TEXT, meta } = useMessages();
  const phase = cardPhaseOf(item, OP_TEXT);
  const note = cardNoteOf(item, OP_TEXT);
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
          <span className="op-badge">{opTypeLabel(item.type, OP_TEXT)}</span>
          <span className="op-system">
            {item.system?.label ?? OP_TEXT.systemUnknown}
            {item.function !== null && ` / ${item.function.label}`}
          </span>
          {item.urgency !== "NORMAL" && (
            <span className="op-badge warn strong-warn">
              {opUrgencyLabel(item.urgency, urgencies, OP_TEXT)}
            </span>
          )}
          {item.sentImmediately && (
            <span className="op-badge strong">{OP_TEXT.sentImmediately}</span>
          )}
          {phase !== null && (
            <span className="op-badge quiet">
              {OP_TEXT.phaseLabel} {phase}
            </span>
          )}
        </span>
        <span className="op-summary" lang={item.language}>
          {item.summary}
        </span>
        {note !== null && <span className="board-note op-wait">{note}</span>}
        <span className="op-row-top op-card-foot">
          <time className="op-time" dateTime={item.filedAt}>
            {formatFiledAt(item.filedAt, meta.locale)}
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
 * オペレータ画面。担当者に渡したことのある問い合わせを、誰の番かで分けた列（担当者の番・本人の番・終わり）に
 * カードとして並べるボード。カードを押すと右に詳細のパネルが開く。ドラッグして「本人の番」「終わり」に
 * 落とすと、状態は変えずにパネルを開き、「本人に送る」「見送る」の入力欄にフォーカスを移す。
 * 新しい問い合わせは通知ストリームで届き、「担当者の番」の列に足される。
 * @param props 購読関数の差し替え口と、URL との受け渡し
 */
export function OperatorApp({
  subscribe = subscribeOperatorStream,
  routeId,
  onNavigate,
}: OperatorAppProps) {
  const api = useApi();
  const { language, messages, reportApiLanguages } = useI18n();
  const { op: OP_TEXT, user: USER_TEXT } = messages;
  const [state, dispatch] = useReducer(operatorReducer, initialOperatorState);
  const { items, onlyUnread, selectedId, detail, detailLoading, banner } =
    state;
  const [config, setConfig] = useState<AppConfig | null>(null);
  // 担当者に添える訳（出している間だけ。どの問い合わせの訳かを持つ）
  const [translation, setTranslation] = useState<{
    id: string;
    data: Translation;
  } | null>(null);
  const [translating, setTranslating] = useState(false);
  const [acting, setActing] = useState(false);
  // 操作に失敗した問い合わせの id（別の問い合わせを開いたら出さない）
  const [failedId, setFailedId] = useState<string | null>(null);
  // ドラッグ中の問い合わせの id と、いま重なっている列
  const [dragId, setDragId] = useState<string | null>(null);
  const [overColumn, setOverColumn] = useState<OperatorColumn | null>(null);
  // ドラッグで落とされたときの、パネルの入力欄へのフォーカスの要求
  const [focusRequest, setFocusRequest] = useState<FocusRequest | null>(null);
  const focusCounter = useRef(0);
  // 読み上げ環境に伝える、列が変わったことと、入力欄を開いたことの文
  const [announcement, setAnnouncement] = useState("");
  const boardRef = useRef<HTMLElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  // 詳細のパネルを開いていた問い合わせ（閉じたときにそのカードへフォーカスを戻す）
  const openerId = useRef<string | null>(null);
  useEffect(() => {
    // 取得に失敗したら、実行記録も開発用のリンクも出さずに続行する。言語を切り替えたら取り直す
    api
      .getConfig()
      .then((loaded) => {
        reportApiLanguages(loaded.languages ?? null);
        setConfig(loaded);
      })
      .catch(() => {});
  }, [api, reportApiLanguages]);
  const unread = unreadCountOf(items);
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);
  const selectedRef = useRef<string | null>(null);
  const translationRef = useRef(translation);
  const apiRef = useRef(api);
  useEffect(() => {
    translationRef.current = translation;
    apiRef.current = api;
  });
  // 別の件を開いたとき、画面の言語を切り替えたときは、訳を隠す（訳す先、対象が変わるため）
  const translationKey = `${selectedId ?? ""}|${language}`;
  const [shownKey, setShownKey] = useState(translationKey);
  if (shownKey !== translationKey) {
    setShownKey(translationKey);
    setTranslation(null);
  }

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
  }, [api]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  /**
   * 開いている詳細を取り直す。本人の追記などで未読に戻った件は、読んでいる最中なので既読にして受け取る。
   * @param id 問い合わせの id
   * @param read 通知の時点で既読か
   */
  const refreshDetail = useCallback(
    async (id: string, read: boolean) => {
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
    },
    [api],
  );

  // 言語を切り替えたら、開いている詳細も取り直す（一覧は loadList の取り直しで揃う）
  const previousApi = useRef(api);
  useEffect(() => {
    if (previousApi.current === api) return;
    previousApi.current = api;
    const id = selectedRef.current;
    if (id !== null) void refreshDetail(id, true);
  }, [api, refreshDetail]);

  /**
   * 本人の言語で書かれた文章の訳を取り、出す。失敗は、既存のエラーの出し方（原文は見えたまま）。
   * 取っている間に、別の件を開いた、画面の言語を切り替えた場合は、結果を捨てる。
   * @param id 問い合わせの id
   */
  const loadTranslation = useCallback(
    async (id: string) => {
      setTranslating(true);
      setFailedId(null);
      try {
        const data = await api.translate(id);
        if (selectedRef.current === id && apiRef.current === api) {
          setTranslation({ id, data });
        }
      } catch {
        setFailedId(id);
      } finally {
        setTranslating(false);
      }
    },
    [api],
  );

  useEffect(
    () =>
      subscribe(
        {
          onFiled: (item) => dispatch({ type: "filed", item }),
          onUpdated: (item) => {
            dispatch({ type: "updated", item });
            if (selectedRef.current === item.id) {
              void refreshDetail(item.id, item.read);
              // 訳を出している件は、増えた発言の分まで訳を取り直す
              if (translationRef.current?.id === item.id) {
                void loadTranslation(item.id);
              }
            }
          },
          // 切断中に届いた分を取りこぼさないよう、再接続後に取り直す
          onReconnected: loadList,
        },
        language,
      ),
    [subscribe, loadList, refreshDetail, loadTranslation, language],
  );

  useEffect(() => {
    const previous = document.title;
    document.title = tabTitleOf(unread, OP_TEXT);
    return () => {
      document.title = previous;
    };
  }, [unread, OP_TEXT]);

  /**
   * 1件を開く。未読なら既読にしながら全内容を受け取る。
   * @param id 問い合わせの id
   */
  const open = useCallback(
    async (id: string) => {
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
          e instanceof ApiError &&
          (e.code === "NOT_FOUND" || e.code === "VALIDATION");
        dispatch(
          missing ? { type: "detailNotFound" } : { type: "detailFailed" },
        );
      }
    },
    [api],
  );

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
   * 詳細に対する操作を実行し、返ってきた全内容で詳細を置き換える。
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
   * 詳細に対する操作（区間の切り替え、置き直し、本人に送る、見送る）を実行する。
   * 返ってきた全内容で詳細を置き換える。
   * @param id 操作する問い合わせの id
   * @param operation 実行する API 呼び出し
   */
  const operate = (
    id: string,
    operation: () => Promise<OperatorDetail>,
  ): Promise<boolean> => act(id, operation);

  /** フォーカスを移し終えたので、要求を消す。 */
  const handleFocusHandled = useCallback(() => setFocusRequest(null), []);

  // 列が変わった件を、読み上げ環境にも伝える（ストリームの更新も、自分の操作も同じ）。
  // 一覧が変わった描画のうちに、前の一覧と比べる
  const [previousItems, setPreviousItems] = useState(items);
  if (items !== previousItems) {
    setPreviousItems(items);
    const message = movedMessageOf(previousItems, items, OP_TEXT);
    if (message !== "") setAnnouncement(message);
  }

  const visible = useMemo(
    () => visibleItems(items, onlyUnread, selectedId),
    [items, onlyUnread, selectedId],
  );
  const board = useMemo(() => arrangeOperatorBoard(visible), [visible]);
  const draggedColumn = useMemo(() => {
    const dragged = items.find((item) => item.id === dragId);
    return dragged === undefined ? null : operatorColumnOf(dragged);
  }, [items, dragId]);
  /** 掴んでいるカードを、この列に落とせるか */
  const canDropOn = (column: OperatorColumn) =>
    draggedColumn !== null && canDropOnColumn(draggedColumn, column);
  const urgencies = config?.urgencies ?? [];
  const types = config?.types ?? [];
  const systems = config?.systems ?? [];
  const showDevLink = config !== null && (config.mock || config.trace === true);
  const showColumns = state.loaded && !state.failed;

  /**
   * 列がドラッグを受けるための受け口を作る。落とせるのは「担当者の番」から「本人の番」「終わり」へだけで、
   * 落としても状態は変えない。パネルを開き、対応する入力欄（「本人に送る」「見送る」）にフォーカスを移す。
   * @param column 列
   */
  const dropPropsOf = (column: OperatorColumn) => ({
    onDragOver: (event: DragEvent<HTMLElement>) => {
      if (dragId === null || !canDropOn(column)) return;
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
      if (dragId === null || !canDropOn(column)) return;
      event.preventDefault();
      const id = dragId;
      setDragId(null);
      setOverColumn(null);
      const intent = dropIntentOf(column);
      if (intent === null) return;
      focusCounter.current += 1;
      setFocusRequest({ id, target: intent, token: focusCounter.current });
      onNavigate(id);
      setAnnouncement(
        intent === "send" ? OP_TEXT.dropSend : OP_TEXT.dropDecline,
      );
    },
  });

  return (
    <div className="operator">
      <header className="op-head">
        <h1>{OP_TEXT.title}</h1>
        {unread > 0 && (
          <span className="op-unread" aria-label={OP_TEXT.unreadCount(unread)}>
            {OP_TEXT.unread} {unread}
          </span>
        )}
        <LanguageSwitch />
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
          {!state.loaded && <p className="op-muted">{OP_TEXT.loading}</p>}
          {showColumns && (
            <div className="board-columns op-columns">
              {OPERATOR_COLUMNS.map((column) => (
                <BoardColumn
                  key={column}
                  columnId={column}
                  title={OP_TEXT.columns[column]}
                  count={board[column].length}
                  dropState={
                    dragId === null || !canDropOn(column)
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
                      urgencies={urgencies}
                      selected={item.id === selectedId}
                      dragging={item.id === dragId}
                      onOpen={() => onNavigate(item.id)}
                      onDragStart={() => setDragId(item.id)}
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
                key={detail.id}
                detail={detail}
                urgencies={urgencies}
                types={types}
                systems={systems}
                trace={config?.trace === true}
                busy={acting}
                failed={failedId === detail.id}
                focusRequest={focusRequest}
                onFocusHandled={handleFocusHandled}
                onPhase={(phase) =>
                  operate(detail.id, () => api.setPhase(detail.id, phase))
                }
                onPlacement={(placement) =>
                  operate(detail.id, () =>
                    api.setPlacement(detail.id, placement),
                  )
                }
                onReply={(kind, body) =>
                  operate(detail.id, () => api.postReply(detail.id, kind, body))
                }
                adviceLanguage={config?.languages?.[0]}
                translation={
                  translation?.id === detail.id ? translation.data : null
                }
                translating={translating}
                onToggleTranslation={() => {
                  if (translation?.id === detail.id) setTranslation(null);
                  else void loadTranslation(detail.id);
                }}
                onDraft={async (kind, note) => {
                  setFailedId(null);
                  try {
                    return await api.draft(
                      detail.id,
                      kind,
                      note === "" ? undefined : note,
                    );
                  } catch {
                    setFailedId(detail.id);
                    return null;
                  }
                }}
                onInvestigate={() =>
                  operate(detail.id, () => api.investigate(detail.id))
                }
                onDecline={(kind, reason) =>
                  operate(detail.id, () =>
                    api.declineInquiry(detail.id, kind, reason),
                  )
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
