import { en } from "./en.ts";
import { ja, type Messages } from "./ja.ts";
import type { Language } from "./language.ts";

export type { Messages } from "./ja.ts";

/** 言語ごとの文言の辞書。 */
export const MESSAGES: Record<Language, Messages> = { ja, en };

/**
 * 言語の識別子から、その言語の文言を返す。持たない言語は日本語。
 * @param code 言語の識別子（`ja`、`en`）
 */
export function messagesOf(code: string): Messages {
  return code in MESSAGES ? MESSAGES[code as Language] : ja;
}

/**
 * 言語の識別子から、その言語自身の名前を返す（「日本語」「English」）。持たない言語は識別子のまま。
 * @param code 言語の識別子
 */
export function languageName(code: string): string {
  return code in MESSAGES ? MESSAGES[code as Language].meta.name : code;
}
