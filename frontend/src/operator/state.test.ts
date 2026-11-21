import { describe, expect, it } from "vitest";
import { makeDetail, makeItem } from "../test/fakes.ts";
import {
  arrangeOperatorBoard,
  initialOperatorState,
  operatorColumnOf,
  mergeFiled,
  operatorReducer,
  tabTitleOf,
  unreadCountOf,
  visibleItems,
} from "./state.ts";

const unread = makeItem({ id: "a", read: false });
const read = makeItem({ id: "b", read: true });

describe("オペレータ画面の純粋関数", () => {
  it("未読数と、未読があるときだけ件数が付くタブの題名", () => {
    expect(unreadCountOf([unread, read, makeItem({ id: "c" })])).toBe(2);
    expect(tabTitleOf(2)).toBe("(2) 問い合わせ受付");
    expect(tabTitleOf(0)).toBe("問い合わせ受付");
  });

  it("未読だけの絞り込みでも、開いている1件は残る", () => {
    expect(visibleItems([unread, read], true, null)).toEqual([unread]);
    expect(visibleItems([unread, read], true, "b")).toEqual([unread, read]);
    expect(visibleItems([unread, read], false, null)).toEqual([unread, read]);
  });

  it("届いた1件は先頭に足し、同じ id は重ねない", () => {
    const fresh = makeItem({ id: "c" });
    expect(mergeFiled([unread, read], fresh).map((i) => i.id)).toEqual([
      "c",
      "a",
      "b",
    ]);
    expect(
      mergeFiled([unread, read], { ...read, summary: "更新" }).map((i) => i.id),
    ).toEqual(["b", "a"]);
  });

  it("全内容を受け取って既読なら一覧の未読が消え、通知はその1件を開くと消える", () => {
    let state = operatorReducer(initialOperatorState, {
      type: "listLoaded",
      items: [unread, read],
    });
    state = operatorReducer(state, {
      type: "filed",
      item: makeItem({ id: "n" }),
    });
    expect(state.banner?.id).toBe("n");
    state = operatorReducer(state, { type: "selected", id: "n" });
    expect(state.banner).toBeNull();
    state = operatorReducer(state, { type: "selected", id: "a" });
    state = operatorReducer(state, {
      type: "detailLoaded",
      detail: makeDetail({ id: "a", read: true }),
    });
    expect(state.items.find((i) => i.id === "a")?.read).toBe(true);
    expect(unreadCountOf(state.items)).toBe(1);
  });
});

describe("担当者のボードの列と並び順", () => {
  it("列は対応状況で決まり、対応状況の無い古い応答は未対応に入る", () => {
    expect(operatorColumnOf({ handlingStatus: "OPEN" })).toBe("OPEN");
    expect(operatorColumnOf({ handlingStatus: "IN_PROGRESS" })).toBe(
      "IN_PROGRESS",
    );
    expect(operatorColumnOf({ handlingStatus: "DONE" })).toBe("DONE");
    expect(operatorColumnOf({ handlingStatus: null })).toBe("OPEN");
    expect(operatorColumnOf({})).toBe("OPEN");
  });

  it("列ごとに振り分け、列の中は未読を上に寄せて、同じ条件なら新しい順にする", () => {
    const at = (id: string, filedAt: string, rest = {}) =>
      makeItem({ id, filedAt, read: true, handlingStatus: "OPEN", ...rest });
    const board = arrangeOperatorBoard([
      at("old-read", "2026-11-20T00:00:00Z"),
      at("new-read", "2026-11-21T00:00:00Z"),
      at("old-unread", "2026-11-19T00:00:00Z", { read: false }),
      at("new-unread", "2026-11-21T01:00:00Z", { read: false }),
      at("working", "2026-11-18T00:00:00Z", { handlingStatus: "IN_PROGRESS" }),
      at("finished", "2026-11-18T00:00:00Z", { handlingStatus: "DONE" }),
      at("legacy", "2026-11-17T00:00:00Z", { handlingStatus: undefined }),
    ]);
    expect(board.OPEN.map((i) => i.id)).toEqual([
      "new-unread",
      "old-unread",
      "new-read",
      "old-read",
      "legacy",
    ]);
    expect(board.IN_PROGRESS.map((i) => i.id)).toEqual(["working"]);
    expect(board.DONE.map((i) => i.id)).toEqual(["finished"]);
  });

  it("日時が同じなら渡された順のままにし、元の配列は変えない", () => {
    const first = makeItem({ id: "x", read: true, handlingStatus: "OPEN" });
    const second = makeItem({ id: "y", read: true, handlingStatus: "OPEN" });
    const input = [first, second];
    expect(arrangeOperatorBoard(input).OPEN.map((i) => i.id)).toEqual([
      "x",
      "y",
    ]);
    expect(input).toEqual([first, second]);
  });
});

describe("更新の反映", () => {
  const open = makeItem({ id: "o", read: true, handlingStatus: "OPEN" });
  const done = makeItem({ id: "d", read: true, handlingStatus: "DONE" });

  it("updated は一覧のその件を更新し、未読なら先頭へ動かす。既読なら位置を保つ", () => {
    const base = {
      ...initialOperatorState,
      items: [open, makeItem({ id: "x", read: true }), done],
    };
    const unreadUpdate = operatorReducer(base, {
      type: "updated",
      item: { ...done, read: false, messageCount: 1 },
    });
    expect(unreadUpdate.items.map((i) => i.id)).toEqual(["d", "o", "x"]);
    expect(unreadUpdate.items[0]).toMatchObject({
      read: false,
      messageCount: 1,
    });

    const readUpdate = operatorReducer(base, {
      type: "updated",
      item: { ...open, handlingStatus: "IN_PROGRESS" },
    });
    expect(readUpdate.items.map((i) => i.id)).toEqual(["o", "x", "d"]);
    expect(readUpdate.items[0].handlingStatus).toBe("IN_PROGRESS");
  });

  it("statusMoved は一覧のその件と、開いている詳細の対応状況を変える", () => {
    const base = {
      ...initialOperatorState,
      items: [open, done],
      detail: makeDetail({ id: "o", handlingStatus: "OPEN" }),
    };
    const moved = operatorReducer(base, {
      type: "statusMoved",
      id: "o",
      status: "DONE",
    });
    expect(moved.items.map((i) => i.handlingStatus)).toEqual(["DONE", "DONE"]);
    expect(moved.detail?.handlingStatus).toBe("DONE");

    const other = operatorReducer(base, {
      type: "statusMoved",
      id: "d",
      status: "OPEN",
    });
    expect(other.detail?.handlingStatus).toBe("OPEN");
    expect(other.items[1].handlingStatus).toBe("OPEN");
  });

  it("詳細の取得に失敗しても、一覧の取得の失敗とは別に扱う", () => {
    const failed = operatorReducer(
      { ...initialOperatorState, loaded: true },
      { type: "detailFailed" },
    );
    expect(failed).toMatchObject({ detailFailed: true, failed: false });
    expect(operatorReducer(failed, { type: "closeDetail" }).detailFailed).toBe(
      false,
    );
  });
});
