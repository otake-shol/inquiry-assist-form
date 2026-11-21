import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Question } from "./api/types.ts";
import Root from "./Root.tsx";
import {
  FakeOperatorStream,
  FakeStream,
  groundedAnswer,
  installFakeFetch,
  makeFiledView,
  makeListItem,
  makeMessage,
  makeStructuredView,
  makeTraceEntry,
  makeView,
  type RecordedCall,
} from "./test/fakes.ts";

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";
const FORBIDDEN = /0次回答|種別|判定|整形|起票|出典|スキル|モデル/;

const Q_SCREEN: Question = {
  id: "q1",
  text: "どの画面の話ですか",
  options: ["一覧", "詳細"],
};
const Q_WHEN: Question = {
  id: "q2",
  text: "いつ頃から気になっていますか",
  options: [],
};

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
});

/** 指定のパスを開いた状態で Root を描く。 */
function open(path: string, stream = new FakeStream()) {
  window.history.pushState({}, "", path);
  render(
    <Root
      subscribeAnswers={stream.subscribe}
      subscribeOperator={new FakeOperatorStream().subscribe}
    />,
  );
  return stream;
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

const config = {
  "GET /api/config": {
    body: { mock: false, user: { id: "local-user", name: "開発用ユーザー" } },
  },
};

describe("ページの上部（本人向け）", () => {
  it("左に「問い合わせアシスト」、右に「送った問い合わせ」と利用者の名前を出し、現在のページに aria-current をつける", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": { body: { unreadReplyCount: 0, items: [] } },
    });
    open("/");

    const brand = await screen.findByRole("link", {
      name: "問い合わせアシスト",
    });
    expect(brand).toHaveAttribute("href", "/");
    expect(brand).toHaveAttribute("aria-current", "page");
    const mine = screen.getByRole("link", { name: "送った問い合わせ" });
    expect(mine).not.toHaveAttribute("aria-current");
    expect(await screen.findByText("開発用ユーザー")).toBeInTheDocument();
  });

  it("未読の返信があれば、件数の印をつけ、読み上げられる文でも伝える", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: {
          unreadReplyCount: 2,
          items: [makeListItem({ unreadReply: true })],
        },
      },
    });
    open("/");

    const mine = await screen.findByRole("link", {
      name: /送った問い合わせ 未読の返信 2件/,
    });
    expect(mine).toHaveTextContent("2");
  });

  it("config に新しい項目が無い古い応答でも、名前も印も出さずに表示できる", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false } },
      "GET /api/inquiries": { status: 500, body: { code: "X", message: "x" } },
    });
    open("/");

    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "送った問い合わせ" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("開発用ユーザー")).not.toBeInTheDocument();
    expect(screen.queryByText(/未読の返信/)).not.toBeInTheDocument();
  });

  it("「送った問い合わせ」を押すと /inquiries に移り、「戻る」で入力に戻れる", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries": { body: { unreadReplyCount: 0, items: [] } },
    });
    open("/");

    await user.click(
      await screen.findByRole("link", { name: "送った問い合わせ" }),
    );
    expect(window.location.pathname).toBe("/inquiries");
    expect(
      await screen.findByText("送った問い合わせはまだありません。"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "送った問い合わせ" }),
    ).toHaveAttribute("aria-current", "page");

    act(() => window.history.back());
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });
});

describe("送った問い合わせの一覧", () => {
  it("1件ごとに言い直し・日時・状態・未読の返信の印を出し、状態は設計方針の言葉にする", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: {
          unreadReplyCount: 1,
          items: [
            makeListItem({
              id: "a",
              summary: "掲載期限を知りたい。",
              unreadReply: true,
              handlingStatus: "IN_PROGRESS",
            }),
            makeListItem({
              id: "b",
              summary: "請求書を直したい。",
              handlingStatus: "DONE",
            }),
            makeListItem({
              id: "c",
              summary: "確認待ちの件。",
              handlingStatus: "OPEN",
            }),
            makeListItem({
              id: "d",
              summary: "解決した件。",
              outcome: "SELF_RESOLVED",
              handlingStatus: null,
            }),
            makeListItem({
              id: "e",
              summary: "障害の件。",
              outcome: "INCIDENT_REDIRECTED",
              handlingStatus: null,
            }),
            makeListItem({
              id: "f",
              summary: "途中の件。",
              step: "STRUCTURED",
              outcome: "IN_PROGRESS",
              handlingStatus: null,
            }),
          ],
        },
      },
    });
    open("/inquiries");

    const rows = await screen.findAllByRole("link", { name: /。/ });
    const byText = (text: string) =>
      rows.find((r) => r.textContent?.includes(text))!;
    expect(byText("掲載期限")).toHaveTextContent("担当者が対応中");
    expect(byText("掲載期限")).toHaveTextContent("未読の返信");
    expect(byText("掲載期限")).toHaveAttribute("href", "/inquiries/a");
    expect(byText("請求書")).toHaveTextContent("対応完了");
    expect(byText("請求書")).not.toHaveTextContent("未読の返信");
    expect(byText("確認待ち")).toHaveTextContent("担当者の確認待ち");
    expect(byText("解決した件")).toHaveTextContent("解決済み");
    expect(byText("障害の件")).toHaveTextContent("障害の窓口を案内");
    expect(byText("途中の件")).toHaveTextContent(
      "途中（続きから再開できます）",
    );
    expect(
      within(byText("掲載期限")).getByText(/\d+\/\d+/),
    ).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("1件もなければ、その旨と「新しい問い合わせを書く」を出し、押すと入力に移る", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries": { body: { unreadReplyCount: 0, items: [] } },
    });
    open("/inquiries");

    expect(
      await screen.findByText("送った問い合わせはまだありません。"),
    ).toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
  });

  it("一覧を取得できなければ、その旨を示す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": { status: 500, body: { code: "X", message: "x" } },
    });
    open("/inquiries");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "読み込めませんでした",
    );
  });

  it("一覧から1件を押すと、その問い合わせを取得して開く", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: { unreadReplyCount: 0, items: [makeListItem({ id: "inq-1" })] },
      },
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
    });
    open("/inquiries");

    await user.click(await screen.findByRole("link", { name: /掲載期限/ }));
    expect(window.location.pathname).toBe("/inquiries/inq-1");
    expect(
      await screen.findByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();
    expect(callsTo(calls, "GET", "/api/inquiries/inq-1")).toHaveLength(1);
  });
});

describe("作成と URL", () => {
  it("作成すると URL が /inquiries/{id} に変わり、取得し直さずにそのまま続く。「戻る」で入力に戻る", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...config,
      "GET /api/inquiries": { body: { unreadReplyCount: 0, items: [] } },
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN] }),
      },
    });
    open("/");

    await user.type(await screen.findByLabelText(INPUT_LABEL), "掲載期限");
    await user.click(screen.getByRole("button", { name: "送信する" }));

    expect(await screen.findByText("どの画面の話ですか")).toBeInTheDocument();
    await waitFor(() =>
      expect(window.location.pathname).toBe("/inquiries/inq-1"),
    );
    expect(callsTo(calls, "GET", "/api/inquiries/inq-1")).toHaveLength(0);
    expect(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    ).toBeInTheDocument();

    act(() => window.history.back());
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("どの画面の話ですか")).not.toBeInTheDocument();
  });

  it("終わりの「新しい問い合わせを書く」で / に移り、入力に戻る", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({
          step: "CLOSED",
          outcome: "SELF_RESOLVED",
          answer: groundedAnswer,
        }),
      },
    });
    open("/inquiries/inq-1");

    await user.click(
      await screen.findByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
  });

  it("やり取りがある終わりでは、別件の入口を終わりの文言の下に置かず、やり取りの最後に置く", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
    });
    open("/inquiries/inq-1");

    const other = await screen.findByRole("button", {
      name: "別の問い合わせを書く",
    });
    expect(
      screen.queryByRole("button", { name: "新しい問い合わせを書く" }),
    ).not.toBeInTheDocument();
    // 追記の入力欄より後ろにある
    const append = screen.getByLabelText("追加で伝える");
    expect(
      append.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    await user.click(other);
    expect(window.location.pathname).toBe("/");
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
  });
});

describe("開き直したときの再開", () => {
  it("TRIAGED は受け取りの表示と、追加質問の1問目から始める", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({
          questions: [Q_SCREEN, Q_WHEN],
          submittedAnswers: null,
        }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText("仕様の確認・相談として受け取りました"),
    ).toBeInTheDocument();
    expect(screen.getByText("2つだけ教えてください")).toBeInTheDocument();
    expect(screen.getByText("どの画面の話ですか")).toBeInTheDocument();
    expect(
      screen.queryByText("いつ頃から気になっていますか"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
  });

  it("STRUCTURED は submittedAnswers から「質問 → 回答」を再現し、言い直しの確認を出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeStructuredView({
          questions: [Q_SCREEN, Q_WHEN],
          submittedAnswers: [
            { questionId: "q1", answer: "詳細" },
            { questionId: "q2", answer: null },
          ],
        }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText("つまり、こういうことですね。"),
    ).toBeInTheDocument();
    const first = screen
      .getByText("どの画面の話ですか")
      .closest(".answered-row")!;
    expect(first).toHaveTextContent("詳細");
    const second = screen
      .getByText("いつ頃から気になっていますか")
      .closest(".answered-row")!;
    expect(second).toHaveTextContent("わからない");
    // 答えた質問は、この段階では押して答え直す形にしない
    expect(first.tagName).toBe("DIV");
    expect(
      screen.getByRole("button", { name: "合っています" }),
    ).toBeInTheDocument();
  });

  it("ANSWERING は進捗ストリームを購読し直し、届いた進捗を出す", async () => {
    const stream = new FakeStream();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeStructuredView({ step: "ANSWERING" }),
      },
    });
    open("/inquiries/inq-1", stream);

    expect(
      await screen.findByRole("heading", { name: "調べています" }),
    ).toBeInTheDocument();
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    expect(stream.latest.id).toBe("inq-1");
    act(() => stream.latest.handlers.onProgress("資料を探しています"));
    expect(await screen.findByText("資料を探しています")).toBeInTheDocument();
  });

  it("ANSWERED は調べた結果と、解決したかの選択を出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeStructuredView({ step: "ANSWERED", answer: groundedAnswer }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByRole("heading", { name: "調べた結果" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "解決しました" }),
    ).toBeInTheDocument();
  });

  it("CLOSED かつ FILED は、終わりの文言に続けて「いまの状況」とやり取りを出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeFiledView({
          handlingStatus: "IN_PROGRESS",
          messages: [
            makeMessage({ id: "m1", body: "確認します。" }),
            makeMessage({
              id: "m2",
              from: "ASKER",
              authorName: "開発用ユーザー",
              body: "お願いします。",
            }),
          ],
        }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();
    expect(screen.getByText("担当者が対応しています")).toBeInTheDocument();
    expect(
      screen.getByText(
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
    // 「今すぐ担当者に送る」は終わると出さない
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();

    const thread = screen.getByRole("list", { name: "やり取り" });
    expect(thread).toHaveAttribute("aria-live", "polite");
    const items = within(thread).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    // 古い順。担当者の返信は左、自分の追記は右（色でも分かれる）
    expect(items[0]).toHaveTextContent("確認します。");
    expect(items[0]).toHaveClass("by-operator", "theirs");
    expect(items[1]).toHaveTextContent("お願いします。");
    expect(items[1]).toHaveClass("by-asker", "mine");
    expect(
      screen.queryByText("担当者からの返信は、ここに表示されます。"),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("返信がまだなければ、返信がここに出ることを伝える。対応状況ごとの言葉も出る", async () => {
    for (const [status, text] of [
      ["OPEN", "担当者の確認を待っています"],
      ["DONE", "対応が完了しました"],
    ] as const) {
      installFakeFetch({
        ...config,
        "GET /api/inquiries/inq-1": {
          body: makeFiledView({ handlingStatus: status }),
        },
      });
      window.history.replaceState({}, "", "/inquiries/inq-1");
      const { unmount } = render(
        <Root subscribeAnswers={new FakeStream().subscribe} />,
      );
      expect(await screen.findByText(text)).toBeInTheDocument();
      expect(
        screen.getByText("担当者からの返信は、ここに表示されます。"),
      ).toBeInTheDocument();
      unmount();
    }
  });

  it("担当者に送っていない終わり方（解決済み）には、状況とやり取りを出さない", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({ step: "CLOSED", outcome: "SELF_RESOLVED" }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText(
        "解決してよかったです。この内容は記録しておきます。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "いまの状況" }),
    ).not.toBeInTheDocument();
  });

  it("新しい項目（handlingStatus、messages、submittedAnswers、trace、createdAt）が欠けた古い応答でも表示できる", async () => {
    const legacy = { ...makeFiledView() } as Record<string, unknown>;
    for (const key of [
      "handlingStatus",
      "messages",
      "submittedAnswers",
      "trace",
      "createdAt",
    ]) {
      delete legacy[key];
    }
    installFakeFetch({
      "GET /api/config": { body: { mock: true } },
      "GET /api/inquiries/inq-1": { body: legacy },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText(
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "いまの状況" }),
    ).not.toBeInTheDocument();
  });

  it("見つからない問い合わせは「この問い合わせは見つかりませんでした」と示し、新しい問い合わせを書ける", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/missing": {
        status: 404,
        body: { code: "NOT_FOUND", message: "x" },
      },
    });
    open("/inquiries/missing");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "この問い合わせは見つかりませんでした",
    );
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(await screen.findByLabelText(INPUT_LABEL)).toBeInTheDocument();
  });

  it("形式の崩れた id は API を呼ばずに「見つかりませんでした」と示す。API が VALIDATION を返した場合も同じ", async () => {
    const calls = installFakeFetch({ ...config });
    open("/inquiries/%E0%A4%A");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "この問い合わせは見つかりませんでした",
    );
    expect(
      calls.filter((c) => c.path.startsWith("/api/inquiries/")),
    ).toHaveLength(0);

    vi.unstubAllGlobals();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/not-a-uuid": {
        status: 400,
        body: { code: "VALIDATION", message: "x" },
      },
    });
    document.body.innerHTML = "";
    window.history.replaceState({}, "", "/inquiries/not-a-uuid");
    render(<Root subscribeAnswers={new FakeStream().subscribe} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "この問い合わせは見つかりませんでした",
    );
  });

  it("取得に失敗したら、一般的なエラーを示す（見つからない扱いにしない）", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        status: 500,
        body: { code: "X", message: "x" },
      },
    });
    open("/inquiries/inq-1");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(
      screen.queryByText("この問い合わせは見つかりませんでした"),
    ).not.toBeInTheDocument();
  });

  it("ブラウザの「戻る」「進む」で、一覧と問い合わせの間を行き来できる", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: { unreadReplyCount: 0, items: [makeListItem({ id: "inq-1" })] },
      },
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
    });
    open("/inquiries");

    await user.click(await screen.findByRole("link", { name: /掲載期限/ }));
    expect(
      await screen.findByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();

    act(() => window.history.back());
    expect(
      await screen.findByRole("link", { name: /掲載期限/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "いまの状況" }),
    ).not.toBeInTheDocument();

    act(() => window.history.forward());
    expect(
      await screen.findByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();
  });
});

describe("追加で伝える", () => {
  it("送ると POST …/messages が呼ばれ、やり取りに現れ、入力欄は空になる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
      "POST /api/inquiries/inq-1/messages": {
        body: makeFiledView({
          messages: [
            makeMessage({
              id: "m1",
              from: "ASKER",
              authorName: "開発用ユーザー",
              body: "補足です。昨日からです。",
            }),
          ],
        }),
      },
    });
    open("/inquiries/inq-1");

    const box = await screen.findByLabelText("追加で伝える");
    await user.type(box, "  補足です。昨日からです。  ");
    await user.click(screen.getByRole("button", { name: "送る" }));

    const thread = screen.getByRole("list", { name: "やり取り" });
    expect(
      await within(thread).findByText("補足です。昨日からです。"),
    ).toBeInTheDocument();
    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/messages")[0].body,
    ).toEqual({
      body: "補足です。昨日からです。",
    });
    expect(box).toHaveValue("");
    expect(within(thread).getAllByRole("listitem")[0]).toHaveClass("mine");
  });

  it("空の本文（空白だけを含む）では送れない", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
    });
    open("/inquiries/inq-1");

    const send = await screen.findByRole("button", { name: "送る" });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText("追加で伝える"), "   ");
    expect(send).toBeDisabled();
    await user.keyboard("{Enter}");
    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/messages"),
    ).toHaveLength(0);
  });

  it("送信に失敗したら、入力を残してエラーを示す", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
      "POST /api/inquiries/inq-1/messages": {
        status: 409,
        body: { code: "INVALID_STATE", message: "x" },
      },
    });
    open("/inquiries/inq-1");

    const box = await screen.findByLabelText("追加で伝える");
    await user.type(box, "補足");
    await user.click(screen.getByRole("button", { name: "送る" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(box).toHaveValue("補足");
  });
});

describe("スキルの実行記録（開発者向け）", () => {
  const traceView = (overrides = {}) =>
    makeFiledView({
      trace: [
        makeTraceEntry({
          skill: "reception",
          provider: "mock",
          model: null,
          inputTokens: 0,
          outputTokens: 0,
          durationMillis: 3,
        }),
        makeTraceEntry({
          skill: "restater",
          inputTokens: 100,
          outputTokens: 10,
          durationMillis: 500,
        }),
        makeTraceEntry({
          skill: "restater",
          inputTokens: 200,
          outputTokens: 30,
          durationMillis: 700,
        }),
        makeTraceEntry({ skill: "explainer", failed: true }),
      ],
      ...overrides,
    });

  it("config.trace が false なら出さず、内部の言葉も出ない", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false, trace: false } },
      "GET /api/inquiries/inq-1": { body: traceView() },
    });
    open("/inquiries/inq-1");

    await screen.findByRole("heading", { name: "いまの状況" });
    expect(
      screen.queryByText("裏側を見る（開発者向け）"),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("true なら最下部に閉じた折りたたみを置き、開くと流れの順の段、未実行の段、複数回の集計、失敗が分かる", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "GET /api/config": { body: { mock: false, trace: true } },
      "GET /api/inquiries/inq-1": { body: traceView() },
    });
    open("/inquiries/inq-1");

    const summary = await screen.findByText("裏側を見る（開発者向け）");
    const details = summary.closest("details")!;
    expect(details).not.toHaveAttribute("open");
    // 本人のページの最下部（開発用のリンクより上、それ以外の内容より下）
    const followup = screen.getByRole("heading", { name: "いまの状況" });
    expect(
      followup.compareDocumentPosition(details) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    await user.click(summary);
    expect(details).toHaveAttribute("open");
    const stages = within(details).getAllByRole("listitem");
    expect(stages.map((s) => s.querySelector("strong")?.textContent)).toEqual([
      "受付",
      "聞き手",
      "言い直し",
      "調査",
      "照合（コード）",
      "説明",
    ]);
    expect(stages[0]).toHaveTextContent("モック");
    expect(stages[0]).toHaveTextContent("AI のスキル");
    // 未実行の段は薄く示す
    expect(stages[1]).toHaveClass("idle");
    expect(stages[1]).toHaveTextContent("まだ動いていません");
    // 複数回は回数と合計
    expect(stages[2]).toHaveTextContent("2回（合計）");
    expect(stages[2]).toHaveTextContent("1.2秒");
    expect(stages[2]).toHaveTextContent("入力 300 / 出力 40 トークン");
    expect(stages[2]).toHaveTextContent("手元のモデル（qwen3.6:35b）");
    // 照合はコードの段
    expect(stages[4]).toHaveTextContent("通常のコード");
    expect(stages[4]).toHaveClass("idle");
    // 失敗
    expect(stages[5]).toHaveTextContent("失敗 1回");
  });

  it("trace が true でも記録が無い（null）ときは、すべての段が未実行として出る", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false, trace: true } },
      "GET /api/inquiries/inq-1": { body: traceView({ trace: null }) },
    });
    open("/inquiries/inq-1");

    const details = (
      await screen.findByText("裏側を見る（開発者向け）")
    ).closest("details")!;
    expect(details.querySelectorAll("li.idle")).toHaveLength(6);
  });
});

describe("開発用の行き来", () => {
  it("mock が true なら、最下部に「開発用: 担当者の画面へ」を出し、押すと担当者の画面に移る", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "GET /api/config": { body: { mock: true } },
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    open("/");

    await user.click(
      await screen.findByRole("link", { name: "開発用: 担当者の画面へ" }),
    );
    expect(window.location.pathname).toBe("/operator");
    expect(
      await screen.findByRole("heading", { name: "問い合わせ受付" }),
    ).toBeInTheDocument();
    // 担当者の画面からも本人の画面に戻れる
    await user.click(
      await screen.findByRole("link", { name: "開発用: 本人の画面へ" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
  });

  it("trace だけが true でも出る。どちらも false なら出ない", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false, trace: true } },
    });
    open("/");
    expect(
      await screen.findByRole("link", { name: "開発用: 担当者の画面へ" }),
    ).toBeInTheDocument();
  });

  it("mock も trace も false なら、どちらの画面にも出ない", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false, trace: false } },
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    open("/");
    await screen.findByRole("button", { name: "送信する" });
    expect(
      screen.queryByRole("link", { name: /開発用/ }),
    ).not.toBeInTheDocument();
  });
});
