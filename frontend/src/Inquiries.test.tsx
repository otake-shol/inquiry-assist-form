import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Root from "./Root.tsx";
import {
  FakeOperatorStream,
  FakeStream,
  groundedAnswer,
  installFakeFetch,
  makeConfig,
  makeFiledView,
  makeListItem,
  makeMessage,
  makeQuestion,
  makeStructuredView,
  makeTraceEntry,
  makeTypeEntry,
  makeView,
  type RecordedCall,
  ASKER_REPLY_STATE,
  CONSULTATION_STATE,
  HANDLING_STATE,
  INTAKE_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
} from "./test/fakes.ts";

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";
const FORBIDDEN = /0次回答|種別|種類|判定|整形|起票|出典|スキル|モデル/;

/** 入口の質問（選択肢つき）。 */
const Q_SCREEN = makeQuestion({
  questionId: "screen",
  kind: "DETAIL",
  text: "どの画面の話ですか",
  options: [
    { id: "list", label: "一覧" },
    { id: "detail", label: "詳細" },
  ],
});
/** 1問目（種類の質問）が出ていることを確かめる。 */
const findFirstQuestion = () =>
  screen.findByRole("group", { name: "何をしたいですか" });

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
    body: makeConfig({ user: { id: "local-user", name: "開発用ユーザー" } }),
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
      await screen.findByRole("button", { name: "文章で書く" }),
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
    expect(await findFirstQuestion()).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });
});

describe("送った問い合わせのボード", () => {
  /** 状態ごとに1件ずつ含む一覧。 */
  const boardItems = [
    makeListItem({
      id: "a",
      summary: "入力の途中の件。",
      ...INTAKE_STATE,
    }),
    makeListItem({
      id: "b",
      summary: "聞き返された件。",
      ...ASKER_REPLY_STATE,
      unreadReply: true,
      messageCount: 2,
    }),
    makeListItem({
      id: "c",
      summary: "結果が届いた件。",
      ...RESULT_STATE,
      handedOverAt: "2026-11-21T05:00:00Z",
      messageCount: 1,
    }),
    makeListItem({
      id: "d",
      summary: "AI の答えを確かめる件。",
      ...RESULT_STATE,
      handedOverAt: null,
      // やり取りがあっても、担当者に渡していなければ AI の答え
      messageCount: 1,
    }),
    makeListItem({
      id: "e",
      summary: "担当者が確認している件。",
      ...HANDLING_STATE,
    }),
    makeListItem({
      id: "f",
      summary: "調べている件。",
      ...INVESTIGATION_STATE,
    }),
    makeListItem({
      id: "g",
      summary: "解決した件。",
      ...closedState("RESOLVED"),
    }),
    makeListItem({
      id: "h",
      summary: "見送られた件。",
      ...closedState("DECLINED"),
    }),
  ];

  it("あなたの番・相手の番・終わりの3列と件数を出し、カードを番の列に入れる。言い直し・日時・未読の返信の印も出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: { unreadReplyCount: 1, items: boardItems },
      },
    });
    open("/inquiries");

    await screen.findAllByRole("link", { name: /。/ });
    const column = (name: string) => screen.getByRole("region", { name });
    const yours = column("あなたの番 4件");
    const theirs = column("相手の番 2件");
    const closed = column("終わり 2件");
    expect(
      screen
        .getAllByRole("heading", { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(["あなたの番", "相手の番", "終わり"]);

    for (const text of [
      "入力の途中の件。",
      "聞き返された件。",
      "結果が届いた件。",
      "AI の答えを確かめる件。",
    ]) {
      expect(within(yours).getByText(text)).toBeInTheDocument();
    }
    for (const text of ["担当者が確認している件。", "調べている件。"]) {
      expect(within(theirs).getByText(text)).toBeInTheDocument();
    }
    for (const text of ["解決した件。", "見送られた件。"]) {
      expect(within(closed).getByText(text)).toBeInTheDocument();
    }

    const card = within(yours)
      .getAllByRole("listitem")
      .find((item) => item.textContent?.includes("聞き返された件"))!;
    expect(within(card).getByRole("link")).toHaveAttribute(
      "href",
      "/inquiries/b",
    );
    expect(card).toHaveTextContent("未読の返信");
    expect(card).toHaveTextContent("やり取り 2件");
    expect(within(card).getByText(/\d+\/\d+/)).toBeInTheDocument();
    expect(within(theirs).getAllByRole("listitem")[0]).not.toHaveTextContent(
      "未読の返信",
    );
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("どのカードにも、本人に見せる言葉（いまの状況、終わっていれば終わり方）を添える", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: { unreadReplyCount: 1, items: boardItems },
      },
    });
    open("/inquiries");
    await screen.findAllByRole("link", { name: /。/ });

    const note = (text: string) => {
      const card = screen
        .getAllByRole("listitem")
        .find((item) => item.textContent?.includes(text))!;
      return card.querySelector(".board-note")?.textContent;
    };
    expect(note("入力の途中の件")).toBe("入力の途中です");
    expect(note("聞き返された件")).toBe("担当者から質問が届いています");
    // 担当者に渡したことがある件は、担当者の結果が届いている件
    expect(note("結果が届いた件")).toBe("担当者から結果が届いています");
    expect(note("AI の答えを確かめる件")).toBe("結果を確かめてください");
    expect(note("担当者が確認している件")).toBe("担当者が確認しています");
    expect(note("調べている件")).toBe("調べています");
    expect(note("解決した件")).toBe("解決済み");
    expect(note("見送られた件")).toBe("見送り");
  });

  it("担当者が仕様の持ち主に相談している件は、「相手の番」の列に入り、「担当者が、仕様を決める人に確認しています」と添える。相談の中身は出さない", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: {
          unreadReplyCount: 0,
          items: [
            makeListItem({
              id: "c",
              summary: "相談されている件。",
              ...CONSULTATION_STATE,
            }),
          ],
        },
      },
    });
    open("/inquiries");

    await screen.findByRole("link", { name: /相談されている件/ });
    const theirs = screen.getByRole("region", { name: "相手の番 1件" });
    const card = within(theirs).getByRole("listitem");
    expect(card.querySelector(".board-note")?.textContent).toBe(
      "担当者が、仕様を決める人に確認しています",
    );
    expect(
      within(
        screen.getByRole("region", { name: "あなたの番 0件" }),
      ).queryByRole("listitem"),
    ).toBeNull();
    expect(document.body.textContent).not.toMatch(
      /CONSULTATION|OWNER|仕様の持ち主/,
    );
  });

  it("列の中は、未読の返信を上に寄せ、同じ条件なら新しい順に並ぶ", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries": {
        body: {
          unreadReplyCount: 1,
          items: [
            makeListItem({
              id: "old",
              summary: "古い件。",
              createdAt: "2026-11-19T00:00:00Z",
            }),
            makeListItem({
              id: "new",
              summary: "新しい件。",
              createdAt: "2026-11-21T00:00:00Z",
            }),
            makeListItem({
              id: "unread",
              summary: "未読の件。",
              createdAt: "2026-11-18T00:00:00Z",
              unreadReply: true,
            }),
          ],
        },
      },
    });
    open("/inquiries");

    const waiting = await screen.findByRole("region", {
      name: "相手の番 3件",
    });
    expect(
      within(waiting)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([
      expect.stringContaining("未読の件。"),
      expect.stringContaining("新しい件。"),
      expect.stringContaining("古い件。"),
    ]);
    // 件のない列も出し、「ありません」と示す
    expect(
      within(screen.getByRole("region", { name: "終わり 0件" })).getByText(
        "ありません",
      ),
    ).toBeInTheDocument();
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
    expect(await findFirstQuestion()).toBeInTheDocument();
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
  it("1問目の選択肢を押すと問い合わせを作って URL が /inquiries/{id} に変わり、取得し直さずにそのまま続く。「戻る」で最初の画面に戻る", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...config,
      "GET /api/inquiries": { body: { unreadReplyCount: 0, items: [] } },
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/intake/answers": {
        body: makeView({
          intake: {
            entries: [makeTypeEntry()],
            next: Q_SCREEN,
            complete: false,
          },
        }),
      },
    });
    open("/");

    await user.click(
      await screen.findByRole("button", { name: "仕組みを知りたい" }),
    );

    expect(await screen.findByText("どの画面の話ですか")).toBeInTheDocument();
    await waitFor(() =>
      expect(window.location.pathname).toBe("/inquiries/inq-1"),
    );
    expect(callsTo(calls, "GET", "/api/inquiries/inq-1")).toHaveLength(0);

    act(() => window.history.back());
    expect(await findFirstQuestion()).toBeInTheDocument();
    expect(screen.queryByText("どの画面の話ですか")).not.toBeInTheDocument();
  });

  it("終わりの「新しい問い合わせを書く」で / に移り、入力に戻る", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({
          ...closedState("RESOLVED"),
          answer: groundedAnswer,
        }),
      },
    });
    open("/inquiries/inq-1");

    await user.click(
      await screen.findByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(await findFirstQuestion()).toBeInTheDocument();
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
    expect(await findFirstQuestion()).toBeInTheDocument();
  });
});

describe("開き直したときの再開", () => {
  it("INTAKE は、答え終えた質問の要約と、次の質問から続ける。入力欄は出さない", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({
          utterance: "応募者一覧のことです",
          intake: {
            entries: [
              makeTypeEntry("LOOKS_WRONG", "おかしいかもしれない"),
              {
                questionId: "system",
                kind: "SYSTEM",
                text: "どのシステムですか",
                answer: {
                  kind: "INFERRED",
                  optionId: "system-a",
                  label: "システムA",
                },
              },
            ],
            next: Q_SCREEN,
            complete: false,
          },
        }),
      },
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByRole("group", { name: "どの画面の話ですか" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("何をしたいですか").closest(".answered-row"),
    ).toHaveTextContent("おかしいかもしれない");
    expect(
      screen.getByText("どのシステムですか").closest(".answered-row"),
    ).toHaveTextContent("システムA（文章から読み取りました）");
    expect(
      screen.getByText("応募者一覧のことです", { selector: ".utterance p" }),
    ).toBeInTheDocument();
    // 続きの質問より後の質問は出ない
    expect(
      screen.queryByText("いつ頃から気になっていますか"),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "答えを待たずに担当者に送る" }),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("STRUCTURED は intake.entries から「質問 → 答え」を再現し、言い直しの確認を出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeStructuredView({
          intake: {
            entries: [
              makeTypeEntry(),
              {
                questionId: "screen",
                kind: "DETAIL",
                text: "どの画面の話ですか",
                answer: { kind: "OPTION", optionId: "detail", label: "詳細" },
              },
              {
                questionId: "when",
                kind: "DETAIL",
                text: "いつ頃から気になっていますか",
                answer: { kind: "UNKNOWN" },
              },
            ],
            next: null,
            complete: true,
          },
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
        body: makeStructuredView({ ...INVESTIGATION_STATE }),
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
        body: makeStructuredView({ ...RESULT_STATE, answer: groundedAnswer }),
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

  it("担当者の番（HANDLING）は、終わりの文言を出さず、「いまの状況」とやり取り、追記の入力欄を出す", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeFiledView({
          messages: [
            makeMessage({ id: "m1", body: "確認します。" }),
            makeMessage({
              id: "m2",
              from: "ASKER",
              kind: "NOTE",
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
    expect(screen.getByText("担当者が確認しています")).toBeInTheDocument();
    expect(screen.getByLabelText("追加で伝える")).toBeInTheDocument();
    // 終わっていないので、終わりの文言は出ない
    expect(
      screen.queryByText("解決してよかったです。この内容は記録しておきます。"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/取り下げました/)).not.toBeInTheDocument();
    // 「答えを待たずに担当者に送る」は担当者に渡した後は出さない
    expect(
      screen.queryByRole("button", { name: "答えを待たずに担当者に送る" }),
    ).not.toBeInTheDocument();

    const thread = screen.getByRole("list", { name: "やり取り" });
    expect(thread).toHaveAttribute("aria-live", "polite");
    const items = within(thread).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    // 古い順。担当者の発言は左、自分の追記は右（色でも分かれる）
    expect(items[0]).toHaveTextContent("確認します。");
    expect(items[0]).toHaveClass("by-operator", "theirs");
    expect(items[1]).toHaveTextContent("お願いします。");
    expect(items[1]).toHaveClass("by-asker", "mine");
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("担当者の発言がまだない間だけ、渡した直後の案内と返信がここに出る旨を、いまの状況の下に出す", async () => {
    installFakeFetch({
      "GET /api/config": {
        body: makeConfig({
          filedGuidance: "担当者が内容を確認して連絡します。",
        }),
      },
      "GET /api/inquiries/inq-1": { body: makeFiledView() },
    });
    open("/inquiries/inq-1");

    const status = await screen.findByText("担当者が確認しています");
    const lead = screen.getByText(
      "担当者に送りました。書いていただいた内容をそのまま添えています。",
    );
    expect(
      status.compareDocumentPosition(lead) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen.getByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("担当者からの返信は、ここに表示されます。"),
    ).toBeInTheDocument();
  });

  it("担当者の発言が1つでもあれば、渡した直後の案内は出さない。本人の追記だけでは出し続ける", async () => {
    installFakeFetch({
      "GET /api/config": {
        body: makeConfig({
          filedGuidance: "担当者が内容を確認して連絡します。",
        }),
      },
      "GET /api/inquiries/inq-1": {
        body: makeFiledView({
          messages: [
            makeMessage({
              id: "m1",
              from: "ASKER",
              kind: "NOTE",
              body: "急いでいます。",
            }),
          ],
        }),
      },
    });
    open("/inquiries/inq-1");
    expect(
      await screen.findByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    cleanup();

    installFakeFetch({
      "GET /api/config": {
        body: makeConfig({
          filedGuidance: "担当者が内容を確認して連絡します。",
        }),
      },
      "GET /api/inquiries/inq-1": {
        body: makeFiledView({
          messages: [makeMessage({ id: "m1", body: "確認します。" })],
        }),
      },
    });
    window.history.replaceState({}, "", "/inquiries/inq-1");
    render(<Root subscribeAnswers={new FakeStream().subscribe} />);
    expect(await screen.findByText("確認します。")).toBeInTheDocument();
    expect(
      screen.queryByText("担当者が内容を確認して連絡します。"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(/担当者に送りました。書いていただいた内容/),
    ).not.toBeInTheDocument();
  });

  it("担当者に渡していない終わり方（AI の答えで解決）は、終わりの文言だけを出し、状況とやり取りは出さない", async () => {
    installFakeFetch({
      ...config,
      "GET /api/inquiries/inq-1": {
        body: makeView({ ...closedState("RESOLVED") }),
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
    expect(screen.queryByLabelText("追加で伝える")).not.toBeInTheDocument();
  });

  it("任意の項目（messages、trace、createdAt）が欠けた応答でも表示できる", async () => {
    const legacy = { ...makeFiledView() } as Record<string, unknown>;
    for (const key of ["messages", "trace", "createdAt"]) {
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
      screen.getByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();
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
    expect(
      screen.queryByRole("group", { name: "何をしたいですか" }),
    ).not.toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(window.location.pathname).toBe("/");
    expect(await findFirstQuestion()).toBeInTheDocument();
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
      "GET /api/config": { body: makeConfig({ mock: true }) },
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
    expect(await findFirstQuestion()).toBeInTheDocument();
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
      "GET /api/config": { body: makeConfig({ trace: false }) },
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    open("/");
    await findFirstQuestion();
    expect(
      screen.queryByRole("link", { name: /開発用/ }),
    ).not.toBeInTheDocument();
  });
});
