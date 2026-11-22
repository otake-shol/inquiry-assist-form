import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { I18nContext, type I18nValue } from "./context.ts";
import {
  availableLanguages,
  readStoredLanguage,
  resolveInitialLanguage,
  storeLanguage,
  type Language,
} from "./language.ts";
import { MESSAGES } from "./messages.ts";

/** ブラウザの言語（`navigator.languages`）。無ければ空。 */
function browserLanguages(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  return navigator.languages?.length
    ? navigator.languages
    : [navigator.language];
}

/**
 * 言語の提供元。最初の言語は、保存した言語、ブラウザの言語、既定の言語の順に決める。
 * 選んだ言語はブラウザに保存し、`<html lang>` を合わせる。使える言語は、`GET /api/config` の
 * `languages`（`reportApiLanguages` で知らされる）と、画面が文言を持つ言語の共通部分。
 * @param props 子の部品と、最初に選んでいた言語（テスト用）
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [selected, setSelected] = useState<string | null>(readStoredLanguage);
  const [apiLanguages, setApiLanguages] = useState<readonly string[] | null>(
    null,
  );
  const languages = useMemo(
    () => availableLanguages(apiLanguages),
    [apiLanguages],
  );
  const language = resolveInitialLanguage({
    stored: selected,
    browser: browserLanguages(),
    available: languages,
    defaultLanguage: languages[0],
  });

  const setLanguage = useCallback((next: Language) => {
    setSelected(next);
    storeLanguage(next);
  }, []);

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const value = useMemo<I18nValue>(
    () => ({
      language,
      messages: MESSAGES[language],
      languages,
      canSwitch: apiLanguages !== null && languages.length > 1,
      setLanguage,
      reportApiLanguages: setApiLanguages,
    }),
    [language, languages, apiLanguages, setLanguage],
  );
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
