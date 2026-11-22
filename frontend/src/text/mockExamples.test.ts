import { describe, expect, it } from "vitest";
import type { InquiryTypeId } from "../api/types.ts";
import { en } from "../i18n/en.ts";
import { ja } from "../i18n/ja.ts";
import {
  SCENARIO_TAGS,
  tagExampleList,
  typeExampleList,
} from "./mockExamples.ts";

/** 契約書「モックのシナリオ」の受付の表（上から優先）。 */
const RECEPTION_RULES: [InquiryTypeId, string[]][] = [
  [
    "CHANGE_REQUEST",
    ["変えてほしい", "要望", "改善", "feature request", "improve"],
  ],
  [
    "DO_FOR_ME",
    [
      "依頼",
      "してほしい",
      "お願いします",
      "してください",
      "please",
      "could you",
    ],
  ],
  ["LOOKS_WRONG", ["不具合", "バグ", "おかしい", "bug", "wrong", "broken"]],
  ["HOW_TO", ["使い方", "やり方", "手順", "どうやって", "how to", "how do i"]],
];

/** モックの受付と同じ決まりで、文章から種類を決める。 */
function receptionTypeOf(text: string): InquiryTypeId {
  const lower = text.toLowerCase();
  for (const [type, words] of RECEPTION_RULES) {
    if (words.some((word) => lower.includes(word))) return type;
  }
  return "HOW_IT_WORKS";
}

describe("モックの例文", () => {
  for (const [name, messages] of [
    ["日本語", ja],
    ["英語", en],
  ] as const) {
    it(`${name}: 種類ごとの例文は、モックの受付がその種類に置く語を含む`, () => {
      const types = Object.keys(messages.mock.typeExamples);
      expect(types).toEqual([
        "HOW_TO",
        "HOW_IT_WORKS",
        "LOOKS_WRONG",
        "DO_FOR_ME",
        "CHANGE_REQUEST",
      ]);
      for (const [type, example] of Object.entries(
        messages.mock.typeExamples,
      )) {
        expect(receptionTypeOf(example.text)).toBe(type);
      }
      expect(typeExampleList(messages.mock)).toHaveLength(5);
    });

    it(`${name}: 動きの例は、契約書の12個のタグを表の順に並べ、正常系の例文にタグを付ける`, () => {
      const list = tagExampleList(messages.mock);
      expect(list.map((e) => e.label)).toEqual([...SCENARIO_TAGS]);
      expect(list).toHaveLength(12);
      for (const example of list) {
        expect(example.text).toBe(
          `${messages.mock.typeExamples.HOW_IT_WORKS.text} ${example.label}`,
        );
        expect(receptionTypeOf(example.text)).toBe("HOW_IT_WORKS");
      }
    });
  }

  it("英語の例文は、システムの英語の名前（System A）を含む", () => {
    for (const example of typeExampleList(en.mock)) {
      expect(example.text).toContain("System A");
    }
  });

  it("タグは識別子で、言語によらず同じ12個", () => {
    expect(SCENARIO_TAGS).toEqual([
      "[判定エラー]",
      "[質問なし]",
      "[質問エラー]",
      "[言い直しエラー]",
      "[根拠なし]",
      "[幽霊出典]",
      "[一部幽霊]",
      "[遅延]",
      "[エラー]",
      "[拒否]",
      "[説明エラー]",
      "[的外れ]",
    ]);
  });
});
