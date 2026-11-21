import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  FakeOperatorStream,
  installFakeFetch,
  makeDetail,
  makeItem,
  type RecordedCall,
} from "../test/fakes.ts";
import Root from "../Root.tsx";

beforeEach(() => {
  window.history.replaceState({}, "", "/operator");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

const unreadItem = makeItem({ id: "op-1" });
const readItem = makeItem({
  id: "op-2",
  read: true,
  category: "SPEC_QUESTION",
  system: null,
  summary: "掲載期限の延長日数を知りたい。",
  sentImmediately: true,
});

/** 一覧と詳細・既読の API を登録する。 */
function operatorRoutes(items = [unreadItem, readItem]) {
  return {
    "GET /api/operator/inquiries": {
      body: {
        unreadCount: items.filter((i) => !i.read).length,
        items,
      },
    },
    "GET /api/operator/inquiries/op-1": { body: makeDetail({ read: true }) },
    "POST /api/operator/inquiries/op-1/read": {
      body: makeDetail({ read: true }),
    },
    "GET /api/operator/inquiries/op-2": {
      body: makeDetail({
        id: "op-2",
        read: true,
        category: "SPEC_QUESTION",
        system: null,
        sentImmediately: true,
        answer: null,
        revisionNotes: [],
        slackStatus: "FAILED",
        utterance: "掲載期限の延長日数を知りたい",
      }),
    },
  };
}

const count = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path).length;

describe("オペレータ画面", () => {
  it("(e) 一覧を表示し、未読に印を付け、未読数とタブの題名を出す", async () => {
    installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const rows = await screen.findAllByRole("button", {
      name: /求人が一覧に出なくなった|掲載期限の延長日数/,
    });
    // 新しい順（API の順）のまま並ぶ
    expect(rows[0]).toHaveTextContent(
      "求人が一覧に出なくなった理由を知りたい。",
    );
    expect(rows[0]).toHaveClass("unread");
    expect(within(rows[0]).getByText("未読")).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent("不具合の疑い");
    expect(rows[0]).toHaveTextContent("求人管理画面");
    expect(rows[1]).not.toHaveClass("unread");
    expect(within(rows[1]).queryByText("未読")).not.toBeInTheDocument();
    expect(rows[1]).toHaveTextContent("システム未定");
    expect(rows[1]).toHaveTextContent("今すぐ送る");
    expect(screen.getByLabelText("未読 1件")).toBeInTheDocument();
    expect(document.title).toBe("(1) 問い合わせ受付");
  });

  it("(f) 未読の1件を開くと詳細が出て read が呼ばれ、未読数が減る。既読の1件は取得だけ", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );

    const detail = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    expect(
      await within(detail).findByText(
        "昨日まで見えていた求人が今日は見えません",
      ),
    ).toBeInTheDocument();
    expect(count(calls, "POST", "/api/operator/inquiries/op-1/read")).toBe(1);
    expect(count(calls, "GET", "/api/operator/inquiries/op-1")).toBe(0);
    // 追加質問と回答（null は「わからない」）、作り直しの一言、調べた結果
    expect(within(detail).getByText("掲載中")).toBeInTheDocument();
    expect(within(detail).getByText("わからない")).toBeInTheDocument();
    expect(
      within(detail).getByText("消えたのは一昨日から"),
    ).toBeInTheDocument();
    expect(
      within(detail).getByText("掲載期限を過ぎると一覧から外れます。"),
    ).toBeInTheDocument();
    expect(
      within(detail).getByText("期限切れで非表示になる"),
    ).toBeInTheDocument();
    expect(within(detail).getByText("再掲載の手順")).toBeInTheDocument();
    expect(within(detail).getByText("Slack に投稿済み")).toBeInTheDocument();
    // 未読数が減る
    expect(screen.queryByLabelText(/^未読 \d+件$/)).not.toBeInTheDocument();
    expect(document.title).toBe("問い合わせ受付");

    await user.click(
      screen.getByRole("button", { name: /掲載期限の延長日数を知りたい/ }),
    );
    expect(
      await within(detail).findByText("Slack への投稿に失敗しました"),
    ).toBeInTheDocument();
    expect(
      within(detail).getByText(/調査の前に送られました/),
    ).toBeInTheDocument();
    expect(count(calls, "GET", "/api/operator/inquiries/op-2")).toBe(1);
    expect(count(calls, "POST", "/api/operator/inquiries/op-2/read")).toBe(0);
  });

  it("(g) filed の通知で一覧の先頭に足され、通知から1件を開ける", async () => {
    const user = userEvent.setup();
    const stream = new FakeOperatorStream();
    const fresh = makeItem({
      id: "op-9",
      summary: "請求書の宛名を変えてほしい。",
      category: "WORK_REQUEST",
      system: { id: "billing", label: "請求管理" },
    });
    const calls = installFakeFetch({
      ...operatorRoutes(),
      "POST /api/operator/inquiries/op-9/read": {
        body: makeDetail({
          id: "op-9",
          utterance: "宛名を変えたい",
          read: true,
        }),
      },
    });
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ });
    expect(document.title).toBe("(1) 問い合わせ受付");

    act(() => stream.latest.handlers.onFiled(fresh));

    const rows = within(
      screen.getByRole("region", { name: "受付一覧" }),
    ).getAllByRole("button");
    expect(rows[0]).toHaveTextContent("請求書の宛名を変えてほしい。");
    expect(rows[0]).toHaveTextContent("作業依頼");
    const banner = screen.getByRole("status");
    expect(banner).toHaveTextContent("新しい問い合わせが届きました");
    expect(banner).toHaveTextContent("請求書の宛名を変えてほしい。");
    expect(document.title).toBe("(2) 問い合わせ受付");

    await user.click(
      within(banner).getByRole("button", {
        name: /新しい問い合わせが届きました/,
      }),
    );
    expect(await screen.findByText("宛名を変えたい")).toBeInTheDocument();
    expect(count(calls, "POST", "/api/operator/inquiries/op-9/read")).toBe(1);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(document.title).toBe("(1) 問い合わせ受付");
  });

  it("切断から再接続できたら一覧を取り直す", async () => {
    const stream = new FakeOperatorStream();
    const calls = installFakeFetch(operatorRoutes([readItem]));
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /掲載期限の延長日数/ });
    expect(count(calls, "GET", "/api/operator/inquiries")).toBe(1);

    act(() => stream.latest.handlers.onReconnected());

    await waitFor(() =>
      expect(count(calls, "GET", "/api/operator/inquiries")).toBe(2),
    );
  });

  it("(h) 照合に落ちた出典は照合済みと区別して表示される", async () => {
    const user = userEvent.setup();
    installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );

    const ghost = (await screen.findByText(/faq\/ghost\.md/)).closest("li")!;
    const real = screen
      .getByText(/src\/JobPostingService\.java/)
      .closest("li")!;
    expect(within(ghost).getByText("照合できず")).toBeInTheDocument();
    expect(ghost).toHaveClass("unverified");
    expect(within(real).getByText("照合済み")).toBeInTheDocument();
    expect(within(real).queryByText("照合できず")).not.toBeInTheDocument();
    expect(real).not.toHaveClass("unverified");
  });

  it("(i) 「未読だけ」で既読の問い合わせが一覧から消える", async () => {
    const user = userEvent.setup();
    installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await screen.findByRole("button", { name: /掲載期限の延長日数/ });

    await user.click(screen.getByRole("checkbox", { name: "未読だけ" }));

    expect(
      screen.queryByRole("button", { name: /掲載期限の延長日数/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /求人が一覧に出なくなった/ }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "未読だけ" }));
    expect(
      screen.getByRole("button", { name: /掲載期限の延長日数/ }),
    ).toBeInTheDocument();
  });

  it("未読が無いと未読だけでは空の案内が出る", async () => {
    const user = userEvent.setup();
    installFakeFetch(operatorRoutes([readItem]));
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await screen.findByRole("button", { name: /掲載期限の延長日数/ });

    await user.click(screen.getByRole("checkbox", { name: "未読だけ" }));

    expect(
      screen.getByText("未読の問い合わせはありません。"),
    ).toBeInTheDocument();
  });
});

describe("オペレータ画面の URL", () => {
  it("(a) /operator/<id> を直接開くと、その問い合わせの詳細が出て既読になる", async () => {
    window.history.replaceState({}, "", "/operator/op-1");
    const calls = installFakeFetch({
      ...operatorRoutes(),
      // 直接開く場合、一覧より先に未読の詳細を取得する
      "GET /api/operator/inquiries/op-1": { body: makeDetail({ read: false }) },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    expect(
      await screen.findByText("昨日まで見えていた求人が今日は見えません"),
    ).toBeInTheDocument();
    // 本人向けの画面ではない
    expect(
      screen.queryByRole("button", { name: "送信する" }),
    ).not.toBeInTheDocument();
    expect(count(calls, "GET", "/api/operator/inquiries/op-1")).toBe(1);
    expect(count(calls, "POST", "/api/operator/inquiries/op-1/read")).toBe(1);
    // 一覧も並び、遅れて届いた一覧でも既読の扱い（未読数に数えない）
    expect(
      await screen.findByRole("button", { name: /掲載期限の延長日数/ }),
    ).toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe("問い合わせ受付"));
  });

  it("(b) 一覧で選ぶと URL が変わり、戻る・進むで選択が追従する", async () => {
    const user = userEvent.setup();
    installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    await screen.findByText("昨日まで見えていた求人が今日は見えません");
    expect(window.location.pathname).toBe("/operator/op-1");

    await user.click(
      screen.getByRole("button", { name: /掲載期限の延長日数/ }),
    );
    await screen.findByText("掲載期限の延長日数を知りたい");
    expect(window.location.pathname).toBe("/operator/op-2");

    // 戻る: 1件目の選択に戻る
    act(() => {
      window.history.back();
    });
    await waitFor(() =>
      expect(window.location.pathname).toBe("/operator/op-1"),
    );
    expect(
      await screen.findByText("昨日まで見えていた求人が今日は見えません"),
    ).toBeInTheDocument();
    // さらに戻る: 一覧だけ（未選択）
    act(() => {
      window.history.back();
    });
    await waitFor(() => expect(window.location.pathname).toBe("/operator"));
    await waitFor(() =>
      expect(screen.queryByText("元の発話")).not.toBeInTheDocument(),
    );
    expect(
      screen.getByText("左の一覧から問い合わせを選んでください。"),
    ).toBeInTheDocument();
  });

  it("(b) 詳細の「一覧に戻る」は /operator に戻す", async () => {
    window.history.replaceState({}, "", "/operator/op-1");
    const user = userEvent.setup();
    installFakeFetch(operatorRoutes());
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    await screen.findByText("昨日まで見えていた求人が今日は見えません");

    await user.click(screen.getByRole("button", { name: "一覧に戻る" }));

    expect(window.location.pathname).toBe("/operator");
    expect(screen.queryByText("元の発話")).not.toBeInTheDocument();
  });

  it("(c) 存在しない id では一覧を出したうえで見つかりませんでしたと示す", async () => {
    window.history.replaceState({}, "", "/operator/nope");
    installFakeFetch({
      ...operatorRoutes(),
      "GET /api/operator/inquiries/nope": {
        status: 404,
        body: { code: "NOT_FOUND", message: "x" },
      },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    expect(
      await screen.findByText("この問い合わせは見つかりませんでした"),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    ).toBeInTheDocument();
    expect(screen.queryByText("元の発話")).not.toBeInTheDocument();
  });

  it("(c) 形式の崩れた id（API が 400 を返す）でも見つかりませんでしたと示す", async () => {
    window.history.replaceState({}, "", "/operator/0b9c-cut-off");
    installFakeFetch({
      ...operatorRoutes(),
      "GET /api/operator/inquiries/0b9c-cut-off": {
        status: 400,
        body: { code: "VALIDATION", message: "入力が不正です" },
      },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    expect(
      await screen.findByText("この問い合わせは見つかりませんでした"),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    ).toBeInTheDocument();
    expect(screen.queryByText("元の発話")).not.toBeInTheDocument();
  });

  it("通知から開いたときも URL が変わる", async () => {
    const user = userEvent.setup();
    const stream = new FakeOperatorStream();
    installFakeFetch({
      ...operatorRoutes(),
      "POST /api/operator/inquiries/op-9/read": {
        body: makeDetail({ id: "op-9", utterance: "宛名を変えたい" }),
      },
    });
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ });

    act(() => stream.latest.handlers.onFiled(makeItem({ id: "op-9" })));
    await user.click(
      screen.getByRole("button", { name: /新しい問い合わせが届きました/ }),
    );

    expect(await screen.findByText("宛名を変えたい")).toBeInTheDocument();
    expect(window.location.pathname).toBe("/operator/op-9");
  });

  it("(e) slackStatus は POSTED だけ控えめに、FAILED だけ目立たせ、SKIPPED と PENDING は何も出さない", async () => {
    const expectations = {
      POSTED: { posted: true, failed: false },
      FAILED: { posted: false, failed: true },
      SKIPPED: { posted: false, failed: false },
      PENDING: { posted: false, failed: false },
    } as const;
    for (const [status, want] of Object.entries(expectations)) {
      window.history.replaceState({}, "", "/operator/op-1");
      installFakeFetch({
        ...operatorRoutes([readItem]),
        "GET /api/operator/inquiries/op-1": {
          body: makeDetail({
            read: true,
            slackStatus: status as keyof typeof expectations,
          }),
        },
      });
      const { unmount } = render(
        <Root subscribeOperator={new FakeOperatorStream().subscribe} />,
      );
      await screen.findByText("昨日まで見えていた求人が今日は見えません");

      expect(screen.queryByText("Slack に投稿済み") !== null).toBe(want.posted);
      expect(screen.queryByText("Slack への投稿に失敗しました") !== null).toBe(
        want.failed,
      );
      unmount();
    }
  });
});
