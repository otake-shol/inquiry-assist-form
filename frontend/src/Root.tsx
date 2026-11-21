import { useCallback, useEffect, useState } from "react";
import App from "./App.tsx";
import type {
  SubscribeAnswerStream,
  SubscribeOperatorStream,
} from "./api/stream.ts";
import { OperatorApp } from "./operator/OperatorApp.tsx";
import { operatorPathOf, parseOperatorPath } from "./path.ts";

/** Root の props。 */
interface RootProps {
  subscribeAnswers?: SubscribeAnswerStream;
  subscribeOperator?: SubscribeOperatorStream;
}

/**
 * パスで本人向けの画面とオペレータ画面を切り替える。ルーターのライブラリは使わず、
 * `location.pathname` と History API（pushState / popstate）だけで見る。
 * オペレータ画面で問い合わせを選ぶと URL が `/operator/{id}` に変わる。
 * @param props 購読関数の差し替え口
 */
export default function Root({
  subscribeAnswers,
  subscribeOperator,
}: RootProps) {
  const [pathname, setPathname] = useState(() => window.location.pathname);
  useEffect(() => {
    const onPop = () => setPathname(window.location.pathname);
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const navigate = useCallback((id: string | null) => {
    const path = operatorPathOf(id);
    if (path !== window.location.pathname) {
      window.history.pushState({}, "", path);
    }
    setPathname(path);
  }, []);

  const route = parseOperatorPath(pathname);
  return route.operator ? (
    <OperatorApp
      subscribe={subscribeOperator}
      routeId={route.id}
      onNavigate={navigate}
    />
  ) : (
    <App subscribe={subscribeAnswers} />
  );
}
