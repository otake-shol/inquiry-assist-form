import { createContext, useContext } from "react";
import type { Language } from "./language.ts";
import { MESSAGES, type Messages } from "./messages.ts";

/** 画面が受け取る、言語まわりの値と操作。 */
export interface I18nValue {
  /** いま表示している言語 */
  language: Language;
  /** いまの言語の文言 */
  messages: Messages;
  /** 使える言語（API の言語と、画面が文言を持つ言語の両方に含まれるもの） */
  languages: Language[];
  /** 言語の切り替えを出すか（使える言語が2つ以上で、API の言語が分かっているとき） */
  canSwitch: boolean;
  /** 言語を選ぶ。選んだ言語はブラウザに保存する */
  setLanguage: (language: Language) => void;
  /** `GET /api/config` の `languages` を知らせる（使える言語が決まる） */
  reportApiLanguages: (languages: readonly string[] | null) => void;
}

/** 提供元の外で使うときの既定。日本語で、切り替えは出さない。 */
const DEFAULT_VALUE: I18nValue = {
  language: "ja",
  messages: MESSAGES.ja,
  languages: ["ja"],
  canSwitch: false,
  setLanguage: () => {},
  reportApiLanguages: () => {},
};

export const I18nContext = createContext<I18nValue>(DEFAULT_VALUE);

/** いまの言語と文言、切り替えの操作を受け取る。 */
export function useI18n(): I18nValue {
  return useContext(I18nContext);
}

/** いまの言語の文言を受け取る。 */
export function useMessages(): Messages {
  return useContext(I18nContext).messages;
}
