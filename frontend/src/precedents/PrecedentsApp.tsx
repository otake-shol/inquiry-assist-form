import { useEffect, useState } from "react";
import { useApi } from "../api/useApi.ts";
import type { AppConfig, Precedent } from "../api/types.ts";
import { LanguageSwitch } from "../components/LanguageSwitch.tsx";
import { NavLink } from "../components/Navigation.tsx";
import { useI18n } from "../i18n/context.ts";
import { replaceSearch, useSearch } from "../location.ts";
import { DecisionCard } from "../operator/Consultation.tsx";
import {
  OWNER_PATH,
  operatorPathOf,
  parsePrecedentQuery,
  precedentsPathOf,
  type PrecedentQuery,
} from "../path.ts";

/** 取得した先例の一覧と、取得の失敗。 */
interface Loaded {
  items: Precedent[] | null;
  failed: boolean;
}

/**
 * 絞り込みを URL のクエリに書く（履歴は積まない）。
 * @param query 絞り込み
 */
function writeQuery(query: PrecedentQuery): void {
  replaceSearch(precedentsPathOf(query).split("?")[1] ?? "");
}

/**
 * 先例の一覧の画面（`/precedents`）。担当者と仕様の持ち主が、置き場所ごとに先例を見る。
 * 上部の絞り込み（システム、機能、種類）は URL のクエリ（`systemId`、`functionId`、`type`）に持つ。
 * 先例は新しい順。先例ごとに、置き場所、決定の内容、元の問い合わせへのリンク。この画面では直せない。
 */
export function PrecedentsApp() {
  const api = useApi();
  const { messages, reportApiLanguages } = useI18n();
  const { op: OP_TEXT, precedents: TEXT } = messages;
  const search = useSearch();
  const query = parsePrecedentQuery(search);
  const { systemId = "", functionId = "", type = "" } = query;
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [loaded, setLoaded] = useState<Loaded>({
    items: null,
    failed: false,
  });

  useEffect(() => {
    // 取得に失敗したら、絞り込みの選択肢が空のまま続ける。言語を切り替えたら取り直す
    api
      .getConfig()
      .then((value) => {
        reportApiLanguages(value.languages ?? null);
        setConfig(value);
      })
      .catch(() => {});
  }, [api, reportApiLanguages]);

  // 絞り込みか言語が変わったら、取り直す。古い応答は捨てる
  useEffect(() => {
    let current = true;
    api
      .getPrecedents({
        systemId: systemId || undefined,
        functionId: functionId || undefined,
        type: type || undefined,
      })
      .then((list) => {
        if (current) setLoaded({ items: list.items, failed: false });
      })
      .catch(() => {
        if (current) setLoaded({ items: null, failed: true });
      });
    return () => {
      current = false;
    };
  }, [api, systemId, functionId, type]);

  useEffect(() => {
    const previous = document.title;
    document.title = TEXT.title;
    return () => {
      document.title = previous;
    };
  }, [TEXT.title]);

  const systems = config?.systems ?? [];
  const types = config?.types ?? [];
  const functions = systems.find((s) => s.id === systemId)?.functions ?? [];
  const { items, failed } = loaded;
  return (
    <div className="operator precedents">
      <header className="op-head">
        <h1>{TEXT.title}</h1>
        <NavLink to="/operator">{TEXT.toOperator}</NavLink>
        <NavLink to={OWNER_PATH}>{TEXT.toOwner}</NavLink>
        <LanguageSwitch />
      </header>
      <form
        className="precedent-filter"
        role="search"
        aria-label={TEXT.filterLabel}
        onSubmit={(event) => event.preventDefault()}
      >
        <label>
          {TEXT.system}
          <select
            value={systemId}
            onChange={(event) =>
              writeQuery({ systemId: event.target.value, type })
            }
          >
            <option value="">{TEXT.all}</option>
            {systems.map((system) => (
              <option key={system.id} value={system.id}>
                {system.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          {TEXT.function}
          <select
            value={functionId}
            disabled={systemId === ""}
            onChange={(event) =>
              writeQuery({ systemId, functionId: event.target.value, type })
            }
          >
            <option value="">{TEXT.all}</option>
            {functions.map((fn) => (
              <option key={fn.id} value={fn.id}>
                {fn.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          {TEXT.type}
          <select
            value={type}
            onChange={(event) =>
              writeQuery({ systemId, functionId, type: event.target.value })
            }
          >
            <option value="">{TEXT.all}</option>
            {types.map((option) => (
              <option key={option.id} value={option.id}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </form>
      <section className="precedent-list" aria-label={TEXT.listLabel}>
        {failed && (
          <p className="op-muted" role="alert">
            {OP_TEXT.loadFailed}
          </p>
        )}
        {items === null && !failed && (
          <p className="op-muted">{OP_TEXT.loading}</p>
        )}
        {items !== null && items.length === 0 && (
          <p className="board-empty">{TEXT.empty}</p>
        )}
        {items?.map((precedent) => (
          <DecisionCard key={precedent.id} decision={precedent} showPlace>
            <p>
              <NavLink to={operatorPathOf(precedent.inquiryId)}>
                {TEXT.openOriginal}
              </NavLink>
            </p>
          </DecisionCard>
        ))}
      </section>
    </div>
  );
}
