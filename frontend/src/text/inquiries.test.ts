import { describe, expect, it } from "vitest";
import { handlingStatusLabel, listStatusLabel } from "./inquiries.ts";

describe("listStatusLabel", () => {
  it("設計方針の表どおりの言葉を返す", () => {
    const cases: [Parameters<typeof listStatusLabel>[0], string][] = [
      [
        { step: "STRUCTURED", outcome: "IN_PROGRESS", handlingStatus: null },
        "途中（続きから再開できます）",
      ],
      [
        { step: "TRIAGED", outcome: "IN_PROGRESS", handlingStatus: null },
        "途中（続きから再開できます）",
      ],
      [
        { step: "CLOSED", outcome: "SELF_RESOLVED", handlingStatus: null },
        "解決済み",
      ],
      [
        {
          step: "CLOSED",
          outcome: "INCIDENT_REDIRECTED",
          handlingStatus: null,
        },
        "障害の窓口を案内",
      ],
      [
        { step: "CLOSED", outcome: "FILED", handlingStatus: "OPEN" },
        "担当者の確認待ち",
      ],
      [
        { step: "CLOSED", outcome: "FILED", handlingStatus: "IN_PROGRESS" },
        "担当者が対応中",
      ],
      [
        { step: "CLOSED", outcome: "FILED", handlingStatus: "DONE" },
        "対応完了",
      ],
    ];
    for (const [item, label] of cases) {
      expect(listStatusLabel(item)).toBe(label);
    }
  });
});

describe("handlingStatusLabel", () => {
  it("本人向けの画面の言葉を返し、無ければ確認待ちとして扱う", () => {
    expect(handlingStatusLabel("OPEN")).toBe("担当者の確認を待っています");
    expect(handlingStatusLabel("IN_PROGRESS")).toBe("担当者が対応しています");
    expect(handlingStatusLabel("DONE")).toBe("対応が完了しました");
    expect(handlingStatusLabel(undefined)).toBe("担当者の確認を待っています");
  });
});
