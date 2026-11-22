import { describe, expect, it } from "vitest";
import { ja } from "../i18n/ja.ts";
import { placementChangeText } from "./labels.ts";

describe("placementChangeText", () => {
  it("変える前と後を「・」でつなぎ、決まっていない項目は省く", () => {
    expect(
      placementChangeText(
        {
          before: {
            type: "おかしいかもしれない",
            system: "システムA",
            function: null,
          },
          after: {
            type: "やってほしい",
            system: "システムA",
            function: "機能A-1",
          },
        },
        ja,
      ),
    ).toBe(
      "おかしいかもしれない・システムA → やってほしい・システムA・機能A-1",
    );
  });

  it("すべて決まっていない側は「未定」と示す", () => {
    expect(
      placementChangeText(
        {
          before: { type: null, system: null, function: null },
          after: { type: "仕組みを知りたい", system: null, function: null },
        },
        ja,
      ),
    ).toBe("未定 → 仕組みを知りたい");
  });
});
