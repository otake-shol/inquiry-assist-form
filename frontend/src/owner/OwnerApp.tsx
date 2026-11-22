import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { ApiError } from "../api/client.ts";
import { useApi } from "../api/useApi.ts";
import type {
  AppConfig,
  DecisionRequest,
  OperatorDetail,
  OwnerConsultationItem,
  SystemOption,
} from "../api/types.ts";
import { DevLinks } from "../components/Navigation.tsx";
import { LanguageSwitch } from "../components/LanguageSwitch.tsx";
import { useI18n, useMessages } from "../i18n/context.ts";
import { openConsultationOf } from "../operator/consultations.ts";
import {
  formatFiledAt,
  opTypeLabel,
  opUrgencyLabel,
} from "../operator/labels.ts";
import {
  ExchangeSection,
  MaterialSections,
  PlacementBadges,
} from "../operator/Material.tsx";
import { NEW_INQUIRY_PATH } from "../path.ts";
import { DecisionForm } from "./DecisionForm.tsx";

/** OwnerApp の props。 */
interface OwnerAppProps {
  /** URL で指定された問い合わせの id（一覧だけなら null） */
  routeId: string | null;
  /** 開く問い合わせを変える（URL を更新する）。一覧に戻るときは null。 */
  onNavigate: (id: string | null) => void;
}

/** 詳細のパネルの状態。 */
interface PanelState {
  detail: OperatorDetail | null;
  loading: boolean;
  failed: boolean;
  /** 指定された問い合わせが無い（または相談したことがない） */
  notFound: boolean;
  /** 決定を記録した直後か（完了の文言を示す） */
  recorded: boolean;
  /** 決めるのに失敗したか */
  decideFailed: boolean;
}

/** 詳細のパネルの状態を変える操作。 */
type PanelAction =
  | { type: "opening" }
  | { type: "loaded"; detail: OperatorDetail }
  | { type: "failed" }
  | { type: "notFound" }
  | { type: "recorded" }
  | { type: "deciding" }
  | { type: "decideFailed" };

/**
 * 詳細のパネルの状態遷移。
 * @param state 現在の状態
 * @param action 操作
 */
function panelReducer(state: PanelState, action: PanelAction): PanelState {
  switch (action.type) {
    case "opening":
      return {
        detail: null,
        loading: true,
        failed: false,
        notFound: false,
        recorded: false,
        decideFailed: false,
      };
    case "loaded":
      return { ...state, detail: action.detail, loading: false };
    case "failed":
      return { ...state, loading: false, failed: true };
    case "notFound":
      return { ...state, loading: false, notFound: true };
    case "recorded":
      return { ...state, recorded: true };
    case "deciding":
      return { ...state, decideFailed: false };
    case "decideFailed":
      return { ...state, decideFailed: true };
  }
}

/** OwnerCard の props。 */
interface OwnerCardProps {
  item: OwnerConsultationItem;
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
  /** 詳細のパネルで開いているか */
  selected: boolean;
  onOpen: () => void;
}

/**
 * 決定を待っている相談のカード1枚。種類、システムと機能、緊急度（通常以外）、相談した文章（2行まで）、
 * 相談した人と日時、相談先の名前。押すとその相談を開く。
 * @param props 1件と、開く操作
 */
function OwnerCard({ item, urgencies, selected, onOpen }: OwnerCardProps) {
  const { op: OP_TEXT, meta } = useMessages();
  return (
    <li className="board-card-item" data-card-id={item.inquiryId}>
      <button
        type="button"
        className={`board-card op-card ${selected ? "selected" : ""}`}
        aria-current={selected ? "true" : undefined}
        onClick={onOpen}
      >
        <span className="op-row-top">
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
        </span>
        <span className="op-summary">{item.question}</span>
        <span className="board-note op-wait">
          {OP_TEXT.consultedNote(OP_TEXT.nameList(item.to))}
        </span>
        <span className="op-row-top op-card-foot">
          <span>{item.askedBy}</span>
          <time className="op-time" dateTime={item.askedAt}>
            {formatFiledAt(item.askedAt, meta.locale)}
          </time>
        </span>
      </button>
    </li>
  );
}

/**
 * 仕様の持ち主の画面（`/owner`、`/owner/{id}`）。左に「決定を待っている相談」の一覧（長く待たせている順）、
 * 右に選んだ相談の詳細（相談、材料、決める）を置く。材料は担当者のパネルの読み取りの部分と同じ部品で、
 * 担当者の操作は出さない。決めると、その相談は一覧から消え、完了の文言を示す。
 * 一覧は開いたときと「更新」を押したときに取得する（即時には届かない）。
 * @param props URL との受け渡し
 */
export function OwnerApp({ routeId, onNavigate }: OwnerAppProps) {
  const api = useApi();
  const { messages, reportApiLanguages } = useI18n();
  const { op: OP_TEXT, owner: OWNER_TEXT, user: USER_TEXT } = messages;
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [items, setItems] = useState<OwnerConsultationItem[] | null>(null);
  const [listFailed, setListFailed] = useState(false);
  const [panel, dispatch] = useReducer(panelReducer, {
    detail: null,
    loading: false,
    failed: false,
    notFound: false,
    recorded: false,
    decideFailed: false,
  });
  const {
    detail,
    loading: detailLoading,
    failed: detailFailed,
    notFound,
    recorded,
    decideFailed: failed,
  } = panel;
  const [acting, setActing] = useState(false);
  const selectedRef = useRef<string | null>(null);

  useEffect(() => {
    // 取得に失敗したら、緊急度の名前も開発用のリンクも出さずに続行する。言語を切り替えたら取り直す
    api
      .getConfig()
      .then((loaded) => {
        reportApiLanguages(loaded.languages ?? null);
        setConfig(loaded);
      })
      .catch(() => {});
  }, [api, reportApiLanguages]);

  // 古い応答が、新しい応答の一覧を上書きしないようにする
  const listRequest = useRef(0);
  const loadList = useCallback(() => {
    const request = ++listRequest.current;
    api
      .getOwnerList()
      .then((list) => {
        if (request !== listRequest.current) return;
        setItems(list.items);
        setListFailed(false);
      })
      .catch(() => {
        if (request === listRequest.current) setListFailed(true);
      });
  }, [api]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  useEffect(() => {
    const previous = document.title;
    document.title = OWNER_TEXT.tabTitle;
    return () => {
      document.title = previous;
    };
  }, [OWNER_TEXT.tabTitle]);

  /**
   * 1件を開く。相談したことのない問い合わせ、形式の崩れた id は「見つからない」として示す。
   * @param id 問い合わせの id
   */
  const open = useCallback(
    async (id: string) => {
      selectedRef.current = id;
      dispatch({ type: "opening" });
      try {
        const loaded = await api.getOwnerDetail(id);
        if (selectedRef.current === id) {
          dispatch({ type: "loaded", detail: loaded });
        }
      } catch (e) {
        if (selectedRef.current !== id) return;
        if (
          e instanceof ApiError &&
          (e.code === "NOT_FOUND" || e.code === "VALIDATION")
        ) {
          selectedRef.current = null;
          dispatch({ type: "notFound" });
        } else {
          dispatch({ type: "failed" });
        }
      }
    },
    [api],
  );

  // URL（直接開いた場合、戻る・進む、カードでの選択）に合わせて詳細を開閉する。言語を切り替えたら取り直す
  const openedFor = useRef<{ id: string | null; api: unknown } | null>(null);
  useEffect(() => {
    const previous = openedFor.current;
    if (previous !== null && previous.id === routeId && previous.api === api) {
      return;
    }
    openedFor.current = { id: routeId, api };
    if (routeId === null) {
      selectedRef.current = null;
      return;
    }
    void open(routeId);
  }, [routeId, api, open]);

  /**
   * 決める。成功したら、その相談を一覧から消し、一覧に戻って完了の文言を示す。
   * @param id 問い合わせの id
   * @param request 決定の内容
   * @returns 成功したか
   */
  const decide = async (
    id: string,
    request: DecisionRequest,
  ): Promise<boolean> => {
    setActing(true);
    dispatch({ type: "deciding" });
    try {
      await api.decide(id, request);
      setItems((current) =>
        current === null
          ? current
          : current.filter((item) => item.inquiryId !== id),
      );
      dispatch({ type: "recorded" });
      onNavigate(null);
      return true;
    } catch {
      dispatch({ type: "decideFailed" });
      return false;
    } finally {
      setActing(false);
    }
  };

  const urgencies = config?.urgencies ?? [];
  const showDevLink = config !== null && (config.mock || config.trace === true);
  const consultation = detail === null ? null : openConsultationOf(detail);
  const selectedId = routeId;

  return (
    <div className="operator owner">
      <header className="op-head">
        <h1>{OWNER_TEXT.title}</h1>
        <LanguageSwitch />
      </header>
      <div
        className={`op-layout ${selectedId !== null && !notFound ? "has-selection" : ""}`}
      >
        <section className="op-board" aria-label={OWNER_TEXT.listTitle}>
          <div className="owner-list-head">
            <h2>{OWNER_TEXT.listTitle}</h2>
            <button type="button" className="small" onClick={loadList}>
              {OWNER_TEXT.refresh}
            </button>
          </div>
          {recorded && (
            <p className="owner-recorded" role="status">
              {OWNER_TEXT.recorded}
            </p>
          )}
          {notFound && (
            <p className="op-notfound" role="alert">
              {OP_TEXT.inquiryNotFound}
            </p>
          )}
          {listFailed && (
            <p className="op-muted" role="alert">
              {OP_TEXT.loadFailed}
            </p>
          )}
          {items === null && !listFailed && (
            <p className="op-muted">{OP_TEXT.loading}</p>
          )}
          {items !== null && items.length === 0 && (
            <p className="board-empty">{OWNER_TEXT.empty}</p>
          )}
          {items !== null && items.length > 0 && (
            <ul className="board-cards owner-cards">
              {items.map((item) => (
                <OwnerCard
                  key={item.consultationId}
                  item={item}
                  urgencies={urgencies}
                  selected={item.inquiryId === selectedId}
                  onOpen={() => onNavigate(item.inquiryId)}
                />
              ))}
            </ul>
          )}
        </section>
        {selectedId !== null && !notFound && (
          <section
            className="op-panel"
            aria-label={OP_TEXT.detailLabel}
            tabIndex={-1}
          >
            <button
              type="button"
              className="link op-close"
              onClick={() => onNavigate(null)}
            >
              {OP_TEXT.close}
            </button>
            {detailLoading && <p className="op-muted">{OP_TEXT.loading}</p>}
            {detailFailed && (
              <p className="op-muted" role="alert">
                {OP_TEXT.loadFailed}
              </p>
            )}
            {detail !== null && (
              <article className="op-article">
                <header className="op-article-head">
                  <PlacementBadges detail={detail} urgencies={urgencies} />
                </header>
                {consultation !== null && (
                  <section className="owner-consultation">
                    <h3>{OWNER_TEXT.consultTitle}</h3>
                    <dl className="op-fields">
                      <dt>{OWNER_TEXT.askedBy}</dt>
                      <dd>{consultation.askedBy}</dd>
                      <dt>{OWNER_TEXT.askedAt}</dt>
                      <dd>
                        <time dateTime={consultation.askedAt}>
                          {formatFiledAt(
                            consultation.askedAt,
                            messages.meta.locale,
                          )}
                        </time>
                      </dd>
                      <dt>{OWNER_TEXT.consultedTo}</dt>
                      <dd>{OP_TEXT.nameList(consultation.to)}</dd>
                      <dt>{OWNER_TEXT.question}</dt>
                      <dd className="op-pre">{consultation.question}</dd>
                    </dl>
                  </section>
                )}
                <h3 className="owner-material-title">
                  {OWNER_TEXT.materialTitle}
                </h3>
                <MaterialSections
                  detail={detail}
                  adviceLanguage={config?.languages?.[0]}
                />
                <ExchangeSection detail={detail} />
                {consultation !== null ? (
                  <DecisionForm
                    key={consultation.id}
                    detail={detail}
                    consultation={consultation}
                    busy={acting}
                    failed={failed}
                    onSubmit={(request) => decide(detail.id, request)}
                  />
                ) : (
                  <p className="op-muted">{OWNER_TEXT.notWaiting}</p>
                )}
              </article>
            )}
          </section>
        )}
      </div>
      {showDevLink && (
        <DevLinks
          links={[
            { to: NEW_INQUIRY_PATH, label: USER_TEXT.devToAsker },
            { to: "/operator", label: USER_TEXT.devToOperator },
          ]}
        />
      )}
    </div>
  );
}
