import { describe, expect, it } from "vitest";
import { makeDetail, makeItem } from "../test/fakes.ts";
import {
  initialOperatorState,
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
