import { describe, expect, it } from "vitest";
import {
  ASKER_REPLY_STATE,
  CONFIRMATION_STATE,
  HANDLING_STATE,
  INTAKE_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
} from "../test/fakes.ts";
import { askerStatusLabel, endingText } from "./inquiries.ts";

describe("askerStatusLabel", () => {
  it("設計方針の表どおり、待っていることを本人に見せる言葉にする", () => {
    const cases: [Parameters<typeof askerStatusLabel>[0], boolean, string][] = [
      [INTAKE_STATE, false, "入力の途中です"],
      [CONFIRMATION_STATE, false, "問いの確認をお願いします"],
      [INVESTIGATION_STATE, false, "調べています"],
      [RESULT_STATE, false, "結果を確かめてください"],
      [RESULT_STATE, true, "担当者から結果が届いています"],
      [HANDLING_STATE, false, "担当者が確認しています"],
      [ASKER_REPLY_STATE, true, "担当者から質問が届いています"],
    ];
    for (const [state, fromOperator, label] of cases) {
      expect(askerStatusLabel(state, fromOperator)).toBe(label);
    }
  });

  it("終わっていれば、終わり方を本人に見せる言葉にする（結果の出どころは関係しない）", () => {
    expect(askerStatusLabel(closedState("RESOLVED"), false)).toBe("解決済み");
    expect(askerStatusLabel(closedState("ANSWERED"), true)).toBe("回答済み");
    expect(askerStatusLabel(closedState("DECLINED"), true)).toBe("見送り");
    expect(askerStatusLabel(closedState("WITHDRAWN"), false)).toBe("取り下げ");
  });

  it("内部の言葉を含まない", () => {
    const all = [
      INTAKE_STATE,
      CONFIRMATION_STATE,
      INVESTIGATION_STATE,
      RESULT_STATE,
      HANDLING_STATE,
      ASKER_REPLY_STATE,
      closedState("RESOLVED"),
    ].map((state) => askerStatusLabel(state, true));
    for (const label of all) expect(label).not.toMatch(/区間|番|phase/);
  });
});

describe("endingText", () => {
  it("終わり方ごとの終わりの文言を返し、見送りだけ担当者が書いた理由を続ける", () => {
    expect(endingText("RESOLVED", null)).toEqual({
      lead: "解決してよかったです。この内容は記録しておきます。",
      reason: null,
    });
    expect(endingText("ANSWERED", null).lead).toBe(
      "お返事がなかったため、いったん終了しました。解決していなければ、このページから続きを送れます。",
    );
    expect(
      endingText("DECLINED", { reasonKind: "OTHER", reason: "対象外です" }),
    ).toEqual({
      lead: "担当者から、今回は対応を見送るとの連絡がありました。",
      reason: "対象外です",
    });
    expect(endingText("WITHDRAWN", null).lead).toBe(
      "この問い合わせは取り下げました。",
    );
  });
});
