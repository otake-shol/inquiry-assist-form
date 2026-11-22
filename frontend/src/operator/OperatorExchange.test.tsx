import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import {
  ASKER_REPLY_STATE,
  FakeOperatorStream,
  RESULT_STATE,
  TYPE_OPTIONS,
  closedState,
  installFakeFetch,
  makeConfig,
  makeDetail,
  makeItem,
  makeMessage,
  makeTraceEntry,
  type RecordedCall,
  type RouteResult,
} from "../test/fakes.ts";

beforeEach(() => {
  window.history.replaceState({}, "", "/operator");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

/** 担当者の番の件。 */
const mine1 = makeItem({ id: "op-1", read: true });
/** 本人の返事を待っている件。 */
const waiting2 = makeItem({
  id: "op-2",
  read: true,
  ...ASKER_REPLY_STATE,
  summary: "返事待ちの件。",
  messageCount: 1,
});
/** 解決済みで終わった件。 */
const done3 = makeItem({
  id: "op-3",
  read: true,
  ...closedState("RESOLVED"),
  summary: "完了した件。",
  messageCount: 1,
});

/** 置き直しの選択肢にするシステム。 */
const SYSTEMS = [
  {
    id: "job-admin",
    label: "求人管理画面",
    functions: [
      { id: "posting", label: "求人の掲載" },
      { id: "applicants", label: "応募者の一覧" },
    ],
  },
  {
    id: "billing",
    label: "請求管理",
    functions: [{ id: "invoice", label: "請求書" }],
  },
];

/**
 * 列の領域を名前（見出し）で取る。名前は「見出し 件数」の形。
 * @param title 列の見出し
 */
const column = (title: string) =>
  screen.getByRole("region", { name: new RegExp(`^${title} \\d+件$`) });

/**
 * 列の中のカード（ボタン）を、言い直しの一部で取る。
 * @param title 列の見出し
 * @param text カードに含まれる文字
 */
const cardIn = (title: string, text: RegExp) =>
  within(column(title)).queryByRole("button", { name: text });

/** カードをドラッグして、指定の列に落とす。 */
function dragTo(card: HTMLElement, title: string) {
  const item = card.closest("li")!;
  fireEvent.dragStart(item);
  fireEvent.dragOver(column(title));
  fireEvent.drop(column(title));
  fireEvent.dragEnd(item);
}

/** 一覧と詳細の API を登録する。 */
function routes(extra: Parameters<typeof installFakeFetch>[0] = {}) {
  return {
    "GET /api/config": {
      body: makeConfig({ systems: SYSTEMS }),
    },
    "GET /api/operator/inquiries": {
      body: { unreadCount: 0, items: [mine1, waiting2, done3] },
    },
    "GET /api/operator/inquiries/op-1": {
      body: makeDetail({ read: true, messages: [] }),
    },
    "GET /api/operator/inquiries/op-2": {
      body: makeDetail({
        id: "op-2",
        read: true,
        ...ASKER_REPLY_STATE,
        messages: [makeMessage({ id: "m1", body: "いつからですか。" })],
      }),
    },
    ...extra,
  };
}

/** 画面を描いて、1件目のカードを返す。 */
async function renderBoard(
  extra: Parameters<typeof installFakeFetch>[0] = {},
  stream = new FakeOperatorStream(),
) {
  const calls = installFakeFetch(routes(extra));
  render(<Root subscribeOperator={stream.subscribe} />);
  const card = await screen.findByRole("button", {
    name: /求人が一覧に出なくなった/,
  });
  return { calls, card, stream };
}

/** 1件目のパネルを開いて返す。 */
async function openPanel(
  user: ReturnType<typeof userEvent.setup>,
  card: HTMLElement,
) {
  await user.click(card);
  const panel = await screen.findByRole("region", {
    name: "問い合わせの詳細",
  });
  await within(panel).findByText("元の発話");
  return panel;
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

/** 読み取り以外の API 呼び出し。 */
const writes = (calls: RecordedCall[]) =>
  calls.filter((c) => c.method !== "GET");

describe("担当者のボード: 列とカード", () => {
  it("担当者の番・本人の番・終わりの3列に件数を添え、カードを番の列に入れる。空の列は「ありません」と示す", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: { unreadCount: 0, items: [mine1, waiting2] },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /返事待ちの件/ });
    const board = screen.getByRole("region", { name: "受付一覧" });
    expect(
      within(board)
        .getAllByRole("heading", { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(["担当者の番", "本人の番", "終わり"]);
    expect(column("担当者の番")).toHaveAccessibleName("担当者の番 1件");
    expect(column("本人の番")).toHaveAccessibleName("本人の番 1件");
    expect(column("終わり")).toHaveAccessibleName("終わり 0件");
    expect(
      cardIn("担当者の番", /求人が一覧に出なくなった/),
    ).toBeInTheDocument();
    expect(cardIn("本人の番", /返事待ちの件/)).toBeInTheDocument();
    expect(within(column("終わり")).getByText("ありません")).toBeVisible();
    // 対応状況で分ける列は無い
    expect(screen.queryByText("未対応")).not.toBeInTheDocument();
    expect(screen.queryByText("対応中")).not.toBeInTheDocument();
    // 領域の中のボタンはカードだけ
    expect(within(board).getAllByRole("button")).toHaveLength(2);
  });

  it("カードに種類・システムと機能・緊急度・区間・今すぐ送る・日時・やり取りの件数を出す。担当者の番の列には待っていることを添えない", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              makeItem({
                id: "op-1",
                read: true,
                function: { id: "posting", label: "求人の掲載" },
                urgency: "HURRY",
                phase: "DECIDE",
                sentImmediately: true,
                messageCount: 3,
              }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    expect(card).toHaveTextContent("おかしいかもしれない");
    expect(card).toHaveTextContent("求人管理画面 / 求人の掲載");
    expect(card).toHaveTextContent("急ぎ");
    expect(card).toHaveTextContent("区間 決まる");
    expect(card).toHaveTextContent("今すぐ送る");
    expect(card).toHaveTextContent("やり取り 3件");
    expect(within(card).getByText(/\d+\/\d+/)).toBeInTheDocument();
    expect(card.querySelector(".op-wait")).toBeNull();
    expect(cardIn("担当者の番", /求人が一覧に出なくなった/)).toBe(card);
  });

  it("本人の番の列のカードは何を待っているか、終わりの列のカードは終わり方を出す", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              waiting2,
              makeItem({
                id: "op-4",
                read: true,
                ...RESULT_STATE,
                summary: "結果を伝えた件。",
              }),
              done3,
              makeItem({
                id: "op-5",
                read: true,
                ...closedState("DECLINED"),
                summary: "見送った件。",
              }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /返事待ちの件/ });
    expect(cardIn("本人の番", /返事待ちの件/)).toHaveTextContent("返事待ち");
    expect(cardIn("本人の番", /結果を伝えた件/)).toHaveTextContent(
      "結果の確かめ待ち",
    );
    expect(cardIn("終わり", /完了した件/)).toHaveTextContent("解決済み");
    expect(cardIn("終わり", /見送った件/)).toHaveTextContent("見送り");
  });

  it("列の中は、業務が止まっている件、未読の件の順に上へ寄せ、同じ条件なら新しい順に並ぶ", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 1,
            items: [
              makeItem({
                id: "a",
                read: true,
                summary: "新しい既読の件。",
                filedAt: "2026-11-21T09:00:00Z",
              }),
              makeItem({
                id: "b",
                read: false,
                summary: "未読の件。",
                filedAt: "2026-11-20T09:00:00Z",
              }),
              makeItem({
                id: "c",
                read: true,
                summary: "止まっている件。",
                filedAt: "2026-11-01T09:00:00Z",
                urgency: "BLOCKED",
              }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /止まっている件/ });
    expect(
      within(column("担当者の番"))
        .getAllByRole("button")
        .map((b) => b.querySelector(".op-summary")?.textContent),
    ).toEqual(["止まっている件。", "未読の件。", "新しい既読の件。"]);
  });

  it("カードを押すとパネルが開いて URL が変わり、パネルの先頭にフォーカスが移る。閉じるとカードに戻る", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard();
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).not.toBeInTheDocument();
    await user.click(card);

    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    expect(window.location.pathname).toBe("/operator/op-1");
    await waitFor(() => expect(panel).toHaveFocus());
    // 開いているカードは強調し、ボードは見えたまま
    expect(card).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("region", { name: "受付一覧" })).toBeVisible();

    await user.click(within(panel).getByRole("button", { name: "閉じる" }));
    expect(window.location.pathname).toBe("/operator");
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).not.toBeInTheDocument();
    await waitFor(() => expect(card).toHaveFocus());
    expect(card).not.toHaveAttribute("aria-current");
  });

  it("Enter でカードを開け、Esc でパネルを閉じて URL が戻る", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard();
    card.focus();
    await user.keyboard("{Enter}");
    await screen.findByRole("region", { name: "問い合わせの詳細" });
    expect(window.location.pathname).toBe("/operator/op-1");

    await user.keyboard("{Escape}");
    await waitFor(() => expect(window.location.pathname).toBe("/operator"));
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).not.toBeInTheDocument();
  });
});

describe("担当者のボード: ドラッグは操作を始める合図", () => {
  it("「本人の番」に落とすと、状態は変えずにパネルを開き、「本人に送る」の入力欄にフォーカスを移す", async () => {
    const { calls, card } = await renderBoard();

    dragTo(card, "本人の番");

    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    await waitFor(() =>
      expect(within(panel).getByLabelText("本人に送る")).toHaveFocus(),
    );
    expect(window.location.pathname).toBe("/operator/op-1");
    // 状態を変える API は呼ばない。カードも動かない
    expect(writes(calls)).toHaveLength(0);
    expect(
      cardIn("担当者の番", /求人が一覧に出なくなった/),
    ).toBeInTheDocument();
    expect(cardIn("本人の番", /求人が一覧に出なくなった/)).toBeNull();
    // 読み上げにも伝える
    expect(
      screen.getByText("「本人に送る」の入力欄を開きました"),
    ).toBeInTheDocument();
  });

  it("「終わり」に落とすと、「見送る」を開いて、理由の入力欄にフォーカスを移す。API は呼ばない", async () => {
    const { calls, card } = await renderBoard();

    dragTo(card, "終わり");

    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    const reason = await within(panel).findByLabelText("理由");
    await waitFor(() => expect(reason).toHaveFocus());
    expect(reason.closest("details")).toHaveAttribute("open");
    expect(writes(calls)).toHaveLength(0);
    expect(cardIn("終わり", /求人が一覧に出なくなった/)).toBeNull();
    expect(
      screen.getByText("「見送る」の入力欄を開きました"),
    ).toBeInTheDocument();
  });

  it("すでにパネルが開いているカードをもう一度落としても、入力欄にフォーカスが移る", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard();
    const panel = await openPanel(user, card);
    const box = within(panel).getByLabelText("本人に送る");
    expect(box).not.toHaveFocus();

    dragTo(card, "本人の番");

    await waitFor(() => expect(box).toHaveFocus());
    expect(writes(calls)).toHaveLength(0);
  });

  it("別の件のパネルが開いているときに落とすと、落とした件の詳細を開いて入力欄に移る", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard();
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    await screen.findByText("いつからですか。");

    dragTo(card, "本人の番");

    const box = await screen.findByLabelText("本人に送る");
    await waitFor(() => expect(box).toHaveFocus());
    expect(window.location.pathname).toBe("/operator/op-1");
  });

  it("掴んでいる間は、落とせる列（本人の番、終わり）だけを示し、掴んだ列自身は示さない。離したら戻る", async () => {
    const { card } = await renderBoard();
    const item = card.closest("li")!;
    expect(item).toHaveAttribute("draggable", "true");

    fireEvent.dragStart(item);
    expect(item).toHaveClass("dragging");
    expect(column("担当者の番")).not.toHaveClass("droppable");
    expect(column("本人の番")).toHaveClass("droppable");
    expect(column("終わり")).toHaveClass("droppable");

    fireEvent.dragOver(column("終わり"));
    expect(column("終わり")).toHaveClass("over");
    expect(column("本人の番")).not.toHaveClass("over");

    fireEvent.dragEnd(item);
    expect(item).not.toHaveClass("dragging");
    expect(column("終わり")).not.toHaveClass("droppable");
    expect(column("終わり")).not.toHaveClass("over");
  });

  it("落とせない動かし方（本人の番や終わりのカード、同じ列）では、何も強調せず、何も起きない", async () => {
    const { calls, card } = await renderBoard();

    // 本人の番のカードは、どの列にも落とせない
    const waiting = screen.getByRole("button", { name: /返事待ちの件/ });
    fireEvent.dragStart(waiting.closest("li")!);
    for (const title of ["担当者の番", "本人の番", "終わり"]) {
      expect(column(title)).not.toHaveClass("droppable");
    }
    fireEvent.dragEnd(waiting.closest("li")!);
    dragTo(waiting, "担当者の番");
    dragTo(waiting, "終わり");
    // 終わりのカードも同じ
    dragTo(screen.getByRole("button", { name: /完了した件/ }), "担当者の番");
    // 担当者の番のカードを、同じ列に落とす
    dragTo(card, "担当者の番");

    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).not.toBeInTheDocument();
    expect(window.location.pathname).toBe("/operator");
    expect(writes(calls)).toHaveLength(0);
    expect(callsTo(calls, "GET", "/api/operator/inquiries/op-1")).toHaveLength(
      0,
    );
  });
});

describe("担当者のパネル", () => {
  it("いまの状態と区間を出し、担当者の番なら区間を切り替えられる。切り替えるとカードにも区間が出る", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "PUT /api/operator/inquiries/op-1/phase": {
        body: makeDetail({ read: true, messages: [], phase: "ACT" }),
      },
    });
    const panel = await openPanel(user, card);

    expect(
      within(panel).getByText("担当者の番（担当者が受けている）"),
    ).toBeInTheDocument();
    const group = within(panel).getByRole("group", {
      name: "区間を切り替える",
    });
    expect(
      within(group)
        .getAllByRole("button")
        .map((b) => b.textContent),
    ).toEqual(["分かる", "決まる", "行われる"]);
    expect(
      within(group).getByRole("button", { name: "決まる" }),
    ).toHaveAttribute("aria-pressed", "true");

    await user.click(within(group).getByRole("button", { name: "行われる" }));

    await waitFor(() =>
      expect(
        within(group).getByRole("button", { name: "行われる" }),
      ).toHaveAttribute("aria-pressed", "true"),
    );
    expect(
      callsTo(calls, "PUT", "/api/operator/inquiries/op-1/phase")[0].body,
    ).toEqual({ phase: "ACT" });
    expect(cardIn("担当者の番", /求人が一覧に出なくなった/)).toHaveTextContent(
      "区間 行われる",
    );
  });

  it("担当者の番でないときは、区間を切り替えられず、入力欄の代わりに待っていることを示す", async () => {
    const user = userEvent.setup();
    await renderBoard();
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });

    expect(
      await within(panel).findByText("本人の返事を待っています。"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("本人の番（返事待ち）")).toBeInTheDocument();
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    expect(within(panel).queryByText("見送る")).not.toBeInTheDocument();
    const group = within(panel).getByRole("group", {
      name: "区間を切り替える",
    });
    for (const button of within(group).getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
    expect(
      within(panel).getByText(/区間を切り替えられるのは、担当者の番のときだけ/),
    ).toBeInTheDocument();
  });

  it("終わった件は、終わり方を示し、置き直しもできない", async () => {
    const user = userEvent.setup();
    await renderBoard({
      "GET /api/operator/inquiries/op-3": {
        body: makeDetail({
          id: "op-3",
          read: true,
          ...closedState("DECLINED"),
          declined: { reasonKind: "OTHER", reason: "対象外です" },
          messages: [],
        }),
      },
    });
    await user.click(screen.getByRole("button", { name: /完了した件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });

    expect(
      await within(panel).findByText("終わり（見送り）"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(/この問い合わせは終わっています（見送り）/),
    ).toBeInTheDocument();
    expect(within(panel).queryByText("置き直す")).not.toBeInTheDocument();
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
  });

  it("「聞き返す」で kind: ASK を送り、本人の番に移る。やり取りに質問の見出しが付く", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "POST /api/operator/inquiries/op-1/replies": {
        body: makeDetail({
          read: true,
          ...ASKER_REPLY_STATE,
          messages: [makeMessage({ id: "m1", body: "いつからですか。" })],
        }),
      },
    });
    const panel = await openPanel(user, card);

    const send = within(panel).getByRole("button", { name: "聞き返す" });
    const result = within(panel).getByRole("button", { name: "結果を伝える" });
    expect(send).toBeDisabled();
    expect(result).toBeDisabled();
    await user.type(
      within(panel).getByLabelText("本人に送る"),
      "いつからですか。",
    );
    await user.click(send);

    await waitFor(() =>
      expect(
        callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies")[0].body,
      ).toEqual({ kind: "ASK", body: "いつからですか。" }),
    );
    const thread = await within(panel).findByRole("list", { name: "やり取り" });
    const reply = within(thread).getByText("いつからですか。").closest("li")!;
    expect(reply).toHaveClass("by-operator", "mine");
    expect(within(reply).getByText("質問")).toHaveClass("message-kind");
    // 本人の番になったので、入力欄は待っていることの表示に変わり、カードは本人の番の列に移る
    expect(
      await within(panel).findByText("本人の返事を待っています。"),
    ).toBeInTheDocument();
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    expect(cardIn("本人の番", /求人が一覧に出なくなった/)).toHaveTextContent(
      "返事待ち",
    );
    expect(cardIn("担当者の番", /求人が一覧に出なくなった/)).toBeNull();
    expect(
      await screen.findByText(
        /「求人が一覧に出なくなった.*」を本人の番に移しました/,
      ),
    ).toBeInTheDocument();
  });

  it("「結果を伝える」で kind: RESULT を送る。違いは、ボタンの下に1行ずつ示す", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "POST /api/operator/inquiries/op-1/replies": {
        body: makeDetail({
          read: true,
          ...RESULT_STATE,
          messages: [
            makeMessage({ id: "m1", kind: "RESULT", body: "直しました。" }),
          ],
        }),
      },
    });
    const panel = await openPanel(user, card);

    expect(
      within(panel).getByText("聞き返す: 本人の返事を待ちます"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(/結果を伝える: 本人が解決したかを確かめます/),
    ).toBeInTheDocument();
    await user.type(within(panel).getByLabelText("本人に送る"), "直しました。");
    await user.click(
      within(panel).getByRole("button", { name: "結果を伝える" }),
    );

    await waitFor(() =>
      expect(
        callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies")[0].body,
      ).toEqual({ kind: "RESULT", body: "直しました。" }),
    );
    const thread = await within(panel).findByRole("list", { name: "やり取り" });
    expect(within(thread).getByText("結果")).toBeInTheDocument();
    expect(
      await within(panel).findByText("本人が結果を確かめるのを待っています。"),
    ).toBeInTheDocument();
  });

  it("空の発言は送れない。失敗したら、その旨を示して入力を残す", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "POST /api/operator/inquiries/op-1/replies": {
        status: 500,
        body: { code: "X", message: "x" },
      },
    });
    const panel = await openPanel(user, card);

    const box = within(panel).getByLabelText("本人に送る");
    const send = within(panel).getByRole("button", { name: "聞き返す" });
    await user.type(box, "  ");
    expect(send).toBeDisabled();
    expect(
      within(panel).getByRole("button", { name: "結果を伝える" }),
    ).toBeDisabled();

    await user.type(box, "確認します");
    await user.click(send);
    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(box).toHaveValue("  確認します");
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies"),
    ).toHaveLength(1);
  });

  it("見送る: 理由の種類と文章を送り、終わりの列に移る", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "POST /api/operator/inquiries/op-1/decline": {
        body: makeDetail({
          read: true,
          ...closedState("DECLINED"),
          declined: { reasonKind: "KNOWN_LIMITATION", reason: "仕様です。" },
          messages: [
            makeMessage({ id: "m1", kind: "DECLINE", body: "仕様です。" }),
          ],
        }),
      },
    });
    const panel = await openPanel(user, card);

    const reason = within(panel).getByLabelText("理由");
    const details = reason.closest("details")!;
    expect(details).not.toHaveAttribute("open");
    await user.click(within(details).getByText("見送る"));
    expect(details).toHaveAttribute("open");
    const submit = within(details).getByRole("button", {
      name: "見送りとして終える",
    });
    expect(submit).toBeDisabled();
    const kind = within(details).getByLabelText("理由の種類");
    expect(
      within(kind)
        .getAllByRole("option")
        .map((o) => o.textContent),
    ).toEqual(["再現しない", "既知の制限", "対象外", "重複", "その他"]);
    await user.selectOptions(kind, "既知の制限");
    await user.type(reason, "仕様です。");
    await user.click(submit);

    await waitFor(() =>
      expect(
        callsTo(calls, "POST", "/api/operator/inquiries/op-1/decline")[0].body,
      ).toEqual({ reasonKind: "KNOWN_LIMITATION", reason: "仕様です。" }),
    );
    await waitFor(() =>
      expect(cardIn("終わり", /求人が一覧に出なくなった/)).toHaveTextContent(
        "見送り",
      ),
    );
    expect(
      await within(panel).findByText("終わり（見送り）"),
    ).toBeInTheDocument();
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    const thread = within(panel).getByRole("list", { name: "やり取り" });
    expect(within(thread).getByText("見送りの連絡")).toBeInTheDocument();
  });

  it("置き直す: システムを変えると機能の選択肢が変わり、選んだ内容を PUT する", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "PUT /api/operator/inquiries/op-1/placement": {
        body: makeDetail({
          read: true,
          messages: [],
          system: { id: "billing", label: "請求管理", by: "OPERATOR" },
          function: { id: "invoice", label: "請求書", by: "OPERATOR" },
        }),
      },
    });
    const panel = await openPanel(user, card);

    const form = within(panel)
      .getByText("置き直す", { selector: "summary" })
      .closest("details")!;
    await user.click(
      within(form).getByText("置き直す", { selector: "summary" }),
    );
    const type = within(form).getByLabelText("種類");
    const system = within(form).getByLabelText("システム");
    const fn = within(form).getByLabelText("機能");
    const optionsOf = (select: HTMLElement) =>
      within(select)
        .getAllByRole("option")
        .map((o) => o.textContent);
    // いまの置き場所が選ばれている
    expect(type).toHaveValue("LOOKS_WRONG");
    expect(system).toHaveValue("job-admin");
    expect(optionsOf(type)).toEqual([
      "未定",
      ...TYPE_OPTIONS.map((t) => t.label),
    ]);
    expect(optionsOf(fn)).toEqual(["未定", "求人の掲載", "応募者の一覧"]);

    await user.selectOptions(system, "billing");
    expect(optionsOf(fn)).toEqual(["未定", "請求書"]);
    expect(fn).toHaveValue("");
    await user.selectOptions(fn, "invoice");
    await user.click(within(form).getByRole("button", { name: "置き直す" }));

    await waitFor(() =>
      expect(
        callsTo(calls, "PUT", "/api/operator/inquiries/op-1/placement")[0].body,
      ).toEqual({
        type: "LOOKS_WRONG",
        systemId: "billing",
        functionId: "invoice",
      }),
    );
    // 置き直した内容が、パネルの上部とカードに反映される
    await waitFor(() =>
      expect(
        within(panel).getByText("請求書（担当者が置き直しました）"),
      ).toBeInTheDocument(),
    );
    expect(cardIn("担当者の番", /求人が一覧に出なくなった/)).toHaveTextContent(
      "請求管理 / 請求書",
    );
  });

  it("置き場所を「未定」に戻すと、null を送る", async () => {
    const user = userEvent.setup();
    const { calls, card } = await renderBoard({
      "PUT /api/operator/inquiries/op-1/placement": {
        body: makeDetail({ read: true, messages: [], system: null }),
      },
    });
    const panel = await openPanel(user, card);
    const form = within(panel)
      .getByText("置き直す", { selector: "summary" })
      .closest("details")!;
    await user.click(
      within(form).getByText("置き直す", { selector: "summary" }),
    );

    await user.selectOptions(within(form).getByLabelText("システム"), "");
    await user.click(within(form).getByRole("button", { name: "置き直す" }));

    await waitFor(() =>
      expect(
        callsTo(calls, "PUT", "/api/operator/inquiries/op-1/placement")[0].body,
      ).toEqual({ type: "LOOKS_WRONG", systemId: null, functionId: null }),
    );
  });

  it("状態の履歴は折りたたんでおき、開くと、いつ・誰が・何をして・誰の番になったかを古い順に出す", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard({
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          messages: [],
          history: [
            {
              at: "2026-11-21T04:00:00Z",
              actor: "ASKER",
              event: "CREATED",
              phase: "INTAKE",
              turn: "ASKER",
              awaiting: "INTAKE_ANSWER",
              ending: null,
            },
            {
              at: "2026-11-21T05:00:00Z",
              actor: "ASKER",
              event: "HANDED_OVER",
              phase: "DECIDE",
              turn: "OPERATOR",
              awaiting: "HANDLING",
              ending: null,
            },
            {
              at: "2026-11-21T05:30:00Z",
              actor: "OPERATOR",
              event: "PLACEMENT_CHANGED",
              phase: "DECIDE",
              turn: "OPERATOR",
              awaiting: "HANDLING",
              ending: null,
              detail: {
                before: {
                  type: "おかしいかもしれない",
                  system: "システムA",
                  function: null,
                },
                after: {
                  type: "やってほしい",
                  system: "システムA",
                  function: "機能A-1",
                },
              },
            },
            {
              at: "2026-11-21T06:00:00Z",
              actor: "OPERATOR",
              event: "ASKED_BACK",
              phase: "DECIDE",
              turn: "ASKER",
              awaiting: "ASKER_REPLY",
              ending: null,
            },
          ],
        }),
      },
    });
    const panel = await openPanel(user, card);

    const summary = within(panel).getByText("状態の履歴");
    const details = summary.closest("details")!;
    expect(details).not.toHaveAttribute("open");
    await user.click(summary);
    const rows = within(details).getAllByRole("listitem");
    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent("本人問い合わせができた");
    expect(rows[0]).toHaveTextContent("本人の番（入口の質問に答えている）");
    expect(rows[1]).toHaveTextContent("担当者に渡した");
    expect(rows[1]).toHaveTextContent("担当者の番（担当者が受けている）");
    // 置き直しの行には、変える前と後を示す（決まっていない項目は省く）
    expect(rows[2]).toHaveTextContent("置き直した");
    expect(rows[2]).toHaveTextContent(
      "おかしいかもしれない・システムA → やってほしい・システムA・機能A-1",
    );
    expect(rows[1]).not.toHaveTextContent("→ やってほしい");
    expect(rows[3]).toHaveTextContent("担当者");
    expect(rows[3]).toHaveTextContent("本人に聞き返した");
    expect(rows[3]).toHaveTextContent("本人の番（返事待ち）");
  });

  it("履歴が空なら、その旨を示す", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard();
    const panel = await openPanel(user, card);
    await user.click(within(panel).getByText("状態の履歴"));
    expect(
      within(panel).getByText("履歴はまだありません。"),
    ).toBeInTheDocument();
  });

  it("messages が欠けた詳細でも表示できる", async () => {
    const user = userEvent.setup();
    const legacy = { ...makeDetail({ read: true }) } as Record<string, unknown>;
    delete legacy.messages;
    delete legacy.trace;
    const { card } = await renderBoard({
      "GET /api/operator/inquiries/op-1": { body: legacy },
    });
    const panel = await openPanel(user, card);

    expect(
      within(panel).getByText("やり取りはまだありません。"),
    ).toBeInTheDocument();
  });
});

describe("担当者のパネル: 返事がないときの予定", () => {
  const REMIND_AT = new Date(2026, 10, 24, 9, 0).toISOString();
  const REMINDED_AT = new Date(2026, 10, 24, 12, 0).toISOString();
  const CLOSES_AT = new Date(2026, 11, 1, 12, 0).toISOString();

  const waitingForResult = (noReply: object | null) =>
    makeDetail({
      id: "op-2",
      read: true,
      ...RESULT_STATE,
      messages: [],
      noReply: noReply as never,
    });

  async function openWaiting(noReply: object | null) {
    const user = userEvent.setup();
    await renderBoard({
      "GET /api/operator/inquiries/op-2": { body: waitingForResult(noReply) },
    });
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    return await screen.findByRole("region", { name: "問い合わせの詳細" });
  }

  it("知らせる前は、知らせる予定の日を1行で出す", async () => {
    const panel = await openWaiting({
      endsAs: "ANSWERED",
      remindAt: REMIND_AT,
      remindedAt: null,
      closesAt: null,
    });

    expect(
      await within(panel).findByText(
        "返事がなければ、11月24日に本人へ知らせます",
      ),
    ).toBeInTheDocument();
    expect(within(panel).queryByText(/知らせました/)).not.toBeInTheDocument();
  });

  it("知らせた後は、知らせた日と、終わる予定の日を1行で出す", async () => {
    const panel = await openWaiting({
      endsAs: "ANSWERED",
      remindAt: REMIND_AT,
      remindedAt: REMINDED_AT,
      closesAt: CLOSES_AT,
    });

    expect(
      await within(panel).findByText(
        "11月24日に本人へ知らせました。返事がなければ、12月1日に「回答済み」で終わります",
      ),
    ).toBeInTheDocument();
    expect(within(panel).queryByText(/知らせます$/)).not.toBeInTheDocument();
  });

  it("聞き返しの返事を待つ件は、終わり方が「取り下げ」の行を出す（知らせる前の行は共通）", async () => {
    const user = userEvent.setup();
    const waitingForReply = (noReply: object) =>
      makeDetail({
        id: "op-2",
        read: true,
        ...ASKER_REPLY_STATE,
        messages: [],
        noReply: noReply as never,
      });
    await renderBoard({
      "GET /api/operator/inquiries/op-2": {
        body: waitingForReply({
          endsAs: "WITHDRAWN",
          remindAt: REMIND_AT,
          remindedAt: REMINDED_AT,
          closesAt: CLOSES_AT,
        }),
      },
    });
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });

    expect(
      await within(panel).findByText(
        "11月24日に本人へ知らせました。返事がなければ、12月1日に「取り下げ」で終わります",
      ),
    ).toBeInTheDocument();
    expect(within(panel).queryByText(/「回答済み」で終わります/)).toBeNull();
  });

  it("聞き返しの返事を待つ件の、知らせる前の行は、結果の確かめと同じ文言", async () => {
    const user = userEvent.setup();
    await renderBoard({
      "GET /api/operator/inquiries/op-2": {
        body: makeDetail({
          id: "op-2",
          read: true,
          ...ASKER_REPLY_STATE,
          messages: [],
          noReply: {
            endsAs: "WITHDRAWN",
            remindAt: REMIND_AT,
            remindedAt: null,
            closesAt: null,
          },
        }),
      },
    });
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });

    expect(
      await within(panel).findByText(
        "返事がなければ、11月24日に本人へ知らせます",
      ),
    ).toBeInTheDocument();
  });

  it("時間で取り下げになった件の履歴は、起こした人が「期間」の「取り下げた」として出る", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard({
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          messages: [],
          ...closedState("WITHDRAWN"),
          closedBy: "TIME",
          history: [
            {
              at: CLOSES_AT,
              actor: "TIME",
              event: "WITHDRAWN",
              phase: "CLOSED",
              turn: null,
              awaiting: null,
              ending: "WITHDRAWN",
            },
          ],
        }),
      },
    });
    const panel = await openPanel(user, card);
    await user.click(within(panel).getByText("状態の履歴"));

    const row = within(
      within(panel).getByText("状態の履歴").closest("details")!,
    ).getByRole("listitem");
    expect(row).toHaveTextContent("期間");
    expect(row).toHaveTextContent("取り下げた");
    expect(row).toHaveTextContent("終わり（取り下げ）");
  });

  it("noReply が null の件（担当者の番など）には出さない", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard();
    const panel = await openPanel(user, card);

    expect(within(panel).queryByText(/本人へ知らせ/)).not.toBeInTheDocument();
    expect(document.querySelector(".op-no-reply")).toBeNull();
  });

  it("状態の履歴に、期間が起こした「返事がないことを本人に知らせた」「返事がないまま終わった」が出る", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard({
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          messages: [],
          history: [
            {
              at: REMINDED_AT,
              actor: "TIME",
              event: "REMINDED",
              phase: "KNOW",
              turn: "ASKER",
              awaiting: "RESULT_CONFIRMATION",
              ending: null,
            },
            {
              at: CLOSES_AT,
              actor: "TIME",
              event: "ANSWERED",
              phase: "CLOSED",
              turn: null,
              awaiting: null,
              ending: "ANSWERED",
            },
          ],
        }),
      },
    });
    const panel = await openPanel(user, card);
    await user.click(within(panel).getByText("状態の履歴"));

    const rows = within(
      within(panel).getByText("状態の履歴").closest("details")!,
    ).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("期間");
    expect(rows[0]).toHaveTextContent("返事がないことを本人に知らせた");
    expect(rows[0]).toHaveTextContent("本人の番（結果の確かめ待ち）");
    expect(rows[1]).toHaveTextContent("期間");
    expect(rows[1]).toHaveTextContent("返事がないまま終わった");
    expect(rows[1]).toHaveTextContent("終わり（回答済み）");
  });
});

describe("担当者のパネル: 渡った理由", () => {
  it.each([
    ["SENT_NOW", "本人が、答えを待たずに送った"],
    ["UNRESOLVED", "本人が、解決していないと答えた"],
    ["BLOCKED", "業務が止まっている"],
    ["ROUTE", "この種類は、担当者が判断する"],
    ["NO_ANSWER", "AI が答えられなかった"],
    ["TIMEOUT", "AI の調査が、時間内に終わらなかった"],
    ["REOPENED", "本人が、終わった件に追記した"],
  ] as const)(
    "担当者の番のとき、「いまの状態」に「渡った理由」の行を出す（%s）",
    async (reason, text) => {
      const user = userEvent.setup();
      const { card } = await renderBoard({
        "GET /api/operator/inquiries/op-1": {
          body: makeDetail({
            read: true,
            messages: [],
            handoverReason: reason,
          }),
        },
      });
      const panel = await openPanel(user, card);

      expect(
        within(panel).getByText(`渡った理由: ${text}`),
      ).toBeInTheDocument();
      const state = within(panel).getByText("いまの状態").closest("p")!;
      const line = within(panel).getByText(`渡った理由: ${text}`);
      expect(
        state.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    },
  );

  it("担当者の番でないとき（本人の番、終わり）と、理由が null のときは出さない", async () => {
    const user = userEvent.setup();
    await renderBoard({
      "GET /api/operator/inquiries/op-2": {
        body: makeDetail({
          id: "op-2",
          read: true,
          ...ASKER_REPLY_STATE,
          messages: [],
          handoverReason: "NO_ANSWER",
        }),
      },
    });
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    await within(panel).findByText("本人の番（返事待ち）");
    expect(within(panel).queryByText(/渡った理由/)).not.toBeInTheDocument();
  });

  it("理由が null の件には出さない", async () => {
    const user = userEvent.setup();
    const { card } = await renderBoard({
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({ read: true, messages: [], handoverReason: null }),
      },
    });
    const panel = await openPanel(user, card);
    expect(within(panel).queryByText(/渡った理由/)).not.toBeInTheDocument();
  });
});

describe("担当者のパネル: 調査の状況と調べ直す", () => {
  const investigation = (overrides: object = {}) => ({
    status: "NOT_RUN",
    audience: null,
    startedAt: null,
    finishedAt: null,
    unconfirmedQuestion: false,
    ...overrides,
  });

  /** 指定の調査の状況で、1件目のパネルを開く。 */
  async function openWith(
    inv: object,
    detail: Partial<ReturnType<typeof makeDetail>> = {},
    extra: Parameters<typeof installFakeFetch>[0] = {},
  ) {
    const user = userEvent.setup();
    const board = await renderBoard({
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          messages: [],
          investigation: inv as never,
          ...detail,
        }),
      },
      ...extra,
    });
    const panel = await openPanel(user, board.card);
    return { ...board, user, panel };
  }

  const line = (panel: HTMLElement, text: string) =>
    within(panel).getByText(text);

  it.each([
    [{ status: "NOT_RUN" }, "AI は調べていません。"],
    [
      { status: "RUNNING", startedAt: "2026-11-22T03:00:00Z" },
      "AI が調べています。終わると、ここに結果が出ます。",
    ],
    [{ status: "DONE", audience: "ASKER" }, "本人も、この結果を見ています。"],
    [
      { status: "DONE", audience: "OPERATOR" },
      "この結果は、本人には見せていません。",
    ],
    [{ status: "FAILED" }, "AI の調査は失敗しました。"],
  ])(
    "調査の状況の1行が、「調べた結果」の見出しのすぐ下に出る（%j）",
    async (inv, text) => {
      const { panel } = await openWith(investigation(inv));

      const heading = within(panel).getByRole("heading", {
        name: "調べた結果",
      });
      const status = line(panel, text);
      expect(
        heading.compareDocumentPosition(status) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(heading.nextElementSibling).toBe(status);
      expect(status).toHaveAttribute("aria-live", "polite");
    },
  );

  it("調べた結果がまだない件（answer が null）でも、節と状況の行を出す", async () => {
    const { panel } = await openWith(investigation({ status: "FAILED" }), {
      answer: null,
    });

    expect(
      within(panel).getByRole("heading", { name: "調べた結果" }),
    ).toBeInTheDocument();
    expect(line(panel, "AI の調査は失敗しました。")).toBeInTheDocument();
    expect(
      within(panel).getByText(/調べた結果はありません/),
    ).toBeInTheDocument();
  });

  it("調査のもとにした言い直しを本人が確かめていないときは、注記を足す", async () => {
    const { panel } = await openWith(
      investigation({ status: "RUNNING", unconfirmedQuestion: true }),
    );
    expect(
      within(panel).getByText(
        "本人が確かめていない言い直しをもとに調べています。",
      ),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const second = await openWith(
      investigation({ status: "DONE", audience: "OPERATOR" }),
    );
    expect(
      within(second.panel).queryByText(/確かめていない言い直し/),
    ).not.toBeInTheDocument();
  });

  it("調べたことがなければ「AI に調べさせる」、あれば「調べ直す」。ボタンの下に説明を添える", async () => {
    const { panel } = await openWith(investigation({ status: "NOT_RUN" }));
    expect(
      within(panel).getByRole("button", { name: "AI に調べさせる" }),
    ).toBeEnabled();
    expect(
      within(panel).queryByRole("button", { name: "調べ直す" }),
    ).not.toBeInTheDocument();
    expect(
      within(panel).getByText(
        "番は変わりません。結果は、担当者だけに見えます。",
      ),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    for (const status of ["DONE", "FAILED"]) {
      const next = await openWith(
        investigation({ status, audience: status === "DONE" ? "ASKER" : null }),
      );
      expect(
        within(next.panel).getByRole("button", { name: "調べ直す" }),
      ).toBeEnabled();
      expect(
        within(next.panel).queryByRole("button", { name: "AI に調べさせる" }),
      ).not.toBeInTheDocument();
      cleanup();
      vi.unstubAllGlobals();
    }
  });

  it("調べている最中、言い直しが無い件、終わった件には、ボタンを出さない", async () => {
    const running = await openWith(investigation({ status: "RUNNING" }));
    expect(
      within(running.panel).queryByRole("button", {
        name: /調べ直す|AI に調べさせる/,
      }),
    ).not.toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const noRestatement = await openWith(investigation(), { structured: null });
    expect(
      within(noRestatement.panel).queryByRole("button", {
        name: /調べ直す|AI に調べさせる/,
      }),
    ).not.toBeInTheDocument();
    // 状況の行は出す
    expect(
      line(noRestatement.panel, "AI は調べていません。"),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const ended = await openWith(
      investigation({ status: "DONE", audience: "ASKER" }),
      {
        ...closedState("RESOLVED"),
      },
    );
    expect(
      within(ended.panel).queryByRole("button", {
        name: /調べ直す|AI に調べさせる/,
      }),
    ).not.toBeInTheDocument();
  });

  it("押すと POST …/investigation を呼び、返ってきた詳細で状況が変わる。番は変わらない", async () => {
    const { calls, user, panel } = await openWith(
      investigation({ status: "DONE", audience: "ASKER" }),
      {},
      {
        "POST /api/operator/inquiries/op-1/investigation": {
          body: makeDetail({
            read: true,
            messages: [],
            investigation: investigation({
              status: "RUNNING",
              startedAt: "2026-11-22T03:00:00Z",
            }) as never,
          }),
        },
      },
    );

    await user.click(within(panel).getByRole("button", { name: "調べ直す" }));

    const post = callsTo(
      calls,
      "POST",
      "/api/operator/inquiries/op-1/investigation",
    );
    expect(post).toHaveLength(1);
    expect(post[0].body).toBeUndefined();
    expect(
      await within(panel).findByText(
        "AI が調べています。終わると、ここに結果が出ます。",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", { name: "調べ直す" }),
    ).not.toBeInTheDocument();
    // 番は変わらず、担当者の番のまま
    expect(
      cardIn("担当者の番", /求人が一覧に出なくなった/),
    ).toBeInTheDocument();
  });

  it("押している間は二重に押せない", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { calls, user, panel } = await openWith(investigation());
    const fake = vi.mocked(fetch);
    const base = fake.getMockImplementation()!;
    fake.mockImplementation(async (input, init) => {
      if (String(input).endsWith("/investigation") && init?.method === "POST") {
        calls.push({
          method: "POST",
          path: String(input),
          body: undefined,
          headers: {},
        });
        await gate;
        return new Response(
          JSON.stringify(
            makeDetail({
              read: true,
              messages: [],
              investigation: investigation({ status: "RUNNING" }) as never,
            }),
          ),
          { status: 200 },
        );
      }
      return base(input, init);
    });

    const button = within(panel).getByRole("button", {
      name: "AI に調べさせる",
    });
    await user.click(button);
    expect(button).toBeDisabled();
    await user.click(button);
    release();

    await within(panel).findByText(
      "AI が調べています。終わると、ここに結果が出ます。",
    );
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/investigation"),
    ).toHaveLength(1);
  });

  it("失敗（INVALID_STATE など）は、既存のエラーの出し方で出す", async () => {
    const { user, panel } = await openWith(
      investigation({ status: "DONE", audience: "ASKER" }),
      {},
      {
        "POST /api/operator/inquiries/op-1/investigation": {
          status: 409,
          body: { code: "INVALID_STATE", message: "x" },
        },
      },
    );

    await user.click(within(panel).getByRole("button", { name: "調べ直す" }));

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    // 状況はそのまま、ボタンはまた押せる
    expect(
      within(panel).getByRole("button", { name: "調べ直す" }),
    ).toBeEnabled();
  });

  it("開いているパネルの件に updated が届いたら、詳細を取り直して結果を出す。入力の途中の文章は消えない", async () => {
    const stream = new FakeOperatorStream();
    const user = userEvent.setup();
    let gets = 0;
    const { calls, card } = await renderBoard(
      {
        "GET /api/operator/inquiries/op-1": () => ({
          body: makeDetail({
            read: true,
            messages: [],
            investigation: (++gets === 1
              ? investigation({ status: "RUNNING" })
              : investigation({
                  status: "DONE",
                  audience: "OPERATOR",
                })) as never,
          }),
        }),
      },
      stream,
    );
    const panel = await openPanel(user, card);
    expect(
      line(panel, "AI が調べています。終わると、ここに結果が出ます。"),
    ).toBeInTheDocument();

    await user.type(within(panel).getByLabelText("本人に送る"), "確認中です");
    await user.click(
      within(panel).getByText("見送る", { selector: "summary" }),
    );
    await user.type(within(panel).getByLabelText("理由"), "仕様です");

    act(() => stream.latest.handlers.onUpdated({ ...mine1 }));

    expect(
      await within(panel).findByText("この結果は、本人には見せていません。"),
    ).toBeInTheDocument();
    expect(callsTo(calls, "GET", "/api/operator/inquiries/op-1")).toHaveLength(
      2,
    );
    expect(within(panel).getByLabelText("本人に送る")).toHaveValue(
      "確認中です",
    );
    expect(within(panel).getByLabelText("理由")).toHaveValue("仕様です");
  });
});

describe("担当者のパネル: AI の案", () => {
  const judgment = {
    text: "資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。",
    needsConsultation: false,
    consultationReason: null,
    basedOn: ["求人は、掲載期限の時刻ちょうどから期限切れとして扱われる。"],
  };
  const procedure = {
    steps: [
      {
        target: "求人管理画面の求人の編集",
        action: "掲載期限を、翌日の日付に変える",
        check: "応募者向けの一覧に、求人が表示されること",
      },
      {
        target: "応募者向けの一覧",
        action: "一覧を開き直す",
        check: "求人が先頭に出ること",
      },
    ],
    rollback: "掲載期限を、元の日付に戻す",
    basedOn: ["期限切れの求人は、応募者向けの一覧に表示されない。"],
  };

  /** 指定の案で1件目のパネルを開く。 */
  async function openAdvice(advice: object, languages?: string[]) {
    const user = userEvent.setup();
    const board = await renderBoard({
      ...(languages === undefined
        ? {}
        : { "GET /api/config": { body: makeConfig({ languages }) } }),
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({ read: true, messages: [], advice: advice as never }),
      },
    });
    const panel = await openPanel(user, board.card);
    return { ...board, user, panel };
  }
  const heading = (panel: HTMLElement) =>
    within(panel).queryByRole("heading", { name: /AI の案/ });

  it("作っていない（NOT_RUN）件と、案が1つもない（DONE）件には、節を出さない", async () => {
    const { panel } = await openAdvice({
      status: "NOT_RUN",
      judgment: null,
      procedure: null,
    });
    expect(heading(panel)).not.toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const done = await openAdvice({
      status: "DONE",
      judgment: null,
      procedure: null,
    });
    expect(heading(done.panel)).not.toBeInTheDocument();
  });

  it("作っている最中は、見出しと「AI が案を作っています。」を出す", async () => {
    const { panel } = await openAdvice({
      status: "RUNNING",
      judgment: null,
      procedure: null,
    });

    expect(heading(panel)).toBeInTheDocument();
    expect(
      within(panel).getByText("AI が案を作っています。"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(
        "AI が、上の事実から考えた案です。決めるのは担当者です。",
      ),
    ).toBeInTheDocument();
  });

  it("失敗したときは、「AI の案は作れませんでした。」だけを示す", async () => {
    const { panel } = await openAdvice({
      status: "FAILED",
      judgment: null,
      procedure: null,
    });

    expect(
      within(panel).getByText("AI の案は作れませんでした。"),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("heading", { name: "判断の案" }),
    ).not.toBeInTheDocument();
  });

  it("案があるときは、点線の枠と「案」の印をつけた節を、「調べた結果」のすぐ下（やり取りの前）に出す", async () => {
    const { panel } = await openAdvice({
      status: "DONE",
      judgment,
      procedure: null,
    });

    const section = within(panel)
      .getByRole("heading", { name: /AI の案/ })
      .closest("section")!;
    expect(section).toHaveClass("op-advice");
    expect(section.querySelector(".op-advice-mark")).toHaveTextContent("案");
    const result = within(panel)
      .getByRole("heading", { name: "調べた結果" })
      .closest("section")!;
    const exchange = within(panel)
      .getByRole("heading", { name: "やり取り" })
      .closest("section")!;
    expect(result.nextElementSibling).toBe(section);
    expect(section.nextElementSibling).toBe(exchange);
  });

  it("判断の案: 文章、相談は要らなそうである旨、基づく事実を出す", async () => {
    const { panel } = await openAdvice({
      status: "DONE",
      judgment,
      procedure: null,
    });

    expect(
      within(panel).getByRole("heading", { name: "判断の案" }),
    ).toBeInTheDocument();
    expect(within(panel).getByText(judgment.text)).toBeInTheDocument();
    expect(
      within(panel).getByText("相談は要らなそうです。"),
    ).toBeInTheDocument();
    const basis = within(panel).getByText("基づく事実");
    expect(basis.nextElementSibling).toHaveTextContent(judgment.basedOn[0]);
    expect(
      within(panel).queryByRole("heading", { name: "手順の案" }),
    ).not.toBeInTheDocument();
  });

  it("判断の案: 相談が要りそうなときは、その理由を添える", async () => {
    const { panel } = await openAdvice({
      status: "DONE",
      judgment: {
        ...judgment,
        needsConsultation: true,
        consultationReason: "仕様が資料に書かれていないため",
      },
      procedure: null,
    });

    expect(
      within(panel).getByText(/仕様の持ち主への相談が要りそうです:/),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("仕様が資料に書かれていないため"),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByText("相談は要らなそうです。"),
    ).not.toBeInTheDocument();
  });

  it("手順の案: 番号つきの手順ごとに対象・操作・確かめ方、元に戻す方法、基づく事実、「実行するのは担当者です。」", async () => {
    const { panel } = await openAdvice({
      status: "DONE",
      judgment: null,
      procedure,
    });

    expect(
      within(panel).getByRole("heading", { name: "手順の案" }),
    ).toBeInTheDocument();
    const steps = within(panel)
      .getAllByRole("listitem", {
        name: "",
      })
      .filter(
        (li) => li.parentElement?.tagName === "OL" && li.closest(".op-steps"),
      );
    expect(steps).toHaveLength(2);
    expect(
      within(steps[0]).getByText("対象").nextElementSibling,
    ).toHaveTextContent("求人管理画面の求人の編集");
    expect(
      within(steps[0]).getByText("操作").nextElementSibling,
    ).toHaveTextContent("掲載期限を、翌日の日付に変える");
    expect(
      within(steps[0]).getByText("確かめ方").nextElementSibling,
    ).toHaveTextContent("応募者向けの一覧に、求人が表示されること");
    expect(steps[1]).toHaveTextContent("一覧を開き直す");
    // 番号つきの並び
    expect(steps[0].closest("ol")).toBeInTheDocument();
    const rollback = within(panel).getByText("元に戻す方法");
    expect(rollback.nextElementSibling).toHaveTextContent(
      "掲載期限を、元の日付に戻す",
    );
    expect(
      within(panel).getByText("基づく事実").nextElementSibling,
    ).toHaveTextContent("期限切れの求人は、応募者向けの一覧に表示されない。");
    expect(
      within(panel).getByText("実行するのは担当者です。"),
    ).toBeInTheDocument();
  });

  it("元に戻す方法が無い（null）手順の案には、その見出しを出さない", async () => {
    const { panel } = await openAdvice({
      status: "DONE",
      judgment: null,
      procedure: { ...procedure, rollback: null },
    });
    expect(within(panel).queryByText("元に戻す方法")).not.toBeInTheDocument();
  });

  it("案の文章には、既定の言語（config.languages の先頭）の lang が付き、基づく事実には本人の言語が付く", async () => {
    const { panel } = await openAdvice(
      { status: "DONE", judgment, procedure },
      ["en", "ja"],
    );

    expect(within(panel).getByText(judgment.text)).toHaveAttribute(
      "lang",
      "en",
    );
    expect(
      within(panel).getByText("掲載期限を、翌日の日付に変える").closest("ol"),
    ).toHaveAttribute("lang", "en");
    expect(within(panel).getByText(judgment.basedOn[0])).toHaveAttribute(
      "lang",
      "ja",
    );
    expect(
      within(panel).getByText("掲載期限を、元の日付に戻す"),
    ).toHaveAttribute("lang", "en");
  });
});

describe("担当者のパネル: 返信の下書き", () => {
  const detailWithBox = () => makeDetail({ read: true, messages: [] });

  /** 下書きの応答を登録して、1件目のパネルを開く。 */
  async function openDraft(
    draft: RouteResult | ((body: unknown) => RouteResult) = {
      body: { body: "確認させてください。いつからですか。", language: "ja" },
    },
  ) {
    const user = userEvent.setup();
    const board = await renderBoard({
      "GET /api/operator/inquiries/op-1": { body: detailWithBox() },
      "POST /api/operator/inquiries/op-1/draft": draft,
    });
    const panel = await openPanel(user, board.card);
    return { ...board, user, panel };
  }
  const draftCalls = (calls: RecordedCall[]) =>
    callsTo(calls, "POST", "/api/operator/inquiries/op-1/draft");

  it("入力欄のすぐ上に、「伝えたいこと（任意）」の1行の入力欄と、2つの小さなボタンを置く", async () => {
    const { panel } = await openDraft();

    const note = within(panel).getByLabelText("伝えたいこと（任意）");
    expect(note.tagName).toBe("INPUT");
    const ask = within(panel).getByRole("button", {
      name: "聞き返しを下書きさせる",
    });
    const result = within(panel).getByRole("button", {
      name: "結果を下書きさせる",
    });
    const box = within(panel).getByLabelText("本人に送る");
    const follows = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(note, ask)).toBe(true);
    expect(follows(ask, box)).toBe(true);
    expect(follows(result, box)).toBe(true);
    expect(ask).toHaveClass("small");
    expect(ask).toBeEnabled();
    expect(result).toBeEnabled();
    // 入力欄が空のときは、説明を出さない
    expect(
      within(panel).queryByText("入力欄が空のときに使えます"),
    ).not.toBeInTheDocument();
  });

  it("入力欄に書きかけの文章があるときは押せず、説明を添える。空に戻すと押せる", async () => {
    const { user, panel } = await openDraft();

    const box = within(panel).getByLabelText("本人に送る");
    await user.type(box, "確認します");
    expect(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    ).toBeDisabled();
    expect(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    ).toBeDisabled();
    expect(
      within(panel).getByText("入力欄が空のときに使えます"),
    ).toBeInTheDocument();

    await user.clear(box);
    expect(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    ).toBeEnabled();
    expect(
      within(panel).queryByText("入力欄が空のときに使えます"),
    ).not.toBeInTheDocument();
  });

  it("押すと POST …/draft を呼び（note が空なら kind だけ）、下書きが入力欄に入る。注意書きに本人の言語の名前が出る。何も送らない", async () => {
    const { calls, user, panel } = await openDraft({
      body: { body: "Let me check one thing. Since when?", language: "en" },
    });

    await user.click(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    );

    const box = within(panel).getByLabelText("本人に送る");
    await waitFor(() =>
      expect(box).toHaveValue("Let me check one thing. Since when?"),
    );
    expect(draftCalls(calls)).toHaveLength(1);
    expect(draftCalls(calls)[0].body).toEqual({ kind: "ASK" });
    expect(
      within(panel).getByText(
        "AI の下書きです。確かめて、直してから送ってください。本人の言語（English）で書かれています。",
      ),
    ).toBeInTheDocument();
    // 何も送らず、状態も変えない（送るボタンは担当者が選んで押す）
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies"),
    ).toHaveLength(0);
    expect(
      cardIn("担当者の番", /求人が一覧に出なくなった/),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "聞き返す" }),
    ).toBeEnabled();
    expect(
      within(panel).getByRole("button", { name: "結果を伝える" }),
    ).toBeEnabled();
    // 下書きが入ったので、もう下書きは押せない
    expect(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    ).toBeDisabled();
  });

  it("「伝えたいこと」を note として送る（結果の下書き）", async () => {
    const { calls, user, panel } = await openDraft();

    await user.type(
      within(panel).getByLabelText("伝えたいこと（任意）"),
      "  再起動で直ります  ",
    );
    await user.click(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    );

    await waitFor(() => expect(draftCalls(calls)).toHaveLength(1));
    expect(draftCalls(calls)[0].body).toEqual({
      kind: "RESULT",
      note: "再起動で直ります",
    });
  });

  it("入力欄を空にすると、注意書きは消える。送ると消え、「伝えたいこと」の欄はそのまま", async () => {
    const { user, panel } = await openDraft();
    const notice =
      "AI の下書きです。確かめて、直してから送ってください。本人の言語（日本語）で書かれています。";

    await user.type(
      within(panel).getByLabelText("伝えたいこと（任意）"),
      "メモ",
    );
    await user.click(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    );
    const box = within(panel).getByLabelText("本人に送る");
    await waitFor(() =>
      expect(within(panel).getByText(notice)).toBeInTheDocument(),
    );

    await user.clear(box);
    expect(within(panel).queryByText(notice)).not.toBeInTheDocument();
    expect(within(panel).getByLabelText("伝えたいこと（任意）")).toHaveValue(
      "メモ",
    );

    // もう一度下書きさせ、直して送ると消える
    await user.click(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    );
    await waitFor(() =>
      expect(within(panel).getByText(notice)).toBeInTheDocument(),
    );
  });

  it("下書きを直して送ると、注意書きは消え、入力欄は空になる", async () => {
    const user = userEvent.setup();
    const board = await renderBoard({
      "GET /api/operator/inquiries/op-1": { body: detailWithBox() },
      "POST /api/operator/inquiries/op-1/draft": {
        body: { body: "確認させてください。", language: "ja" },
      },
      "POST /api/operator/inquiries/op-1/replies": {
        body: makeDetail({
          read: true,
          ...ASKER_REPLY_STATE,
          messages: [makeMessage({ id: "m1", body: "確認させてください。" })],
        }),
      },
    });
    const panel = await openPanel(user, board.card);
    await user.click(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    );
    await waitFor(() =>
      expect(within(panel).getByLabelText("本人に送る")).toHaveValue(
        "確認させてください。",
      ),
    );

    // 下書きの種類にかかわらず、送る種類は担当者が選んで押す
    await user.click(
      within(panel).getByRole("button", { name: "結果を伝える" }),
    );

    await waitFor(() =>
      expect(
        callsTo(board.calls, "POST", "/api/operator/inquiries/op-1/replies")[0]
          .body,
      ).toEqual({ kind: "RESULT", body: "確認させてください。" }),
    );
    expect(
      within(panel).queryByText(/AI の下書きです/),
    ).not.toBeInTheDocument();
  });

  it("押している間は二重に押せない", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { calls, user, panel } = await openDraft();
    const fake = vi.mocked(fetch);
    const base = fake.getMockImplementation()!;
    fake.mockImplementation(async (input, init) => {
      if (String(input).endsWith("/draft") && init?.method === "POST") {
        calls.push({
          method: "POST",
          path: String(input),
          body: undefined,
          headers: {},
        });
        await gate;
        return new Response(
          JSON.stringify({ body: "下書きです。", language: "ja" }),
          { status: 200 },
        );
      }
      return base(input, init);
    });

    const ask = within(panel).getByRole("button", {
      name: "聞き返しを下書きさせる",
    });
    await user.click(ask);
    expect(ask).toBeDisabled();
    expect(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    ).toBeDisabled();
    await user.click(ask);
    release();

    await waitFor(() =>
      expect(within(panel).getByLabelText("本人に送る")).toHaveValue(
        "下書きです。",
      ),
    );
    expect(draftCalls(calls)).toHaveLength(1);
  });

  it("失敗は既存のエラーの出し方で出し、入力欄は空のまま、また押せる", async () => {
    const { user, panel } = await openDraft({
      status: 503,
      body: { code: "AI_UNAVAILABLE", message: "x" },
    });

    await user.click(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    );

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(within(panel).getByLabelText("本人に送る")).toHaveValue("");
    expect(
      within(panel).getByRole("button", { name: "結果を下書きさせる" }),
    ).toBeEnabled();
    expect(
      within(panel).queryByText(/AI の下書きです/),
    ).not.toBeInTheDocument();
  });

  it("担当者の番でない件には、下書きの欄を出さない", async () => {
    const user = userEvent.setup();
    await renderBoard({
      "GET /api/operator/inquiries/op-2": {
        body: makeDetail({
          id: "op-2",
          read: true,
          ...ASKER_REPLY_STATE,
          messages: [],
        }),
      },
    });
    await user.click(screen.getByRole("button", { name: /返事待ちの件/ }));
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    await within(panel).findByText("本人の返事を待っています。");
    expect(
      within(panel).queryByRole("button", { name: /下書きさせる/ }),
    ).not.toBeInTheDocument();
  });

  it("updated で詳細を取り直しても、「伝えたいこと」の入力と、下書きの注意書きは消えない", async () => {
    const stream = new FakeOperatorStream();
    const user = userEvent.setup();
    let gets = 0;
    const { calls, card } = await renderBoard(
      {
        "GET /api/operator/inquiries/op-1": () => ({
          body: makeDetail({
            read: true,
            messages: [],
            advice: (++gets === 1
              ? { status: "RUNNING", judgment: null, procedure: null }
              : {
                  status: "FAILED",
                  judgment: null,
                  procedure: null,
                }) as never,
          }),
        }),
        "POST /api/operator/inquiries/op-1/draft": {
          body: { body: "確認させてください。", language: "ja" },
        },
      },
      stream,
    );
    const panel = await openPanel(user, card);
    await user.type(
      within(panel).getByLabelText("伝えたいこと（任意）"),
      "メモです",
    );
    await user.click(
      within(panel).getByRole("button", { name: "聞き返しを下書きさせる" }),
    );
    await waitFor(() =>
      expect(within(panel).getByLabelText("本人に送る")).toHaveValue(
        "確認させてください。",
      ),
    );

    act(() => stream.latest.handlers.onUpdated({ ...mine1 }));

    // 取り直した内容（案の状況）が出る
    expect(
      await within(panel).findByText("AI の案は作れませんでした。"),
    ).toBeInTheDocument();
    expect(callsTo(calls, "GET", "/api/operator/inquiries/op-1")).toHaveLength(
      2,
    );
    // 入力は消えない
    expect(within(panel).getByLabelText("伝えたいこと（任意）")).toHaveValue(
      "メモです",
    );
    expect(within(panel).getByLabelText("本人に送る")).toHaveValue(
      "確認させてください。",
    );
    expect(
      within(panel).getByText(
        "AI の下書きです。確かめて、直してから送ってください。本人の言語（日本語）で書かれています。",
      ),
    ).toBeInTheDocument();
  });
});

describe("担当者の画面: ストリーム", () => {
  it("filed で「担当者の番」の列の先頭にカードが足され、通知が出る", async () => {
    const { stream } = await renderBoard();

    act(() =>
      stream.latest.handlers.onFiled(
        makeItem({ id: "op-9", summary: "請求書の宛名を変えてほしい。" }),
      ),
    );

    const cards = within(column("担当者の番")).getAllByRole("button");
    expect(cards[0]).toHaveTextContent("請求書の宛名を変えてほしい。");
    expect(cards).toHaveLength(2);
    expect(column("担当者の番")).toHaveAccessibleName("担当者の番 2件");
    expect(screen.getByRole("status")).toHaveTextContent(
      "新しい問い合わせが届きました",
    );
  });

  it("updated で状態が変わったカードは、別の列に移り、読み上げにも伝わる。番が担当者に戻った件は未読にして担当者の番へ", async () => {
    const { stream } = await renderBoard();

    // 担当者の番の件が、本人の番になる
    act(() =>
      stream.latest.handlers.onUpdated({ ...mine1, ...ASKER_REPLY_STATE }),
    );
    expect(cardIn("本人の番", /求人が一覧に出なくなった/)).toBeInTheDocument();
    expect(cardIn("担当者の番", /求人が一覧に出なくなった/)).toBeNull();
    expect(column("担当者の番")).toHaveAccessibleName("担当者の番 0件");
    expect(
      await screen.findByText(
        /「求人が一覧に出なくなった.*」を本人の番に移しました/,
      ),
    ).toBeInTheDocument();

    // 本人が返事をして、番が担当者に戻る（未読になる）
    act(() =>
      stream.latest.handlers.onUpdated({
        ...waiting2,
        phase: "DECIDE",
        turn: "OPERATOR",
        awaiting: "HANDLING",
        read: false,
        messageCount: 2,
      }),
    );
    const cards = within(column("担当者の番")).getAllByRole("button");
    expect(cards[0]).toHaveTextContent("返事待ちの件。");
    expect(cards[0]).toHaveClass("unread");
    expect(
      await screen.findByText(/「返事待ちの件。」を担当者の番に移しました/),
    ).toBeInTheDocument();
    // 通知のバナーは出さない（新しく届いた件ではない）
    expect(
      screen.queryByText("新しい問い合わせが届きました"),
    ).not.toBeInTheDocument();
  });

  it("updated で終わった件は、終わりの列に移る", async () => {
    const { stream } = await renderBoard();

    act(() =>
      stream.latest.handlers.onUpdated({
        ...waiting2,
        ...closedState("WITHDRAWN"),
      }),
    );

    expect(cardIn("終わり", /返事待ちの件/)).toHaveTextContent("取り下げ");
    expect(column("終わり")).toHaveAccessibleName("終わり 2件");
    expect(screen.queryByText("新しい問い合わせが届きました")).toBeNull();
  });

  it("updated で未読に戻った件は、列の上に寄る", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              makeItem({
                id: "op-5",
                read: true,
                summary: "新しい既読の件。",
                filedAt: "2026-11-21T09:00:00Z",
              }),
              makeItem({
                id: "op-6",
                read: true,
                summary: "古い既読の件。",
                filedAt: "2026-11-20T09:00:00Z",
              }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /古い既読の件/ });
    expect(
      within(column("担当者の番")).getAllByRole("button")[0],
    ).toHaveTextContent("新しい既読の件。");

    act(() =>
      stream.latest.handlers.onUpdated({
        ...makeItem({
          id: "op-6",
          summary: "古い既読の件。",
          filedAt: "2026-11-20T09:00:00Z",
        }),
        read: false,
        messageCount: 2,
      }),
    );

    const cards = within(column("担当者の番")).getAllByRole("button");
    expect(cards[0]).toHaveTextContent("古い既読の件。");
    expect(cards[0]).toHaveClass("unread");
    expect(cards[0]).toHaveTextContent("やり取り 2件");
    await waitFor(() => expect(document.title).toBe("(1) 問い合わせ受付"));
  });

  it("開いている件が更新されたら、詳細を取り直してやり取りに反映する（未読に戻った件は既読にして受け取る）", async () => {
    const user = userEvent.setup();
    const stream = new FakeOperatorStream();
    const { calls, card } = await renderBoard(
      {
        "POST /api/operator/inquiries/op-1/read": {
          body: makeDetail({
            read: true,
            messages: [
              makeMessage({
                id: "m1",
                from: "ASKER",
                kind: "NOTE",
                authorName: "開発用ユーザー",
                body: "追記です。",
              }),
            ],
          }),
        },
      },
      stream,
    );
    await openPanel(user, card);

    act(() =>
      stream.latest.handlers.onUpdated({
        ...mine1,
        read: false,
        messageCount: 1,
      }),
    );

    expect(await screen.findByText("追記です。")).toBeInTheDocument();
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/read"),
    ).toHaveLength(1);
  });
});

describe("担当者の画面: 実行記録と開発用のリンク", () => {
  it("config.trace が false なら出さず、true なら詳細の最下部に折りたたみを置く", async () => {
    const user = userEvent.setup();
    const withTrace = {
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          messages: [],
          trace: [
            makeTraceEntry({ skill: "reception" }),
            makeTraceEntry({ skill: "investigator", failed: true }),
          ],
        }),
      },
    };

    installFakeFetch(
      routes({
        ...withTrace,
        "GET /api/config": { body: { mock: false, trace: false } },
      }),
    );
    const first = render(
      <Root subscribeOperator={new FakeOperatorStream().subscribe} />,
    );
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    await screen.findByRole("list", { name: "やり取り" });
    expect(
      screen.queryByText("裏側を見る（開発者向け）"),
    ).not.toBeInTheDocument();
    first.unmount();
    vi.unstubAllGlobals();

    installFakeFetch(
      routes({
        ...withTrace,
        "GET /api/config": { body: { mock: false, trace: true } },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const summary = await screen.findByText("裏側を見る（開発者向け）");
    const details = summary.closest("details")!;
    expect(details).not.toHaveAttribute("open");
    const detail = screen.getByRole("region", { name: "問い合わせの詳細" });
    // 本人に送る入力欄と、状態の履歴より後ろ（最下部）
    for (const before of [
      within(detail).getByLabelText("本人に送る"),
      within(detail).getByText("状態の履歴"),
    ]) {
      expect(
        before.compareDocumentPosition(details) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    }
    await user.click(summary);
    const stages = within(details).getAllByRole("listitem");
    expect(stages).toHaveLength(9);
    expect(stages[0]).toHaveTextContent("受付");
    expect(stages[3]).toHaveTextContent("失敗 1回");
    expect(stages[4]).toHaveTextContent("照合（コード）");
    expect(stages[4]).toHaveClass("idle");
  });

  it("実行記録の段は、説明の後に 助言 → 案の確認（コード） → 下書き を並べる（担当者の詳細）", async () => {
    const user = userEvent.setup();
    installFakeFetch(
      routes({
        "GET /api/config": { body: makeConfig({ trace: true }) },
        "GET /api/operator/inquiries/op-1": {
          body: makeDetail({
            read: true,
            messages: [],
            trace: [
              makeTraceEntry({ skill: "explainer" }),
              makeTraceEntry({
                skill: "advisor",
                provider: "mock",
                model: null,
              }),
              makeTraceEntry({
                skill: "drafter",
                provider: "mock",
                model: null,
                failed: true,
              }),
            ],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const summary = await screen.findByText("裏側を見る（開発者向け）");
    await user.click(summary);

    const stages = within(summary.closest("details")!).getAllByRole("listitem");
    expect(stages.map((s) => s.querySelector("strong")?.textContent)).toEqual([
      "受付",
      "聞き手",
      "言い直し",
      "調査",
      "照合（コード）",
      "説明",
      "助言",
      "案の確認（コード）",
      "下書き",
    ]);
    expect(stages[6]).toHaveTextContent("AI のスキル");
    expect(stages[6]).toHaveTextContent("モック");
    // 案の確認（コード）は記録に現れないので、助言が成功していれば動いたものとして示す
    expect(stages[7]).toHaveTextContent("通常のコード");
    expect(stages[7]).not.toHaveClass("idle");
    expect(stages[7]).toHaveTextContent("案が基づく事実を確かめる");
    expect(stages[8]).toHaveTextContent("失敗 1回");
  });

  it("mock が true の担当者の画面には、最下部に「開発用: 本人の画面へ」を出す", async () => {
    installFakeFetch(
      routes({ "GET /api/config": { body: makeConfig({ mock: true }) } }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    expect(
      await screen.findByRole("link", { name: "開発用: 本人の画面へ" }),
    ).toHaveAttribute("href", "/");
  });

  it("mock も trace も false なら出さない", async () => {
    installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await screen.findByRole("button", { name: /完了した件/ });
    expect(
      screen.queryByRole("link", { name: /開発用/ }),
    ).not.toBeInTheDocument();
  });
});
