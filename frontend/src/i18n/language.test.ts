import { describe, expect, it } from "vitest";
import {
  availableLanguages,
  resolveInitialLanguage,
  toDictionaryLanguage,
} from "./language.ts";

describe("toDictionaryLanguage", () => {
  it("地域つきの識別子も、基の言語として扱う。画面が文言を持たない言語は null", () => {
    expect(toDictionaryLanguage("en-US")).toBe("en");
    expect(toDictionaryLanguage("ja-JP")).toBe("ja");
    expect(toDictionaryLanguage("EN")).toBe("en");
    expect(toDictionaryLanguage("fr")).toBeNull();
    expect(toDictionaryLanguage("")).toBeNull();
  });
});

describe("resolveInitialLanguage", () => {
  const both = {
    stored: null,
    browser: [] as string[],
    available: ["ja", "en"] as ("ja" | "en")[],
    defaultLanguage: "ja" as const,
  };

  it("保存した言語が使えるなら、ブラウザの言語より優先する", () => {
    expect(
      resolveInitialLanguage({ ...both, stored: "ja", browser: ["en-US"] }),
    ).toBe("ja");
    expect(
      resolveInitialLanguage({ ...both, stored: "en", browser: ["ja-JP"] }),
    ).toBe("en");
  });

  it("使える言語にない保存値は無視する", () => {
    expect(
      resolveInitialLanguage({ ...both, stored: "fr", browser: ["en-US"] }),
    ).toBe("en");
    expect(
      resolveInitialLanguage({
        ...both,
        stored: "en",
        browser: ["ja-JP"],
        available: ["ja"],
      }),
    ).toBe("ja");
  });

  it("ブラウザの言語は、優先する順に見て、使える言語に当てはまる最初のものを採る。en-US は en", () => {
    expect(resolveInitialLanguage({ ...both, browser: ["en-US", "ja"] })).toBe(
      "en",
    );
    expect(
      resolveInitialLanguage({ ...both, browser: ["fr-FR", "ja-JP"] }),
    ).toBe("ja");
    expect(
      resolveInitialLanguage({
        ...both,
        browser: ["en-US"],
        defaultLanguage: "ja",
        available: ["ja"],
      }),
    ).toBe("ja");
  });

  it("どれも当てはまらなければ、既定の言語", () => {
    expect(resolveInitialLanguage({ ...both, browser: ["fr-FR"] })).toBe("ja");
    expect(
      resolveInitialLanguage({
        ...both,
        browser: ["fr-FR"],
        defaultLanguage: "en",
      }),
    ).toBe("en");
    expect(resolveInitialLanguage({ ...both, browser: [] })).toBe("ja");
  });

  it("使える言語が1つなら、保存値やブラウザの言語に関係なくそれ", () => {
    expect(
      resolveInitialLanguage({
        stored: "en",
        browser: ["en-US"],
        available: ["ja"],
        defaultLanguage: "ja",
      }),
    ).toBe("ja");
    expect(
      resolveInitialLanguage({
        stored: "ja",
        browser: ["ja"],
        available: ["en"],
        defaultLanguage: "en",
      }),
    ).toBe("en");
  });
});

describe("availableLanguages", () => {
  it("API の言語と、画面が文言を持つ言語の共通部分を、API の順に返す", () => {
    expect(availableLanguages(["en", "ja"])).toEqual(["en", "ja"]);
    expect(availableLanguages(["ja", "fr"])).toEqual(["ja"]);
    expect(availableLanguages(["ja", "ja", "en-US"])).toEqual(["ja", "en"]);
  });

  it("API の言語が分からないときは、画面が文言を持つ言語すべて。共通部分が空なら日本語だけ", () => {
    expect(availableLanguages(null)).toEqual(["ja", "en"]);
    expect(availableLanguages(["fr"])).toEqual(["ja"]);
  });
});
