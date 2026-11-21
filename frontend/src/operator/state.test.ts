import { describe, expect, it } from "vitest";
import { makeDetail, makeItem } from "../test/fakes.ts";
import {
  adjustCounts,
  initialOperatorState,
  mergeFiled,
  operatorReducer,
  tabTitleOf,
  totalCount,
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

describe("対応状況のタブと更新の反映", () => {
  const open = makeItem({ id: "o", read: true, handlingStatus: "OPEN" });
  const done = makeItem({ id: "d", read: true, handlingStatus: "DONE" });

  it("タブの絞り込みでも、開いている1件は残る", () => {
    expect(visibleItems([open, done], false, null, "DONE")).toEqual([done]);
    expect(visibleItems([open, done], false, "o", "DONE")).toEqual([
      open,
      done,
    ]);
    expect(visibleItems([open, done], false, null, "ALL")).toEqual([
      open,
      done,
    ]);
  });

  it("対応状況が変わった件数だけを直し、取得できていなければ何もしない", () => {
    const counts = { OPEN: 2, IN_PROGRESS: 1, DONE: 5 };
    expect(adjustCounts(counts, "OPEN", "DONE")).toEqual({
      OPEN: 1,
      IN_PROGRESS: 1,
      DONE: 6,
    });
    expect(adjustCounts(counts, undefined, "OPEN")).toEqual({
      ...counts,
      OPEN: 3,
    });
    expect(adjustCounts(counts, "DONE", "DONE")).toBe(counts);
    expect(adjustCounts(null, "OPEN", "DONE")).toBeNull();
    expect(totalCount(counts)).toBe(8);
  });

  it("updated は一覧のその件を更新し、未読なら先頭へ動かす。既読なら位置を保つ", () => {
    const base = {
      ...initialOperatorState,
      items: [open, makeItem({ id: "x", read: true }), done],
      counts: { OPEN: 1, IN_PROGRESS: 0, DONE: 1 },
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
    expect(readUpdate.counts).toEqual({ OPEN: 0, IN_PROGRESS: 1, DONE: 1 });
  });

  it("updated で開いているタブの対応状況から外れた件は一覧から除く", () => {
    const base = {
      ...initialOperatorState,
      tab: "OPEN" as const,
      items: [open],
    };
    const moved = operatorReducer(base, {
      type: "updated",
      item: { ...open, handlingStatus: "DONE" },
    });
    expect(moved.items).toEqual([]);
  });

  it("タブを切り替えると読み込み中に戻る", () => {
    const next = operatorReducer(
      { ...initialOperatorState, loaded: true },
      { type: "tabChanged", tab: "DONE" },
    );
    expect(next).toMatchObject({ tab: "DONE", loaded: false });
  });
});
