import { describe, expect, it } from "vitest";
import { makeListItem } from "../test/fakes.ts";
import { arrangeAskerBoard, askerColumnOf, type AskerColumn } from "./board.ts";

describe("askerColumnOf", () => {
  it("docs/ux-principles.md の表どおりに列を決める", () => {
    const cases: [Parameters<typeof askerColumnOf>[0], AskerColumn][] = [
      [
        { step: "TRIAGED", outcome: "IN_PROGRESS", handlingStatus: null },
        "DRAFT",
      ],
      [
        { step: "STRUCTURED", outcome: "IN_PROGRESS", handlingStatus: null },
        "DRAFT",
      ],
      [
        { step: "ANSWERING", outcome: "IN_PROGRESS", handlingStatus: null },
        "DRAFT",
      ],
      [
        { step: "ANSWERED", outcome: "IN_PROGRESS", handlingStatus: null },
        "DRAFT",
      ],
      [{ step: "CLOSED", outcome: "FILED", handlingStatus: "OPEN" }, "WAITING"],
      [
        { step: "CLOSED", outcome: "FILED", handlingStatus: "IN_PROGRESS" },
        "IN_PROGRESS",
      ],
      [{ step: "CLOSED", outcome: "FILED", handlingStatus: "DONE" }, "DONE"],
      [
        { step: "CLOSED", outcome: "SELF_RESOLVED", handlingStatus: null },
        "DONE",
      ],
      [
        {
          step: "CLOSED",
          outcome: "INCIDENT_REDIRECTED",
          handlingStatus: null,
        },
        "DONE",
      ],
      [{ step: "CLOSED", outcome: "ABANDONED", handlingStatus: null }, "DONE"],
    ];
    for (const [item, column] of cases) {
      expect(askerColumnOf(item)).toBe(column);
    }
  });

  it("終わっていない件は、対応状況があっても入力の途中に入る", () => {
    expect(
      askerColumnOf({
        step: "ANSWERED",
        outcome: "FILED",
        handlingStatus: "DONE",
      }),
    ).toBe("DRAFT");
  });

  it("担当者に送った件で対応状況が無い古い応答は、確認待ちとして扱う", () => {
    expect(
      askerColumnOf({ step: "CLOSED", outcome: "FILED", handlingStatus: null }),
    ).toBe("WAITING");
  });
});

describe("arrangeAskerBoard", () => {
  it("列ごとに振り分け、列の中は未読の返信を上に寄せ、同じ条件なら新しい順にする", () => {
    const waiting = (id: string, createdAt: string, unreadReply = false) =>
      makeListItem({ id, createdAt, unreadReply, handlingStatus: "OPEN" });
    const board = arrangeAskerBoard([
      waiting("old-read", "2026-11-20T00:00:00Z"),
      waiting("new-read", "2026-11-21T00:00:00Z"),
      waiting("old-unread", "2026-11-19T00:00:00Z", true),
      waiting("new-unread", "2026-11-21T01:00:00Z", true),
      makeListItem({
        id: "draft",
        step: "STRUCTURED",
        outcome: "IN_PROGRESS",
        handlingStatus: null,
      }),
      makeListItem({ id: "working", handlingStatus: "IN_PROGRESS" }),
      makeListItem({ id: "finished", handlingStatus: "DONE" }),
      makeListItem({
        id: "solved",
        outcome: "SELF_RESOLVED",
        handlingStatus: null,
        createdAt: "2026-11-22T00:00:00Z",
      }),
    ]);
    expect(board.WAITING.map((i) => i.id)).toEqual([
      "new-unread",
      "old-unread",
      "new-read",
      "old-read",
    ]);
    expect(board.DRAFT.map((i) => i.id)).toEqual(["draft"]);
    expect(board.IN_PROGRESS.map((i) => i.id)).toEqual(["working"]);
    expect(board.DONE.map((i) => i.id)).toEqual(["solved", "finished"]);
  });

  it("元の配列は変えない", () => {
    const input = [
      makeListItem({ id: "a", createdAt: "2026-11-20T00:00:00Z" }),
      makeListItem({ id: "b", createdAt: "2026-11-21T00:00:00Z" }),
    ];
    arrangeAskerBoard(input);
    expect(input.map((i) => i.id)).toEqual(["a", "b"]);
  });
});
