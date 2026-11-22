import { describe, expect, it } from "vitest";
import {
  ASKER_REPLY_STATE,
  CONFIRMATION_STATE,
  HANDLING_STATE,
  INTAKE_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
  makeListItem,
} from "../test/fakes.ts";
import { arrangeAskerBoard, askerColumnOf, type AskerColumn } from "./board.ts";

describe("askerColumnOf", () => {
  it("docs/ux-principles.md の表どおり、誰の番かで列を決める", () => {
    const cases: [Parameters<typeof askerColumnOf>[0], AskerColumn][] = [
      // あなたの番: 入力の途中、問いの確認、結果の確かめ、聞き返しへの返事
      [INTAKE_STATE, "YOURS"],
      [CONFIRMATION_STATE, "YOURS"],
      [RESULT_STATE, "YOURS"],
      [ASKER_REPLY_STATE, "YOURS"],
      // 相手の番: AI か担当者
      [INVESTIGATION_STATE, "THEIRS"],
      [HANDLING_STATE, "THEIRS"],
      // 終わり: 終わり方にかかわらず
      [closedState("RESOLVED"), "CLOSED"],
      [closedState("ANSWERED"), "CLOSED"],
      [closedState("DECLINED"), "CLOSED"],
      [closedState("WITHDRAWN"), "CLOSED"],
    ];
    for (const [item, column] of cases) {
      expect(askerColumnOf(item)).toBe(column);
    }
  });
});

describe("arrangeAskerBoard", () => {
  it("列ごとに振り分け、列の中は未読の返信を上に寄せ、同じ条件なら新しい順にする", () => {
    const theirs = (id: string, createdAt: string, unreadReply = false) =>
      makeListItem({ id, createdAt, unreadReply, ...HANDLING_STATE });
    const board = arrangeAskerBoard([
      theirs("old-read", "2026-11-20T00:00:00Z"),
      theirs("new-read", "2026-11-21T00:00:00Z"),
      theirs("old-unread", "2026-11-19T00:00:00Z", true),
      theirs("new-unread", "2026-11-21T01:00:00Z", true),
      makeListItem({ id: "draft", ...INTAKE_STATE }),
      makeListItem({ id: "reply", ...ASKER_REPLY_STATE }),
      makeListItem({
        id: "solved",
        ...closedState("RESOLVED"),
        createdAt: "2026-11-22T00:00:00Z",
      }),
      makeListItem({ id: "declined", ...closedState("DECLINED") }),
    ]);
    expect(board.THEIRS.map((i) => i.id)).toEqual([
      "new-unread",
      "old-unread",
      "new-read",
      "old-read",
    ]);
    expect(board.YOURS.map((i) => i.id).sort()).toEqual(["draft", "reply"]);
    expect(board.CLOSED.map((i) => i.id)).toEqual(["solved", "declined"]);
  });

  it("列の中は、業務が止まっている件、未読の返信の件の順に上に寄せる", () => {
    const board = arrangeAskerBoard([
      makeListItem({ id: "new", createdAt: "2026-11-22T00:00:00Z" }),
      makeListItem({
        id: "unread",
        createdAt: "2026-11-20T00:00:00Z",
        unreadReply: true,
      }),
      makeListItem({
        id: "blocked",
        createdAt: "2026-11-01T00:00:00Z",
        urgency: "BLOCKED",
      }),
      makeListItem({
        id: "hurry",
        createdAt: "2026-11-23T00:00:00Z",
        urgency: "HURRY",
      }),
    ]);
    expect(board.THEIRS.map((i) => i.id)).toEqual([
      "blocked",
      "unread",
      "hurry",
      "new",
    ]);
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
