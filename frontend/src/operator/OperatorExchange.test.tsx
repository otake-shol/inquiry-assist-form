import {
  act,
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
  FakeOperatorStream,
  installFakeFetch,
  makeDetail,
  makeItem,
  makeMessage,
  makeTraceEntry,
  type RecordedCall,
} from "../test/fakes.ts";

beforeEach(() => {
  window.history.replaceState({}, "", "/operator");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

const open1 = makeItem({
  id: "op-1",
  read: true,
  handlingStatus: "OPEN",
  messageCount: 0,
});
const done2 = makeItem({
  id: "op-2",
  read: true,
  handlingStatus: "DONE",
  summary: "完了した件。",
  messageCount: 1,
});

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
    "GET /api/operator/inquiries": {
      body: { unreadCount: 0, items: [open1, done2] },
    },
    "GET /api/operator/inquiries/op-1": {
      body: makeDetail({ read: true, handlingStatus: "OPEN", messages: [] }),
    },
    ...extra,
  };
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

describe("担当者のボード: 列とカード", () => {
  it("未対応・対応中・完了の3列に件数を添え、カードを対応状況の列に入れる。空の列は「ありません」と示す", async () => {
    installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /完了した件/ });
    const board = screen.getByRole("region", { name: "受付一覧" });
    expect(
      within(board)
        .getAllByRole("heading", { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(["未対応", "対応中", "完了"]);
    expect(column("未対応")).toHaveAccessibleName("未対応 1件");
    expect(column("対応中")).toHaveAccessibleName("対応中 0件");
    expect(column("完了")).toHaveAccessibleName("完了 1件");
    expect(cardIn("未対応", /求人が一覧に出なくなった/)).toBeInTheDocument();
    expect(cardIn("完了", /完了した件/)).toBeInTheDocument();
    expect(within(column("対応中")).getByText("ありません")).toBeVisible();
    // 領域の中のボタンはカードだけ
    expect(within(board).getAllByRole("button")).toHaveLength(2);
  });

  it("カードに種類・対象システム・今すぐ送る・日時・やり取りの件数を出す", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              makeItem({
                id: "op-1",
                read: true,
                handlingStatus: "IN_PROGRESS",
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
    expect(card).toHaveTextContent("求人管理画面");
    expect(card).toHaveTextContent("今すぐ送る");
    expect(card).toHaveTextContent("やり取り 3件");
    expect(within(card).getByText(/\d+\/\d+/)).toBeInTheDocument();
    expect(cardIn("対応中", /求人が一覧に出なくなった/)).toBe(card);
  });

  it("対応状況の無い古い応答のカードは未対応の列に入る", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              makeItem({ id: "op-3", summary: "古い応答の件。", read: true }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /古い応答の件/ });
    expect(cardIn("未対応", /古い応答の件/)).toBeInTheDocument();
  });

  it("カードを押すとパネルが開いて URL が変わり、パネルの先頭にフォーカスが移る。閉じるとカードに戻る", async () => {
    const user = userEvent.setup();
    installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
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
    installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
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

describe("担当者のボード: ドラッグで対応状況を変える", () => {
  it("別の列に落とすと PUT が呼ばれ、カードが列を移り、読み上げに伝わる", async () => {
    const calls = installFakeFetch(
      routes({
        "PUT /api/operator/inquiries/op-1/status": {
          body: makeDetail({
            read: true,
            handlingStatus: "IN_PROGRESS",
            messages: [],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });

    dragTo(card, "対応中");

    // 画面にはすぐ反映される
    expect(cardIn("対応中", /求人が一覧に出なくなった/)).toBeInTheDocument();
    expect(cardIn("未対応", /求人が一覧に出なくなった/)).toBeNull();
    await waitFor(() =>
      expect(
        callsTo(calls, "PUT", "/api/operator/inquiries/op-1/status")[0].body,
      ).toEqual({ status: "IN_PROGRESS" }),
    );
    expect(
      await screen.findByText(
        /「求人が一覧に出なくなった.*」を対応中に移しました/,
      ),
    ).toBeInTheDocument();
    expect(column("対応中")).toHaveAccessibleName("対応中 1件");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("掴んでいる間は落とせる列を示し、離したら戻る。掴んだ列自身は示さない", async () => {
    installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    const item = card.closest("li")!;
    expect(item).toHaveAttribute("draggable", "true");

    fireEvent.dragStart(item);
    expect(item).toHaveClass("dragging");
    expect(column("未対応")).not.toHaveClass("droppable");
    expect(column("対応中")).toHaveClass("droppable");
    expect(column("完了")).toHaveClass("droppable");

    fireEvent.dragOver(column("完了"));
    expect(column("完了")).toHaveClass("over");
    expect(column("対応中")).not.toHaveClass("over");

    fireEvent.dragEnd(item);
    expect(item).not.toHaveClass("dragging");
    expect(column("完了")).not.toHaveClass("droppable", "over");
  });

  it("API が失敗したら元の列に戻し、その旨を伝える", async () => {
    const calls = installFakeFetch(
      routes({
        "PUT /api/operator/inquiries/op-1/status": {
          status: 500,
          body: { code: "X", message: "x" },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });

    dragTo(card, "完了");
    expect(cardIn("完了", /求人が一覧に出なくなった/)).toBeInTheDocument();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "対応状況を変えられませんでした。元の列に戻しました",
    );
    expect(cardIn("未対応", /求人が一覧に出なくなった/)).toBeInTheDocument();
    expect(cardIn("完了", /求人が一覧に出なくなった/)).toBeNull();
    expect(
      callsTo(calls, "PUT", "/api/operator/inquiries/op-1/status"),
    ).toHaveLength(1);
    expect(screen.queryByText(/に移しました/)).not.toBeInTheDocument();
  });

  it("同じ列に落としても API を呼ばない", async () => {
    const calls = installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });

    dragTo(card, "未対応");

    expect(cardIn("未対応", /求人が一覧に出なくなった/)).toBeInTheDocument();
    expect(calls.filter((c) => c.method === "PUT")).toHaveLength(0);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("開いているパネルの切り替えも、ドラッグに合わせて変わる", async () => {
    const user = userEvent.setup();
    installFakeFetch(
      routes({
        "PUT /api/operator/inquiries/op-1/status": {
          body: makeDetail({
            read: true,
            handlingStatus: "DONE",
            messages: [],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    await user.click(card);
    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    const group = await within(panel).findByRole("group", {
      name: "対応状況を切り替える",
    });

    dragTo(card, "完了");

    expect(within(group).getByRole("button", { name: "完了" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(
      within(group).getByRole("button", { name: "未対応" }),
    ).toHaveAttribute("aria-pressed", "false");
  });
});

describe("担当者の画面: 状況の切り替えと返信", () => {
  it("状況の切り替えで PUT が呼ばれ、詳細が変わり、カードが列を移って読み上げにも伝わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(
      routes({
        "PUT /api/operator/inquiries/op-1/status": {
          body: makeDetail({
            read: true,
            handlingStatus: "DONE",
            messages: [],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const detail = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    const group = await within(detail).findByRole("group", {
      name: "対応状況を切り替える",
    });
    expect(
      within(group).getByRole("button", { name: "未対応" }),
    ).toHaveAttribute("aria-pressed", "true");

    await user.click(within(group).getByRole("button", { name: "完了" }));

    await waitFor(() =>
      expect(
        within(group).getByRole("button", { name: "完了" }),
      ).toHaveAttribute("aria-pressed", "true"),
    );
    expect(
      callsTo(calls, "PUT", "/api/operator/inquiries/op-1/status")[0].body,
    ).toEqual({ status: "DONE" });
    expect(cardIn("完了", /求人が一覧に出なくなった理由/)).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(cardIn("未対応", /求人が一覧に出なくなった理由/)).toBeNull();
    expect(column("完了")).toHaveAccessibleName("完了 2件");
    expect(
      await screen.findByText(
        /「求人が一覧に出なくなった.*」を完了に移しました/,
      ),
    ).toBeInTheDocument();
  });

  it("本人に返信すると POST が呼ばれ、やり取りに現れる。担当者の返信は右、本人の追記は左", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(
      routes({
        "GET /api/operator/inquiries/op-1": {
          body: makeDetail({
            read: true,
            handlingStatus: "OPEN",
            messages: [
              makeMessage({
                id: "m1",
                from: "ASKER",
                authorName: "開発用ユーザー",
                body: "急ぎです。",
              }),
            ],
          }),
        },
        "POST /api/operator/inquiries/op-1/replies": {
          body: makeDetail({
            read: true,
            handlingStatus: "IN_PROGRESS",
            messages: [
              makeMessage({
                id: "m1",
                from: "ASKER",
                authorName: "開発用ユーザー",
                body: "急ぎです。",
              }),
              makeMessage({ id: "m2", from: "OPERATOR", body: "確認します。" }),
            ],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const detail = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    const thread = await within(detail).findByRole("list", {
      name: "やり取り",
    });
    expect(thread).toHaveAttribute("aria-live", "polite");
    expect(within(thread).getByText("急ぎです。").closest("li")).toHaveClass(
      "by-asker",
      "theirs",
    );

    await user.type(
      within(detail).getByLabelText("本人に返信する"),
      "確認します。",
    );
    await user.click(
      within(detail).getByRole("button", { name: "返信を送る" }),
    );

    const reply = (await within(thread).findByText("確認します。")).closest(
      "li",
    )!;
    expect(reply).toHaveClass("by-operator", "mine");
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies")[0].body,
    ).toEqual({
      body: "確認します。",
    });
    expect(within(detail).getByLabelText("本人に返信する")).toHaveValue("");
    // 返信で OPEN が IN_PROGRESS になったことが反映される
    expect(
      within(detail).getByRole("button", { name: "対応中" }),
    ).toHaveAttribute("aria-pressed", "true");
  });

  it("空の返信は送れない。操作に失敗したら、その旨を示して入力を残す", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(
      routes({
        "POST /api/operator/inquiries/op-1/replies": {
          status: 500,
          body: { code: "X", message: "x" },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const detail = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    const send = await within(detail).findByRole("button", {
      name: "返信を送る",
    });
    expect(send).toBeDisabled();
    const box = within(detail).getByLabelText("本人に返信する");
    await user.type(box, "  ");
    expect(send).toBeDisabled();

    await user.type(box, "確認します");
    await user.click(send);
    expect(await within(detail).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(box).toHaveValue("  確認します");
    expect(
      callsTo(calls, "POST", "/api/operator/inquiries/op-1/replies"),
    ).toHaveLength(1);
  });

  it("messages と handlingStatus が欠けた古い詳細でも表示できる", async () => {
    const user = userEvent.setup();
    const legacy = { ...makeDetail({ read: true }) } as Record<string, unknown>;
    delete legacy.messages;
    delete legacy.handlingStatus;
    delete legacy.trace;
    installFakeFetch(
      routes({ "GET /api/operator/inquiries/op-1": { body: legacy } }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const detail = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    expect(
      await within(detail).findByText("やり取りはまだありません。"),
    ).toBeInTheDocument();
    const group = within(detail).getByRole("group", {
      name: "対応状況を切り替える",
    });
    for (const button of within(group).getAllByRole("button")) {
      expect(button).toHaveAttribute("aria-pressed", "false");
    }
  });
});

describe("担当者の画面: ストリーム", () => {
  it("filed で「未対応」の列の先頭にカードが足され、通知が出る", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch(routes());
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /完了した件/ });

    act(() =>
      stream.latest.handlers.onFiled(
        makeItem({ id: "op-9", summary: "請求書の宛名を変えてほしい。" }),
      ),
    );

    const cards = within(column("未対応")).getAllByRole("button");
    expect(cards[0]).toHaveTextContent("請求書の宛名を変えてほしい。");
    expect(cards).toHaveLength(2);
    expect(column("未対応")).toHaveAccessibleName("未対応 2件");
    expect(screen.getByRole("status")).toHaveTextContent(
      "新しい問い合わせが届きました",
    );
  });

  it("updated で対応状況が変わったカードは、別の列に移る", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch(routes());
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /完了した件/ });

    act(() =>
      stream.latest.handlers.onUpdated({ ...done2, handlingStatus: "OPEN" }),
    );

    expect(cardIn("未対応", /完了した件/)).toBeInTheDocument();
    expect(cardIn("完了", /完了した件/)).toBeNull();
    expect(column("完了")).toHaveAccessibleName("完了 0件");
    // 通知のバナーは出さない（新しく届いた件ではない）
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
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
                handlingStatus: "OPEN",
              }),
              makeItem({
                id: "op-6",
                read: true,
                summary: "古い既読の件。",
                filedAt: "2026-11-20T09:00:00Z",
                handlingStatus: "OPEN",
              }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /古い既読の件/ });
    expect(
      within(column("未対応")).getAllByRole("button")[0],
    ).toHaveTextContent("新しい既読の件。");

    act(() =>
      stream.latest.handlers.onUpdated({
        ...makeItem({
          id: "op-6",
          summary: "古い既読の件。",
          filedAt: "2026-11-20T09:00:00Z",
          handlingStatus: "OPEN",
        }),
        read: false,
        messageCount: 2,
      }),
    );

    const cards = within(column("未対応")).getAllByRole("button");
    expect(cards[0]).toHaveTextContent("古い既読の件。");
    expect(cards[0]).toHaveClass("unread");
    expect(cards[0]).toHaveTextContent("やり取り 2件");
    await waitFor(() => expect(document.title).toBe("(1) 問い合わせ受付"));
  });

  it("開いている件が更新されたら、詳細を取り直してやり取りに反映する（未読に戻った件は既読にして受け取る）", async () => {
    const user = userEvent.setup();
    const stream = new FakeOperatorStream();
    const calls = installFakeFetch(
      routes({
        "POST /api/operator/inquiries/op-1/read": {
          body: makeDetail({
            read: true,
            handlingStatus: "OPEN",
            messages: [
              makeMessage({
                id: "m1",
                from: "ASKER",
                authorName: "開発用ユーザー",
                body: "追記です。",
              }),
            ],
          }),
        },
      }),
    );
    render(<Root subscribeOperator={stream.subscribe} />);
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    await screen.findByRole("list", { name: "やり取り" });

    act(() =>
      stream.latest.handlers.onUpdated({
        ...open1,
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
          handlingStatus: "OPEN",
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
    // やり取りの入力欄より後ろ（最下部）
    expect(
      within(detail)
        .getByLabelText("本人に返信する")
        .compareDocumentPosition(details) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    await user.click(summary);
    const stages = within(details).getAllByRole("listitem");
    expect(stages).toHaveLength(6);
    expect(stages[0]).toHaveTextContent("受付");
    expect(stages[3]).toHaveTextContent("失敗 1回");
    expect(stages[4]).toHaveTextContent("照合（コード）");
    expect(stages[4]).toHaveClass("idle");
  });

  it("mock が true の担当者の画面には、最下部に「開発用: 本人の画面へ」を出す", async () => {
    installFakeFetch(routes({ "GET /api/config": { body: { mock: true } } }));
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
