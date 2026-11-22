import { describe, expect, it } from "vitest";
import { en } from "./en.ts";
import { ja } from "./ja.ts";
import {
  applyOverrides,
  buildMessages,
  MESSAGES,
  type MessagesOverrides,
} from "./messages.ts";
import { overrides } from "./overrides.ts";

describe("文言の上書き（overrides.ts）", () => {
  it("同梱の上書きは空で、辞書は基になる辞書（ja.ts、en.ts）と同じ内容のまま", () => {
    expect(overrides).toEqual({ ja: {}, en: {} });
    expect(MESSAGES.ja).toEqual(ja);
    expect(MESSAGES.en).toEqual(en);
  });
});

describe("applyOverrides: 辞書の上に上書きを重ねる", () => {
  it("上書きが空（または省略）なら、元の辞書と同じ内容", () => {
    expect(applyOverrides(ja, {})).toEqual(ja);
    expect(applyOverrides(ja, undefined)).toEqual(ja);
    expect(buildMessages({ ja: {}, en: {} })).toEqual({ ja, en });
  });

  it("入れ子の一部だけが変わり、書いていない鍵は、同じ群の中でも、ほかの群でも元のまま", () => {
    const merged = applyOverrides(ja, { text: { appTitle: "社内ヘルプ" } });

    expect(merged.text.appTitle).toBe("社内ヘルプ");
    expect(merged.text.firstQuestion).toBe(ja.text.firstQuestion);
    expect(merged.op.title).toBe(ja.op.title);
    expect(merged.meta).toEqual(ja.meta);
    // 変えていない群は、そのまま（同じオブジェクト）
    expect(merged.op).toBe(ja.op);
    expect({
      ...merged,
      text: { ...merged.text, appTitle: ja.text.appTitle },
    }).toEqual(ja);
  });

  it("さらに深い入れ子（辞書の群の中の群）も、書いた鍵だけが変わる", () => {
    const merged = applyOverrides(ja, {
      op: { columns: { OPERATOR: "対応する人の番" } },
    });

    expect(merged.op.columns).toEqual({
      ...ja.op.columns,
      OPERATOR: "対応する人の番",
    });
    expect(merged.op.title).toBe(ja.op.title);
  });

  it("関数の文言は、同じ引数の関数で上書きできる", () => {
    const merged = applyOverrides(ja, {
      op: { messageCount: (count) => `やり取り ${count}通` },
    });

    expect(merged.op.messageCount(3)).toBe("やり取り 3通");
    expect(ja.op.messageCount(3)).toBe("やり取り 3件");
    expect(merged.op.moved).toBe(ja.op.moved);
  });

  it("配列は、要素ごとではなく、配列ごと置き換わる", () => {
    const labels: [id: string, label: string][] = [["reception", "窓口"]];
    const merged = applyOverrides(ja, { skills: { labels } });

    expect(merged.skills.labels).toEqual([["reception", "窓口"]]);
    expect(merged.skills.allMock).toBe(ja.skills.allMock);
  });

  it("元の辞書は書き換えない", () => {
    const before = ja.text.appTitle;
    applyOverrides(ja, { text: { appTitle: "社内ヘルプ" } });
    expect(ja.text.appTitle).toBe(before);
  });

  it("日本語だけ上書きしても、英語は変わらない（逆も同じ）", () => {
    const only: MessagesOverrides = {
      ja: { text: { appTitle: "社内ヘルプ" } },
      en: {},
    };
    const built = buildMessages(only);
    expect(built.ja.text.appTitle).toBe("社内ヘルプ");
    expect(built.en).toEqual(en);
    expect(
      buildMessages({ ja: {}, en: { text: { appTitle: "Help" } } }).ja,
    ).toEqual(ja);
  });

  it("上書きを重ねた辞書でも、鍵の並びと種類は元の辞書と同じ", () => {
    const built = buildMessages({
      ja: { text: { appTitle: "社内ヘルプ" }, op: { title: "受付" } },
      en: { text: { appTitle: "Help" } },
    });
    const shape = (value: unknown): unknown =>
      typeof value === "function"
        ? "fn"
        : typeof value === "object" && value !== null
          ? Object.fromEntries(
              Object.entries(value).map(([k, v]) => [k, shape(v)]),
            )
          : typeof value;
    expect(shape(built.ja)).toEqual(shape(ja));
    expect(Object.keys(built.ja.text)).toEqual(Object.keys(ja.text));
  });
});

describe("上書きの型", () => {
  it("辞書にない鍵や、型の違う値は、書けない（型の検査で止まる）", () => {
    // 実行はせず、型の検査だけを確かめる
    const typeChecks = () => {
      const bad: MessagesOverrides = {
        // @ts-expect-error 辞書にない鍵
        ja: { text: { noSuchKey: "x" } },
        en: {},
      };
      const wrongType: MessagesOverrides = {
        // @ts-expect-error 文字列の鍵に、数を書いた
        ja: { text: { appTitle: 1 } },
        en: {},
      };
      const wrongFunction: MessagesOverrides = {
        // @ts-expect-error 関数の文言に、文字列を書いた
        ja: { op: { messageCount: "x" } },
        en: {},
      };
      const wrongArguments: MessagesOverrides = {
        // @ts-expect-error 関数の引数の型が違う
        ja: { op: { messageCount: (count: string) => count } },
        en: {},
      };
      return [bad, wrongType, wrongFunction, wrongArguments];
    };
    expect(typeof typeChecks).toBe("function");
  });
});
