import { describe, expect, it } from "vitest";
import type { InquiryStep } from "../api/types.ts";
import { makeStructuredView, makeView } from "../test/fakes.ts";
import {
  blankToNull,
  canSendNow,
  fromStructuredDraft,
  progressIndexOf,
  screenOf,
  toStructuredDraft,
} from "./screen.ts";

describe("screenOf", () => {
  it("問い合わせが無ければ入力画面", () => {
    expect(screenOf(null)).toBe("INPUT");
  });

  it.each<[InquiryStep, string]>([
    ["TRIAGED", "TRIAGE"],
    ["STRUCTURED", "STRUCTURE"],
    ["ANSWERING", "ANSWERING"],
    ["ANSWERED", "ANSWERED"],
    ["CLOSED", "CLOSED"],
  ])("step %s は %s 画面", (step, screen) => {
    expect(screenOf(makeView({ step }))).toBe(screen);
  });
});

describe("progressIndexOf", () => {
  it("0次回答の生成中と判定待ちは同じ段階、完了が最後", () => {
    expect(progressIndexOf("INPUT")).toBe(0);
    expect(progressIndexOf("ANSWERING")).toBe(progressIndexOf("ANSWERED"));
    expect(progressIndexOf("CLOSED")).toBe(4);
  });
});

describe("canSendNow", () => {
  it("作成前と CLOSED では出さず、それ以外の段階では出す", () => {
    expect(canSendNow(null)).toBe(false);
    expect(canSendNow(makeView({ step: "CLOSED" }))).toBe(false);
    for (const step of [
      "TRIAGED",
      "STRUCTURED",
      "ANSWERING",
      "ANSWERED",
    ] as const) {
      expect(canSendNow(makeView({ step }))).toBe(true);
    }
  });
});

describe("blankToNull", () => {
  it("空白だけなら null、それ以外は前後の空白を除く", () => {
    expect(blankToNull("   ")).toBeNull();
    expect(blankToNull("")).toBeNull();
    expect(blankToNull("  対象 ")).toBe("対象");
  });
});

describe("整形結果の変換", () => {
  it("null は空欄に、空欄は null に戻る", () => {
    const structured = makeStructuredView().structured!;
    const draft = toStructuredDraft(structured);
    expect(draft.whatHappened).toBe("");
    expect(fromStructuredDraft(draft)).toEqual(structured);
  });

  it("編集した値は空白を除いて送る形になる", () => {
    const result = fromStructuredDraft({
      targetSystem: " ",
      goal: "  延長したい ",
      whatHappened: "エラーが出た",
      expectedResult: "",
      urgency: "HIGH",
    });
    expect(result).toEqual({
      targetSystem: null,
      goal: "延長したい",
      whatHappened: "エラーが出た",
      expectedResult: null,
      urgency: "HIGH",
    });
  });
});
