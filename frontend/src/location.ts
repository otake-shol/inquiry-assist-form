import { useEffect, useState } from "react";

/**
 * 画面の遷移先を履歴に積み、`location.pathname` を読んでいるすべての部品に知らせる。
 * ルーターのライブラリは使わず、History API と `popstate` だけで見る。
 * 同じパスへの遷移では履歴を積まない。
 * @param path 遷移先のパス
 */
export function navigateTo(path: string): void {
  if (path !== window.location.pathname) {
    window.history.pushState({}, "", path);
  }
  window.dispatchEvent(new PopStateEvent("popstate"));
}

/**
 * 現在の `location.pathname`。ブラウザの「戻る」「進む」と `navigateTo` に追従する。
 */
export function usePathname(): string {
  const [pathname, setPathname] = useState(() => window.location.pathname);
  useEffect(() => {
    const onPop = () => setPathname(window.location.pathname);
    window.addEventListener("popstate", onPop);
    // 購読の前に遷移していた場合に備える
    onPop();
    return () => window.removeEventListener("popstate", onPop);
  }, []);
  return pathname;
}

/**
 * 現在の `location.search`（先頭の `?` を含む。なければ空文字）。ブラウザの「戻る」「進む」と
 * `replaceSearch` に追従する。
 */
export function useSearch(): string {
  const [search, setSearch] = useState(() => window.location.search);
  useEffect(() => {
    const onPop = () => setSearch(window.location.search);
    window.addEventListener("popstate", onPop);
    onPop();
    return () => window.removeEventListener("popstate", onPop);
  }, []);
  return search;
}

/**
 * パスはそのままに、クエリだけを置き換える（履歴は積まない）。絞り込みの変更に使う。
 * @param search 新しいクエリ（`?` なし。空なら、クエリを外す）
 */
export function replaceSearch(search: string): void {
  const url = window.location.pathname + (search === "" ? "" : `?${search}`);
  window.history.replaceState({}, "", url);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
