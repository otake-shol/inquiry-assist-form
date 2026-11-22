import { describe, expect, it } from "vitest";
import { en } from "../i18n/en.ts";
import { ja } from "../i18n/ja.ts";
import { handoverText } from "./labels.ts";
import { HANDED_OVER_AT, makeView } from "../test/fakes.ts";

describe("handoverText", () => {
  it("答えを待たずに送った場合は、書いた内容をそのまま添えたと伝える", () => {
    const text = handoverText(
      makeView({ sentImmediately: true }),
      "担当者が確認します。",
      ja.asker.handover,
    );
    expect(text.lead).toBe(
      "担当者に送りました。書いていただいた内容をそのまま添えています。",
    );
    expect(text.guidance).toBe("担当者が確認します。");
  });

  it("業務が止まっている件は、すぐ届けたと伝える", () => {
    const text = handoverText(
      makeView({
        urgency: "BLOCKED",
        sentImmediately: true,
      }),
      null,
      ja.asker.handover,
    );
    expect(text.lead).toBe(
      "担当者にすぐ届けました。書いていただいた内容をそのまま添えています。",
    );
  });

  it("AI が引き継いだ件は、送ったことを繰り返さず、添えた内容だけを伝える", () => {
    const timedOut = makeView({
      handedOverAt: HANDED_OVER_AT,
      handoverReason: "TIMEOUT",
    });
    expect(handoverText(timedOut, null, ja.asker.handover).lead).toBe(
      "ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
    );
    const noAnswer = makeView({
      handedOverAt: HANDED_OVER_AT,
      handoverReason: "NO_ANSWER",
      answer: {
        grounded: false,
        askerExplanation: null,
        memo: { findings: [], notFound: [], nextChecks: [] },
        citations: [],
      },
    });
    expect(handoverText(noAnswer, null, ja.asker.handover).lead).toBe(
      "ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
    );
    expect(handoverText(noAnswer, null, en.asker.handover).lead).toBe(
      "I attached what you have told me so far and what I found. You do not need to explain it again.",
    );
  });

  it("調べた結果の有無で、添えた内容の言い方が変わる", () => {
    const filed = makeView({ handedOverAt: HANDED_OVER_AT });
    expect(handoverText(filed, null, ja.asker.handover).lead).toBe(
      "担当者に送りました。ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
    );
    const withAnswer = makeView({
      handedOverAt: HANDED_OVER_AT,
      answer: {
        grounded: false,
        askerExplanation: null,
        memo: { findings: [], notFound: [], nextChecks: [] },
        citations: [],
      },
    });
    expect(handoverText(withAnswer, null, ja.asker.handover).lead).toBe(
      "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
    );
  });
});

describe("handoverText（英語）", () => {
  it("渡し方で言い方が変わり、案内文はそのまま添える", () => {
    expect(
      handoverText(
        makeView({ urgency: "BLOCKED", sentImmediately: true }),
        "We will reply on this page.",
        en.asker.handover,
      ),
    ).toEqual({
      lead: "I delivered this to the support team right away. I attached what you wrote as it is.",
      guidance: "We will reply on this page.",
    });
    expect(
      handoverText(makeView({ sentImmediately: true }), null, en.asker.handover)
        .lead,
    ).toBe(
      "I sent this to the support team. I attached what you wrote as it is.",
    );
  });
});
