import App from "./App.tsx";
import type {
  SubscribeAnswerStream,
  SubscribeOperatorStream,
} from "./api/stream.ts";
import { I18nProvider } from "./i18n/I18nProvider.tsx";
import { navigateTo, usePathname } from "./location.ts";
import { OperatorApp } from "./operator/OperatorApp.tsx";
import { operatorPathOf, parseRoute } from "./path.ts";

/** Root の props。 */
interface RootProps {
  subscribeAnswers?: SubscribeAnswerStream;
  subscribeOperator?: SubscribeOperatorStream;
}

/**
 * パスで本人向けの画面と担当者の画面を切り替える。ルーターのライブラリは使わず、
 * `location.pathname` と History API（pushState / popstate）だけで見る。
 * 担当者の画面で問い合わせを選ぶと URL が `/operator/{id}` に変わる。
 * 本人向けの画面（`/`、`/inquiries`、`/inquiries/{id}`）の切り替えは `App` が受け持つ。
 * どちらの画面も、言語の提供元（`I18nProvider`）の中に置く。
 * @param props 購読関数の差し替え口
 */
export default function Root({
  subscribeAnswers,
  subscribeOperator,
}: RootProps) {
  const route = parseRoute(usePathname());
  return (
    <I18nProvider>
      {route.kind === "operator" ? (
        <OperatorApp
          subscribe={subscribeOperator}
          routeId={route.id}
          onNavigate={(id) => navigateTo(operatorPathOf(id))}
        />
      ) : (
        <App subscribe={subscribeAnswers} />
      )}
    </I18nProvider>
  );
}
