import { en } from "./en.ts";
import { ja, type Messages } from "./ja.ts";
import type { Language } from "./language.ts";
import { overrides } from "./overrides.ts";

export type { Messages } from "./ja.ts";

/**
 * 辞書の一部だけを書ける型。辞書と同じ入れ子の形で、書いた鍵だけを持つ。辞書にない鍵や、型の違う値は書けない。
 * 関数と配列は、そのまま（同じ型の値で、まるごと置き換える）。
 */
export type DeepPartial<T> = T extends (...args: never[]) => unknown
  ? T
  : T extends readonly unknown[]
    ? T
    : T extends object
      ? { [K in keyof T]?: DeepPartial<T[K]> }
      : T;

/** 言語ごとの文言の上書き（`overrides.ts`）。 */
export type MessagesOverrides = Record<Language, DeepPartial<Messages>>;

/**
 * 値が、入れ子をたどれる普通のオブジェクトか（配列と関数は、まるごと置き換える値として扱う）。
 * @param value 調べる値
 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * 辞書の上に、上書きを重ねる。入れ子をたどり、上書きに書かれた鍵だけを置き換える。
 * 元の辞書は変えず、新しい辞書を返す。書かれていない鍵は元のまま。
 * @param base 元の辞書（またはその一部）
 * @param override 上書き（空、または省略なら、元と同じ内容）
 */
export function applyOverrides<T>(
  base: T,
  override: DeepPartial<T> | undefined,
): T {
  if (override === undefined) return base;
  if (!isPlainObject(base) || !isPlainObject(override)) {
    return override as T;
  }
  const merged: Record<string, unknown> = { ...base };
  for (const [key, value] of Object.entries(override)) {
    if (value === undefined) continue;
    merged[key] = applyOverrides(base[key], value as never);
  }
  return merged as T;
}

/**
 * 言語ごとの辞書を作る。基になる辞書（`ja.ts`、`en.ts`）に、上書きを重ねる。
 * @param extra 上書き（既定は `overrides.ts`。テストでは差し込める）
 */
export function buildMessages(
  extra: MessagesOverrides = overrides,
): Record<Language, Messages> {
  return {
    ja: applyOverrides<Messages>(ja, extra.ja),
    en: applyOverrides<Messages>(en, extra.en),
  };
}

/** 言語ごとの文言の辞書（上書きを重ねたもの）。 */
export const MESSAGES: Record<Language, Messages> = buildMessages();

/**
 * 言語の識別子から、その言語の文言を返す。持たない言語は日本語。
 * @param code 言語の識別子（`ja`、`en`）
 */
export function messagesOf(code: string): Messages {
  return code in MESSAGES ? MESSAGES[code as Language] : MESSAGES.ja;
}

/**
 * 言語の識別子から、その言語自身の名前を返す（「日本語」「English」）。持たない言語は識別子のまま。
 * @param code 言語の識別子
 */
export function languageName(code: string): string {
  return code in MESSAGES ? MESSAGES[code as Language].meta.name : code;
}
