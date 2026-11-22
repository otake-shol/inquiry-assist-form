import { describe, expect, it } from "vitest";
import { closingText } from "./labels.ts";
import { makeView } from "../test/fakes.ts";

describe("closingText", () => {
  it("答えを待たずに送った場合は、書いた内容をそのまま添えたと伝える", () => {
    const text = closingText(
      makeView({ outcome: "FILED", sentImmediately: true }),
      "担当者が確認します。",
    );
    expect(text.lead).toBe(
      "担当者に送りました。書いていただいた内容をそのまま添えています。",
    );
    expect(text.guidance).toBe("担当者が確認します。");
  });

  it("業務が止まっている件は、すぐ届けたと伝える", () => {
    const text = closingText(
      makeView({
        outcome: "FILED",
        urgency: "BLOCKED",
        sentImmediately: true,
      }),
      null,
    );
    expect(text.lead).toBe(
      "担当者にすぐ届けました。書いていただいた内容をそのまま添えています。",
    );
  });

  it("調べた結果の有無で、添えた内容の言い方が変わる", () => {
    const filed = makeView({ outcome: "FILED" });
    expect(closingText(filed, null).lead).toBe(
      "担当者に送りました。ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
    );
    const withAnswer = makeView({
      outcome: "FILED",
      answer: {
        grounded: false,
        askerExplanation: null,
        memo: { findings: [], notFound: [], nextChecks: [] },
        citations: [],
      },
    });
    expect(closingText(withAnswer, null).lead).toBe(
      "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
    );
  });

  it("自己解決は記録することを伝え、案内文は添えない", () => {
    const text = closingText(makeView({ outcome: "SELF_RESOLVED" }), "案内");
    expect(text).toEqual({
      lead: "解決してよかったです。この内容は記録しておきます。",
      guidance: null,
    });
  });
});
