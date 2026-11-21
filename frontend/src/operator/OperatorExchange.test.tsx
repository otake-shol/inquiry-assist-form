import { act, render, screen, waitFor, within } from "@testing-library/react";
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
const counts = { OPEN: 1, IN_PROGRESS: 0, DONE: 1 };

/** 一覧と詳細の API を登録する。 */
function routes(extra: Parameters<typeof installFakeFetch>[0] = {}) {
  return {
    "GET /api/operator/inquiries": {
      body: { unreadCount: 0, items: [open1, done2], counts },
    },
    "GET /api/operator/inquiries?status=DONE": {
      body: { unreadCount: 0, items: [done2], counts },
    },
    "GET /api/operator/inquiries/op-1": {
      body: makeDetail({ read: true, handlingStatus: "OPEN", messages: [] }),
    },
    ...extra,
  };
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

describe("担当者の画面: 対応状況のタブ", () => {
  it("既定は「すべて」で、タブごとの件数を添える。押すと status つきで取得し直す", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(routes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const tabs = await screen.findByRole("group", {
      name: "対応状況で絞り込む",
    });
    const labels = within(tabs)
      .getAllByRole("button")
      .map((b) => b.textContent);
    expect(labels).toEqual(["未対応1", "対応中0", "完了1", "すべて2"]);
    expect(
      within(tabs).getByRole("button", { name: /すべて/ }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(callsTo(calls, "GET", "/api/operator/inquiries")).toHaveLength(1);

    await user.click(within(tabs).getByRole("button", { name: /完了/ }));
    await waitFor(() =>
      expect(
        callsTo(calls, "GET", "/api/operator/inquiries?status=DONE"),
      ).toHaveLength(1),
    );
    expect(within(tabs).getByRole("button", { name: /完了/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    const list = screen.getByRole("region", { name: "受付一覧" });
    await waitFor(() =>
      expect(
        within(list).queryByText(/求人が一覧に出なくなった/),
      ).not.toBeInTheDocument(),
    );
    expect(within(list).getByText("完了した件。")).toBeInTheDocument();
  });

  it("各件に対応状況の印が出る。古い応答（印なし）でも一覧は出る", async () => {
    installFakeFetch(
      routes({
        "GET /api/operator/inquiries": {
          body: {
            unreadCount: 0,
            items: [
              open1,
              done2,
              makeItem({ id: "op-3", summary: "古い応答の件。", read: true }),
            ],
          },
        },
      }),
    );
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const rows = await screen.findAllByRole("button", { name: /。/ });
    expect(rows[0]).toHaveTextContent("未対応");
    expect(rows[1]).toHaveTextContent("完了");
    expect(rows[2]).toHaveTextContent("古い応答の件。");
    // counts が無い応答ではタブに件数を出さない
    const tabs = screen.getByRole("group", { name: "対応状況で絞り込む" });
    expect(
      within(tabs).getByRole("button", { name: "未対応" }),
    ).toBeInTheDocument();
  });
});

describe("担当者の画面: 状況の切り替えと返信", () => {
  it("状況の切り替えで PUT が呼ばれ、詳細と一覧の印、件数が変わる", async () => {
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
    const row = screen.getByRole("button", {
      name: /求人が一覧に出なくなった理由/,
      current: true,
    });
    expect(row).toHaveTextContent("完了");
    const tabs = screen.getByRole("group", { name: "対応状況で絞り込む" });
    expect(
      within(tabs).getByRole("button", { name: /未対応/ }),
    ).toHaveTextContent("0");
    expect(
      within(tabs).getByRole("button", { name: /完了/ }),
    ).toHaveTextContent("2");
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

describe("担当者の画面: ストリームの updated", () => {
  it("未読になった件は一覧の先頭に動き、更新された内容（状況・やり取りの数）が反映される", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch(routes());
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /完了した件/ });

    act(() =>
      stream.latest.handlers.onUpdated({
        ...done2,
        read: false,
        handlingStatus: "IN_PROGRESS",
        messageCount: 2,
      }),
    );

    const list = screen.getByRole("region", { name: "受付一覧" });
    const rows = within(list).getAllByRole("button", { name: /。/ });
    expect(rows[0]).toHaveTextContent("完了した件。");
    expect(rows[0]).toHaveTextContent("対応中");
    expect(rows[0]).toHaveClass("unread");
    expect(rows).toHaveLength(2);
    // 通知のバナーは出さない（新しく届いた件ではない）
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("既読のままの更新は位置を変えず、その件だけを書き換える", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch(routes());
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /完了した件/ });

    act(() =>
      stream.latest.handlers.onUpdated({ ...done2, handlingStatus: "OPEN" }),
    );

    const rows = within(
      screen.getByRole("region", { name: "受付一覧" }),
    ).getAllByRole("button", { name: /。/ });
    expect(rows[1]).toHaveTextContent("完了した件。");
    expect(rows[1]).toHaveTextContent("未対応");
  });

  it("絞り込み中のタブから外れた件は一覧から消える", async () => {
    const user = userEvent.setup();
    const stream = new FakeOperatorStream();
    installFakeFetch(routes());
    render(<Root subscribeOperator={stream.subscribe} />);
    await user.click(await screen.findByRole("button", { name: /完了/ }));
    await screen.findByRole("button", { name: /完了した件/ });

    act(() =>
      stream.latest.handlers.onUpdated({
        ...done2,
        handlingStatus: "IN_PROGRESS",
      }),
    );

    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: /完了した件/ }),
      ).not.toBeInTheDocument(),
    );
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
