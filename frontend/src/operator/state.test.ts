import { describe, expect, it } from "vitest";
import { ja } from "../i18n/ja.ts";
import {
  ASKER_REPLY_STATE,
  HANDLING_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
  makeDetail,
  makeItem,
  makeMessage,
} from "../test/fakes.ts";
import {
  arrangeOperatorBoard,
  canDropOnColumn,
  cardNoteOf,
  cardPhaseOf,
  dropIntentOf,
  initialOperatorState,
  movedMessageOf,
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
    expect(tabTitleOf(2, ja.op)).toBe("(2) 問い合わせ受付");
    expect(tabTitleOf(0, ja.op)).toBe("問い合わせ受付");
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
  it("列は誰の番かで決まり、終わっていれば番にかかわらず「終わり」に入る", () => {
    expect(operatorColumnOf(HANDLING_STATE)).toBe("OPERATOR");
    expect(operatorColumnOf(ASKER_REPLY_STATE)).toBe("ASKER");
    expect(operatorColumnOf(RESULT_STATE)).toBe("ASKER");
    for (const ending of [
      "RESOLVED",
      "ANSWERED",
      "DECLINED",
      "WITHDRAWN",
    ] as const) {
      expect(operatorColumnOf(closedState(ending))).toBe("CLOSED");
    }
    // 担当者の一覧には本来出ない AI の番も、本人の番の列に入れる（落とさない）
    expect(operatorColumnOf(INVESTIGATION_STATE)).toBe("ASKER");
  });

  it("列ごとに振り分け、列の中は業務が止まっている件、未読の件の順に上へ寄せ、同じ条件なら新しい順にする", () => {
    const at = (id: string, filedAt: string, rest = {}) =>
      makeItem({ id, filedAt, read: true, ...rest });
    const board = arrangeOperatorBoard([
      at("old-read", "2026-11-20T00:00:00Z"),
      at("new-read", "2026-11-21T00:00:00Z"),
      at("old-unread", "2026-11-19T00:00:00Z", { read: false }),
      at("new-unread", "2026-11-21T01:00:00Z", { read: false }),
      at("old-blocked", "2026-11-10T00:00:00Z", { urgency: "BLOCKED" }),
      at("blocked-unread", "2026-11-09T00:00:00Z", {
        urgency: "BLOCKED",
        read: false,
      }),
      at("hurry", "2026-11-22T00:00:00Z", { urgency: "HURRY" }),
      at("waiting", "2026-11-18T00:00:00Z", ASKER_REPLY_STATE),
      at("finished", "2026-11-18T00:00:00Z", closedState("RESOLVED")),
    ]);
    expect(board.OPERATOR.map((i) => i.id)).toEqual([
      "blocked-unread",
      "old-blocked",
      "new-unread",
      "old-unread",
      "hurry",
      "new-read",
      "old-read",
    ]);
    expect(board.ASKER.map((i) => i.id)).toEqual(["waiting"]);
    expect(board.CLOSED.map((i) => i.id)).toEqual(["finished"]);
  });

  it("日時が同じなら渡された順のままにし、元の配列は変えない", () => {
    const first = makeItem({ id: "x", read: true });
    const second = makeItem({ id: "y", read: true });
    const input = [first, second];
    expect(arrangeOperatorBoard(input).OPERATOR.map((i) => i.id)).toEqual([
      "x",
      "y",
    ]);
    expect(input).toEqual([first, second]);
  });

  it("カードを落とせるのは、「担当者の番」から「本人の番」「終わり」へだけ", () => {
    const columns = ["OPERATOR", "ASKER", "CLOSED"] as const;
    const allowed: [string, string][] = [];
    for (const from of columns) {
      for (const to of columns) {
        if (canDropOnColumn(from, to)) allowed.push([from, to]);
      }
    }
    expect(allowed).toEqual([
      ["OPERATOR", "ASKER"],
      ["OPERATOR", "CLOSED"],
    ]);
  });

  it("落とした列で始める操作が決まる。「本人の番」は本人に送る、「終わり」は見送る", () => {
    expect(dropIntentOf("ASKER")).toBe("send");
    expect(dropIntentOf("CLOSED")).toBe("decline");
    expect(dropIntentOf("OPERATOR")).toBeNull();
  });

  it("カードに添える言葉は、本人の番の列では待っていること、終わりの列では終わり方、担当者の番の列には無い", () => {
    expect(cardNoteOf(HANDLING_STATE, ja.op)).toBeNull();
    expect(cardNoteOf(ASKER_REPLY_STATE, ja.op)).toBe("返事待ち");
    expect(cardNoteOf(RESULT_STATE, ja.op)).toBe("結果の確かめ待ち");
    expect(cardNoteOf(closedState("RESOLVED"), ja.op)).toBe("解決済み");
    expect(cardNoteOf(closedState("ANSWERED"), ja.op)).toBe("回答済み");
    expect(cardNoteOf(closedState("DECLINED"), ja.op)).toBe("見送り");
    expect(cardNoteOf(closedState("WITHDRAWN"), ja.op)).toBe("取り下げ");
  });

  it("カードの区間は、分かる・決まる・行われるだけを出す", () => {
    expect(cardPhaseOf({ phase: "KNOW" }, ja.op)).toBe("分かる");
    expect(cardPhaseOf({ phase: "DECIDE" }, ja.op)).toBe("決まる");
    expect(cardPhaseOf({ phase: "ACT" }, ja.op)).toBe("行われる");
    expect(cardPhaseOf({ phase: "INTAKE" }, ja.op)).toBeNull();
    expect(cardPhaseOf({ phase: "CLOSED" }, ja.op)).toBeNull();
  });
});

describe("movedMessageOf", () => {
  it("別の列に移った件を、移った先の列の名前で読み上げ用の文にする", () => {
    const before = [makeItem({ id: "a", read: true })];
    const after = [{ ...before[0], ...ASKER_REPLY_STATE }];
    expect(movedMessageOf(before, after, ja.op)).toBe(
      "「求人が一覧に出なくなった理由を知りたい。」を本人の番に移しました",
    );
  });

  it("列が変わらない件、前の一覧になかった件（新しく届いた件）は、文にしない", () => {
    const before = [makeItem({ id: "a", read: true })];
    expect(movedMessageOf(before, [...before], ja.op)).toBe("");
    expect(movedMessageOf([], before, ja.op)).toBe("");
    expect(
      movedMessageOf(before, [...before, makeItem({ id: "new" })], ja.op),
    ).toBe("");
  });
});

describe("更新の反映", () => {
  const mine = makeItem({ id: "o", read: true });
  const done = makeItem({
    id: "d",
    read: true,
    ...closedState("RESOLVED"),
  });

  it("updated は一覧のその件を更新し、未読なら先頭へ動かす。既読なら位置を保つ", () => {
    const base = {
      ...initialOperatorState,
      items: [mine, makeItem({ id: "x", read: true }), done],
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
      item: { ...mine, ...ASKER_REPLY_STATE },
    });
    expect(readUpdate.items.map((i) => i.id)).toEqual(["o", "x", "d"]);
    expect(readUpdate.items[0].awaiting).toBe("ASKER_REPLY");
  });

  it("updated で状態が変わった件は、並べ直すと別の列に入る", () => {
    const base = { ...initialOperatorState, items: [mine, done] };
    const updated = operatorReducer(base, {
      type: "updated",
      item: { ...mine, ...ASKER_REPLY_STATE },
    });
    const board = arrangeOperatorBoard(updated.items);
    expect(board.OPERATOR).toHaveLength(0);
    expect(board.ASKER.map((i) => i.id)).toEqual(["o"]);
  });

  it("詳細を取得すると、一覧のその件の状態、置き場所、やり取りの数も詳細に揃える", () => {
    const base = { ...initialOperatorState, items: [mine] };
    const loaded = operatorReducer(base, {
      type: "detailLoaded",
      detail: makeDetail({
        id: "o",
        ...ASKER_REPLY_STATE,
        read: true,
        urgency: "BLOCKED",
        function: { id: "posting", label: "求人の掲載", by: "OPERATOR" },
        messages: [makeMessage(), makeMessage({ id: "m2" })],
      }),
    });
    expect(loaded.items[0]).toMatchObject({
      phase: "DECIDE",
      turn: "ASKER",
      awaiting: "ASKER_REPLY",
      ending: null,
      urgency: "BLOCKED",
      messageCount: 2,
      function: { id: "posting" },
    });
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
