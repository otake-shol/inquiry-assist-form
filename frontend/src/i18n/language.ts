/** 画面が文言を持つ言語。 */
export type Language = "ja" | "en";

/** 画面が文言を持つ言語（切り替えの並び順）。 */
export const DICTIONARY_LANGUAGES: Language[] = ["ja", "en"];

/** 選んだ言語をブラウザに保存するときのキー。 */
export const LANGUAGE_STORAGE_KEY = "inquiry-assist.language";

/**
 * 言語の識別子を、画面が文言を持つ言語に直す。`en-US` は `en` として扱う。持たない言語は null。
 * @param tag 言語の識別子（`ja`、`en-US` など）
 */
export function toDictionaryLanguage(tag: string): Language | null {
  const base = tag.trim().toLowerCase().split("-")[0];
  return DICTIONARY_LANGUAGES.find((language) => language === base) ?? null;
}

/** 最初の言語を決めるための材料。 */
export interface LanguageChoice {
  /** ブラウザに保存した言語（無ければ null。使えない値でも、そのまま渡してよい） */
  stored: string | null;
  /** `navigator.languages`（優先する順） */
  browser: readonly string[];
  /** 使える言語（API の言語と、画面が文言を持つ言語の両方に含まれるもの） */
  available: Language[];
  /** 既定の言語（API の `languages` の先頭） */
  defaultLanguage: Language;
}

/**
 * 最初に表示する言語を決める純粋関数。
 * 使える言語が1つならそれ。そうでなければ、保存した言語（使えるときだけ）、ブラウザの言語（順に、
 * 使える言語に当てはまる最初のもの）、既定の言語の順に採る。
 * @param choice 保存した言語、ブラウザの言語、使える言語、既定の言語
 */
export function resolveInitialLanguage(choice: LanguageChoice): Language {
  const { stored, browser, available, defaultLanguage } = choice;
  if (available.length === 1) return available[0];
  const storedLanguage = stored === null ? null : toDictionaryLanguage(stored);
  if (storedLanguage !== null && available.includes(storedLanguage)) {
    return storedLanguage;
  }
  for (const tag of browser) {
    const language = toDictionaryLanguage(tag);
    if (language !== null && available.includes(language)) return language;
  }
  return available.includes(defaultLanguage)
    ? defaultLanguage
    : (available[0] ?? defaultLanguage);
}

/**
 * 使える言語を求める。API の `languages` が分かっていれば、画面が文言を持つ言語との共通部分
 * （API の順）。分かっていない（取得前、取得に失敗）ときは、画面が文言を持つ言語すべて。
 * @param apiLanguages `GET /api/config` の `languages`（分からなければ null）
 */
export function availableLanguages(
  apiLanguages: readonly string[] | null,
): Language[] {
  if (apiLanguages === null) return DICTIONARY_LANGUAGES;
  const common = apiLanguages
    .map(toDictionaryLanguage)
    .filter(
      (language, index, all): language is Language =>
        language !== null && all.indexOf(language) === index,
    );
  return common.length === 0 ? DICTIONARY_LANGUAGES.slice(0, 1) : common;
}

/**
 * ブラウザに保存した言語を読む。読めない（保存できない環境）ときは null。
 */
export function readStoredLanguage(): string | null {
  try {
    return window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * 選んだ言語をブラウザに保存する。保存できない環境では何もしない。
 * @param language 選んだ言語
 */
export function storeLanguage(language: Language): void {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, language);
  } catch {
    // 保存できなくても、この画面の間は選んだ言語で表示できる
  }
}
