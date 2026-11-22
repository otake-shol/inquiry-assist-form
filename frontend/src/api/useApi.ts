import { useMemo } from "react";
import { useI18n } from "../i18n/context.ts";
import { createClient, type ApiClient } from "./client.ts";

/**
 * いまの言語の API の呼び出しを受け取る。言語を切り替えると、別の呼び出しになる
 * （依存に入れた副作用が、言語の切り替えで取得し直しになる）。
 */
export function useApi(): ApiClient {
  const { language } = useI18n();
  return useMemo(() => createClient(language), [language]);
}
