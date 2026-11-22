import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Root from "./Root.tsx";
import type { InquiryView } from "./api/types.ts";
import {
  ASKER_REPLY_STATE,
  FakeStream,
  RESULT_STATE,
  closedState,
  groundedAnswer,
  installFakeFetch,
  makeConfig,
  makeFiledView,
  makeMessage,
  makeStructuredView,
  makeView,
  type RecordedCall,
  type RouteResult,
} from "./test/fakes.ts";

/** 本人向けの画面に出してはいけない、仕組みの言葉。 */
const FORBIDDEN =
  /INTAKE|KNOW|DECIDE|HANDLING|ASKER_REPLY|RESULT_CONFIRMATION|phase|awaiting|区間|0次回答|種別|起票|出典/;

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
});

/**
 * `/inquiries/inq-1` を、指定の問い合わせで開く。
 * @param view 開く問い合わせ
 * @param routes ほかの API の応答
 */
function openInquiry(
  view: InquiryView,
  routes: Record<string, RouteResult | ((body: unknown) => RouteResult)> = {},
) {
  window.history.replaceState({}, "", `/inquiries/${view.id}`);
  const calls = installFakeFetch({
    "GET /api/config": {
      body: makeConfig({ filedGuidance: "担当者が内容を確認して連絡します。" }),
    },
    [`GET /api/inquiries/${view.id}`]: { body: view },
    ...routes,
  });
  render(<Root subscribeAnswers={new FakeStream().subscribe} />);
  return calls;
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

/** 担当者の発言を含む、本人が聞き返されている問い合わせ。 */
const askedBack = () =>
  makeFiledView({
    ...ASKER_REPLY_STATE,
    messages: [
      makeMessage({ id: "m1", kind: "ASK", body: "いつからですか。" }),
    ],
  });

/** 担当者の結果が届いている問い合わせ（AI の答えの後に担当者に渡した件）。 */
const resultDelivered = (overrides: Partial<InquiryView> = {}) =>
  makeFiledView({
    ...RESULT_STATE,
    sentImmediately: false,
    answer: groundedAnswer,
    structured: makeStructuredView().structured,
    messages: [
      makeMessage({ id: "m1", kind: "RESULT", body: "設定を直しました。" }),
    ],
    ...overrides,
  });

describe("本人のページ: 担当者に渡した後の状況", () => {
  it("聞き返されているときは、状況を「担当者から質問が届いています」と示し、質問の見出しを付け、入力欄の見出しを「担当者の質問に答える」にする", async () => {
    openInquiry(askedBack());

    expect(
      await screen.findByText("担当者から質問が届いています"),
    ).toBeInTheDocument();
    const thread = screen.getByRole("list", { name: "やり取り" });
    const message = within(thread).getByText("いつからですか。").closest("li")!;
    expect(within(message).getByText("質問")).toBeInTheDocument();
    expect(screen.getByLabelText("担当者の質問に答える")).toBeInTheDocument();
    expect(screen.queryByLabelText("追加で伝える")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("聞き返しへの返事は POST …/messages で送る", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(askedBack(), {
      "POST /api/inquiries/inq-1/messages": {
        body: makeFiledView({
          messages: [
            makeMessage({ id: "m1", kind: "ASK", body: "いつからですか。" }),
            makeMessage({
              id: "m2",
              from: "ASKER",
              kind: "NOTE",
              authorName: "開発用ユーザー",
              body: "昨日からです。",
            }),
          ],
        }),
      },
    });

    await user.type(
      await screen.findByLabelText("担当者の質問に答える"),
      "昨日からです。",
    );
    await user.click(screen.getByRole("button", { name: "送る" }));

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/messages")[0].body,
    ).toEqual({ body: "昨日からです。" });
    // 担当者の番になり、入力欄の見出しと状況が変わる
    expect(
      await screen.findByText("担当者が確認しています"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("追加で伝える")).toBeInTheDocument();
  });

  it("担当者の結果が届いているときは、状況を示し、結果の見出しと、「やりたかったことは、進められそうですか。」を出す", async () => {
    openInquiry(resultDelivered());

    expect(
      await screen.findByText("担当者から結果が届いています"),
    ).toBeInTheDocument();
    const thread = screen.getByRole("list", { name: "やり取り" });
    expect(
      within(
        within(thread).getByText("設定を直しました。").closest("li")!,
      ).getByText("結果"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("やりたかったことは、進められそうですか。"),
    ).toBeInTheDocument();
    // AI の答えは経過の要約として残るが、その確かめのボタンは出さない（確かめは1か所）
    expect(
      screen.getByRole("heading", { name: "調べた結果" }),
    ).toBeInTheDocument();
    expect(
      screen.getAllByRole("button", { name: "解決しました" }),
    ).toHaveLength(1);
    expect(
      screen.queryByRole("button", {
        name: /まだ解決していません（担当者に送る）/,
      }),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(FORBIDDEN);
  });

  it("「解決しました」は resolved: true を送り、解決済みで終わる。終わった後も追記で開き直せる旨を示す", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(resultDelivered(), {
      "POST /api/inquiries/inq-1/resolution": {
        body: makeFiledView({
          ...closedState("RESOLVED"),
          answer: groundedAnswer,
          structured: makeStructuredView().structured,
          messages: [
            makeMessage({
              id: "m1",
              kind: "RESULT",
              body: "設定を直しました。",
            }),
          ],
        }),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: "解決しました" }),
    );

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/resolution")[0].body,
    ).toEqual({ resolved: true });
    expect(
      await screen.findByText(
        "解決してよかったです。この内容は記録しておきます。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("送ると、担当者の番で開き直します。"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("追加で伝える")).toBeInTheDocument();
    expect(
      screen.queryByText("やりたかったことは、進められそうですか。"),
    ).not.toBeInTheDocument();
  });

  it("「まだ解決していません」は、どこが解決していないかの欄（任意）と「担当者に送る」を出し、書いた一言を note に付けて送る", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(resultDelivered(), {
      "POST /api/inquiries/inq-1/resolution": {
        body: makeFiledView({ messages: [] }),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: "まだ解決していません" }),
    );
    const note = screen.getByLabelText("どこが解決していませんか（任意）");
    expect(note).toHaveFocus();
    await user.type(note, "  再起動すると戻ります  ");
    await user.click(screen.getByRole("button", { name: "担当者に送る" }));

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/resolution")[0].body,
    ).toEqual({ resolved: false, note: "再起動すると戻ります" });
    // 担当者の番になる
    expect(
      await screen.findByText("担当者が確認しています"),
    ).toBeInTheDocument();
  });

  it("一言を書かずに送ると note は付けない。「やめる」で欄を閉じる", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(resultDelivered(), {
      "POST /api/inquiries/inq-1/resolution": {
        body: makeFiledView({ messages: [] }),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: "まだ解決していません" }),
    );
    await user.click(screen.getByRole("button", { name: "やめる" }));
    expect(
      screen.queryByLabelText("どこが解決していませんか（任意）"),
    ).not.toBeInTheDocument();
    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/resolution"),
    ).toHaveLength(0);

    await user.click(
      screen.getByRole("button", { name: "まだ解決していません" }),
    );
    await user.click(screen.getByRole("button", { name: "担当者に送る" }));

    await waitFor(() =>
      expect(
        callsTo(calls, "POST", "/api/inquiries/inq-1/resolution")[0].body,
      ).toEqual({ resolved: false }),
    );
  });

  it("AI の答えを確かめるときも、調べた結果の下に「やりたかったことは、進められそうですか。」を添える。ボタンは従来どおり", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(
      makeStructuredView({ ...RESULT_STATE, answer: groundedAnswer }),
      {
        "POST /api/inquiries/inq-1/resolution": {
          body: makeFiledView({
            answer: groundedAnswer,
            sentImmediately: false,
          }),
        },
      },
    );

    const question = await screen.findByText(
      "やりたかったことは、進められそうですか。",
    );
    const result = screen.getByRole("heading", { name: "調べた結果" });
    expect(
      result.compareDocumentPosition(question) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: "解決しました" })).toHaveClass(
      "primary",
    );
    await user.click(
      screen.getByRole("button", {
        name: "まだ解決していません（担当者に送る）",
      }),
    );

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/resolution")[0].body,
    ).toEqual({ resolved: false });
    // 担当者に渡した後は、いまの状況とやり取りが出る
    expect(
      await screen.findByRole("heading", { name: "いまの状況" }),
    ).toBeInTheDocument();
  });

  it("調べている間・問いの確認・入力の途中では、担当者に渡す前なので、いまの状況とやり取りは出さない", async () => {
    openInquiry(makeStructuredView());

    expect(
      await screen.findByText("つまり、こういうことですね。"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "いまの状況" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("追加で伝える")).not.toBeInTheDocument();
  });
});

describe("本人のページ: 終わり方", () => {
  const cases = [
    {
      ending: "RESOLVED",
      label: "解決済み",
      text: "解決してよかったです。この内容は記録しておきます。",
    },
    {
      ending: "ANSWERED",
      label: "回答済み",
      text: "お返事がなかったため、いったん終了しました。解決していなければ、このページから続きを送れます。",
    },
    {
      ending: "DECLINED",
      label: "見送り",
      text: "担当者から、今回は対応を見送るとの連絡がありました。",
    },
    {
      ending: "WITHDRAWN",
      label: "取り下げ",
      text: "この問い合わせは取り下げました。",
    },
  ] as const;

  for (const { ending, label, text } of cases) {
    it(`${label}: 終わりの文言と、本人に見せる言葉を示す`, async () => {
      openInquiry(
        makeFiledView({
          ...closedState(ending),
          declined:
            ending === "DECLINED"
              ? { reasonKind: "KNOWN_LIMITATION", reason: "仕様のためです。" }
              : null,
          messages: [],
        }),
      );

      expect(await screen.findByText(text)).toBeInTheDocument();
      expect(
        screen.getByText(label, { selector: ".handling-status" }),
      ).toBeInTheDocument();
      if (ending === "DECLINED") {
        // 続けて、担当者が書いた理由
        expect(screen.getByText("仕様のためです。")).toBeInTheDocument();
      }
      expect(document.body.textContent).not.toMatch(FORBIDDEN);
    });
  }

  it.each(["RESOLVED", "ANSWERED", "DECLINED"] as const)(
    "終わり方が %s で、担当者に渡したことがある件は、入力欄を残して開き直せる旨を示す",
    async (ending) => {
      openInquiry(makeFiledView({ ...closedState(ending), messages: [] }));

      expect(
        await screen.findByText("送ると、担当者の番で開き直します。"),
      ).toBeInTheDocument();
      expect(screen.getByLabelText("追加で伝える")).toBeInTheDocument();
    },
  );

  it("取り下げた件には入力欄を出さない", async () => {
    openInquiry(makeFiledView({ ...closedState("WITHDRAWN"), messages: [] }));

    await screen.findByText("この問い合わせは取り下げました。");
    expect(screen.queryByLabelText("追加で伝える")).not.toBeInTheDocument();
    expect(
      screen.queryByText("送ると、担当者の番で開き直します。"),
    ).not.toBeInTheDocument();
  });

  it("終わった件に追記すると POST …/messages を呼び、担当者の番で開き直したページになる", async () => {
    const user = userEvent.setup();
    const calls = openInquiry(
      makeFiledView({ ...closedState("RESOLVED"), messages: [] }),
      {
        "POST /api/inquiries/inq-1/messages": {
          body: makeFiledView({
            messages: [
              makeMessage({
                id: "m1",
                from: "ASKER",
                kind: "NOTE",
                body: "また再発しました。",
              }),
            ],
          }),
        },
      },
    );

    await user.type(
      await screen.findByLabelText("追加で伝える"),
      "また再発しました。",
    );
    await user.click(screen.getByRole("button", { name: "送る" }));

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/messages")[0].body,
    ).toEqual({ body: "また再発しました。" });
    expect(
      await screen.findByText("担当者が確認しています"),
    ).toBeInTheDocument();
    // 開き直したので、終わりの文言と開き直しの案内は消える
    expect(
      screen.queryByText("解決してよかったです。この内容は記録しておきます。"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("送ると、担当者の番で開き直します。"),
    ).not.toBeInTheDocument();
  });

  it("「いまの状況」は、状態が変わったことが読み上げにも伝わる領域（aria-live）にある", async () => {
    openInquiry(makeFiledView({ messages: [] }));

    const status = await screen.findByText("担当者が確認しています");
    expect(status).toHaveAttribute("aria-live", "polite");
  });
});

describe("本人のページ: 取り下げ", () => {
  it("終わっていない問い合わせのページの最下部に、控えめなリンクを置く（入口の質問の間にも置く）", async () => {
    openInquiry(makeView());

    const link = await screen.findByRole("button", {
      name: "この問い合わせを取り下げる",
    });
    expect(link).toHaveClass("link");
    // 質問よりも後ろ（最下部）
    expect(
      screen
        .getByRole("group", { name: "何をしたいですか" })
        .compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("担当者に渡した後の、終わっていない件にも置く。終わった件には置かない", async () => {
    openInquiry(makeFiledView({ messages: [] }));
    expect(
      await screen.findByRole("button", { name: "この問い合わせを取り下げる" }),
    ).toBeInTheDocument();
  });

  it.each(["RESOLVED", "ANSWERED", "DECLINED", "WITHDRAWN"] as const)(
    "終わり方が %s の件には、リンクを出さない",
    async (ending) => {
      openInquiry(makeFiledView({ ...closedState(ending), messages: [] }));

      await screen.findByRole("heading", { name: "いまの状況" });
      expect(
        screen.queryByRole("button", { name: "この問い合わせを取り下げる" }),
      ).not.toBeInTheDocument();
    },
  );

  it("押すと、その場に確認が出てフォーカスが移る。ブラウザの確認の窓は使わない。「やめる」で何も起きずリンクに戻る", async () => {
    const user = userEvent.setup();
    const confirm = vi.fn(() => true);
    vi.stubGlobal("confirm", confirm);
    const calls = openInquiry(makeFiledView({ messages: [] }));

    await user.click(
      await screen.findByRole("button", { name: "この問い合わせを取り下げる" }),
    );

    const question = screen.getByText(
      "取り下げますか。取り下げた後は、続きを送れません。",
    );
    expect(question).toHaveFocus();
    expect(
      screen.queryByRole("button", { name: "この問い合わせを取り下げる" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "取り下げる" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "やめる" }));

    expect(
      screen.queryByText("取り下げますか。取り下げた後は、続きを送れません。"),
    ).not.toBeInTheDocument();
    const link = screen.getByRole("button", {
      name: "この問い合わせを取り下げる",
    });
    await waitFor(() => expect(link).toHaveFocus());
    expect(calls.filter((c) => c.method !== "GET")).toHaveLength(0);
    expect(confirm).not.toHaveBeenCalled();
  });

  it("「取り下げる」で POST …/withdraw を呼び、取り下げの終わりの文言を示す。リンクは消える", async () => {
    const user = userEvent.setup();
    const confirm = vi.fn(() => true);
    vi.stubGlobal("confirm", confirm);
    const calls = openInquiry(makeFiledView({ messages: [] }), {
      "POST /api/inquiries/inq-1/withdraw": {
        body: makeFiledView({ ...closedState("WITHDRAWN"), messages: [] }),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: "この問い合わせを取り下げる" }),
    );
    await user.click(screen.getByRole("button", { name: "取り下げる" }));

    expect(
      callsTo(calls, "POST", "/api/inquiries/inq-1/withdraw"),
    ).toHaveLength(1);
    expect(
      await screen.findByText("この問い合わせは取り下げました。"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "この問い合わせを取り下げる" }),
    ).not.toBeInTheDocument();
    expect(confirm).not.toHaveBeenCalled();
  });

  it("入口の途中の問い合わせも取り下げられ、担当者に渡していなければ終わりの文言だけを示す", async () => {
    const user = userEvent.setup();
    openInquiry(makeView(), {
      "POST /api/inquiries/inq-1/withdraw": {
        body: makeView({ ...closedState("WITHDRAWN") }),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: "この問い合わせを取り下げる" }),
    );
    await user.click(screen.getByRole("button", { name: "取り下げる" }));

    expect(
      await screen.findByText("この問い合わせは取り下げました。"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "いまの状況" }),
    ).not.toBeInTheDocument();
  });
});
