import { describe, expect, it } from "vitest";
import type { InquiryTypeId } from "../api/types.ts";
import {
  SCENARIO_TAGS,
  TAG_EXAMPLE_LIST,
  TYPE_EXAMPLES,
  TYPE_EXAMPLE_LIST,
} from "./mockExamples.ts";

/** 契約書「モックのシナリオ」の受付の表（上から優先）。 */
const RECEPTION_RULES: [InquiryTypeId, string[]][] = [
  ["CHANGE_REQUEST", ["変えてほしい", "要望", "改善"]],
  ["DO_FOR_ME", ["依頼", "してほしい", "お願いします", "してください"]],
  ["LOOKS_WRONG", ["不具合", "バグ", "おかしい"]],
  ["HOW_TO", ["使い方", "やり方", "手順", "どうやって"]],
];

/** モックの受付と同じ決まりで、文章から種類を決める。 */
function receptionTypeOf(text: string): InquiryTypeId {
  for (const [type, words] of RECEPTION_RULES) {
    if (words.some((word) => text.includes(word))) return type;
  }
  return "HOW_IT_WORKS";
}

describe("モックの例文", () => {
  it("種類ごとの例文は、モックの受付がその種類に置く語を含む", () => {
    for (const [type, example] of Object.entries(TYPE_EXAMPLES)) {
      expect(receptionTypeOf(example.text)).toBe(type);
    }
    expect(TYPE_EXAMPLE_LIST).toHaveLength(5);
  });

  it("動きの例は、契約書の12個のタグを表の順に並べ、正常系の例文にタグを付ける", () => {
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
    expect(TAG_EXAMPLE_LIST).toHaveLength(12);
    for (const example of TAG_EXAMPLE_LIST) {
      expect(example.text).toBe(
        `${TYPE_EXAMPLES.HOW_IT_WORKS.text} ${example.label}`,
      );
      expect(receptionTypeOf(example.text)).toBe("HOW_IT_WORKS");
    }
  });
});
