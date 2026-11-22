import { describe, expect, it } from "vitest";
import { en } from "../i18n/en.ts";
import { ja } from "../i18n/ja.ts";
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
      expect(askerStatusLabel(state, fromOperator, ja.asker)).toBe(label);
    }
  });

  it("終わっていれば、終わり方を本人に見せる言葉にする（結果の出どころは関係しない）", () => {
    expect(askerStatusLabel(closedState("RESOLVED"), false, ja.asker)).toBe(
      "解決済み",
    );
    expect(askerStatusLabel(closedState("ANSWERED"), true, ja.asker)).toBe(
      "回答済み",
    );
    expect(askerStatusLabel(closedState("DECLINED"), true, ja.asker)).toBe(
      "見送り",
    );
    expect(askerStatusLabel(closedState("WITHDRAWN"), false, ja.asker)).toBe(
      "取り下げ",
    );
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
    ].map((state) => askerStatusLabel(state, true, ja.asker));
    for (const label of all) expect(label).not.toMatch(/区間|番|phase/);
  });
});

describe("endingText", () => {
  it("終わり方ごとの終わりの文言を返し、見送りだけ担当者が書いた理由を続ける", () => {
    expect(endingText("RESOLVED", null, ja.asker)).toEqual({
      lead: "解決してよかったです。この内容は記録しておきます。",
      reason: null,
    });
    expect(endingText("ANSWERED", null, ja.asker).lead).toBe(
      "お返事がなかったため、いったん終了しました。解決していなければ、このページから続きを送れます。",
    );
    expect(
      endingText(
        "DECLINED",
        { reasonKind: "OTHER", reason: "対象外です" },
        ja.asker,
      ),
    ).toEqual({
      lead: "担当者から、今回は対応を見送るとの連絡がありました。",
      reason: "対象外です",
    });
    // 時間で「取り下げ」になった件だけ、専用の文言
    expect(endingText("WITHDRAWN", null, ja.asker, "TIME").lead).toBe(
      "お返事がなかったため、取り下げとして終了しました。続ける場合は、このページから送れます。",
    );
    expect(endingText("WITHDRAWN", null, ja.asker, "ASKER").lead).toBe(
      "この問い合わせは取り下げました。",
    );
    expect(endingText("ANSWERED", null, ja.asker, "TIME").lead).toBe(
      ja.asker.endingText.ANSWERED,
    );
    expect(endingText("WITHDRAWN", null, ja.asker).lead).toBe(
      "この問い合わせは取り下げました。",
    );
  });
});

describe("英語の状態の言葉（docs/ux-principles.md の「英語の言葉」）", () => {
  it("待っていること、担当者の結果、終わり方を、対応表の言葉にする", () => {
    expect(askerStatusLabel(HANDLING_STATE, false, en.asker)).toBe(
      "The support team is looking into it",
    );
    expect(askerStatusLabel(ASKER_REPLY_STATE, true, en.asker)).toBe(
      "The support team has a question for you",
    );
    expect(askerStatusLabel(RESULT_STATE, true, en.asker)).toBe(
      "The support team has sent you a result",
    );
    expect(askerStatusLabel(closedState("RESOLVED"), false, en.asker)).toBe(
      "Resolved",
    );
    expect(askerStatusLabel(closedState("DECLINED"), false, en.asker)).toBe(
      "Declined",
    );
    expect(endingText("WITHDRAWN", null, en.asker).lead).toBe(
      "This inquiry has been withdrawn.",
    );
  });
});
