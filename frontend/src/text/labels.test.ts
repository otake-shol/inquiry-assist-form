import { describe, expect, it } from "vitest";
import { closingText, receiptText } from "./labels.ts";
import { makeView } from "../test/fakes.ts";

const job = { id: "job-admin", label: "求人管理画面" };

describe("receiptText", () => {
  it("対象システムが無ければ従来の文言", () => {
    expect(receiptText("SUSPECTED_BUG")).toBe(
      "不具合かもしれない件として受け取りました",
    );
    expect(receiptText(null)).toBe("内容を受け取りました");
  });

  it("選んだ対象システムは「〜の、…として」と添える", () => {
    expect(receiptText("SUSPECTED_BUG", job, false)).toBe(
      "求人管理画面の、不具合かもしれない件として受け取りました",
    );
    expect(receiptText(null, job, false)).toBe(
      "求人管理画面の話として内容を受け取りました",
    );
  });

  it("推測した対象システムは推測と分かる言い方にする", () => {
    expect(receiptText("SPEC_QUESTION", job, true)).toBe(
      "求人管理画面の話だと推測しました。仕様の確認・相談として受け取りました",
    );
  });
});

describe("closingText", () => {
  it("障害では案内文を別枠で返す", () => {
    const text = closingText(
      makeView({ outcome: "INCIDENT_REDIRECTED", incidentGuidance: "窓口へ" }),
      null,
    );
    expect(text.incident).toBe("窓口へ");
  });
});
