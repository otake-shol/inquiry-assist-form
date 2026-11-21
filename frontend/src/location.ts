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
