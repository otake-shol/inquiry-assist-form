import {
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
  subscribeAnswerStream,
  subscribeOperatorStream,
} from "./api/stream.ts";
import type { AppConfig, InquiryView } from "./api/types.ts";
import { LANGUAGE_STORAGE_KEY } from "./i18n/language.ts";
import { formatFiledAt } from "./operator/labels.ts";
import {
  ASKER_REPLY_STATE,
  FakeOperatorStream,
  FakeStream,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
  groundedAnswer,
  installFakeFetch,
  makeConfig,
  makeDetail,
  makeFiledView,
  makeItem,
  makeListItem,
  makeMessage,
  makeQuestion,
  makeStructuredView,
  makeTraceEntry,
  makeTypeEntry,
  makeView,
  type RecordedCall,
  type RouteResult,
} from "./test/fakes.ts";

const JAPANESE = /[\u3040-\u30ff\u3400-\u9fff]/;

/** 言語の切り替え（その言語自身の名前で書く）を除いた、画面全体の文字。 */
function textOutsideLanguageSwitch(): string {
  const copy = document.body.cloneNode(true) as HTMLElement;
  copy.querySelectorAll(".lang-switch").forEach((node) => node.remove());
  return copy.textContent ?? "";
}

/** 英語の設定（API が `Accept-Language: en` に返す名前）。 */
const EN_CONFIG: AppConfig = makeConfig({
  filedGuidance: "The support team will reply on this page.",
  types: [
    { id: "HOW_TO", label: "I want to know how to use it", hint: "Steps" },
    { id: "HOW_IT_WORKS", label: "I want to know how it works", hint: "Why" },
    { id: "LOOKS_WRONG", label: "Something looks wrong", hint: "Unexpected" },
    { id: "DO_FOR_ME", label: "I need something done", hint: "A request" },
    { id: "CHANGE_REQUEST", label: "I want a change", hint: "A feature" },
  ],
  urgencies: [
    { id: "NORMAL", label: "Normal" },
    { id: "HURRY", label: "Urgent" },
    { id: "BLOCKED", label: "Work is blocked" },
  ],
  systems: [
    {
      id: "job-admin",
      label: "Job admin",
      functions: [{ id: "posting", label: "Job posting" }],
    },
  ],
});

type Routes = Record<
  string,
  | RouteResult
  | ((body: unknown, headers: Record<string, string>) => RouteResult)
>;

/** `Accept-Language` に合わせて、日本語か英語の設定を返す設定の応答。 */
const bilingualConfig = (ja: AppConfig = makeConfig()) => ({
  "GET /api/config": (_body: unknown, headers: Record<string, string>) => ({
    body: headers["accept-language"] === "en" ? EN_CONFIG : ja,
  }),
});

/** ブラウザの言語を英語にする。 */
function englishBrowser() {
  vi.spyOn(window.navigator, "languages", "get").mockReturnValue([
    "en-US",
    "en",
  ]);
}

/** 保存した言語を英語にする。 */
function storedEnglish() {
  window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

/**
 * 指定のパスを開いた状態で Root を描く。
 * @param path 開くパス
 */
function open(path: string) {
  window.history.replaceState({}, "", path);
  const answers = new FakeStream();
  const operator = new FakeOperatorStream();
  render(
    <Root
      subscribeAnswers={answers.subscribe}
      subscribeOperator={operator.subscribe}
    />,
  );
  return { answers, operator };
}

/** 問い合わせを取得する応答。 */
const inquiry = (view: InquiryView) => ({
  [`GET /api/inquiries/${view.id}`]: { body: view },
});

const countCalls = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

describe("言語の切り替え（本人の画面）", () => {
  it("最初の言語は、ブラウザの言語（en-US は en）に合わせる", async () => {
    englishBrowser();
    installFakeFetch({ ...bilingualConfig() });
    open("/");

    expect(
      await screen.findByRole("group", { name: "What can we help you with?" }),
    ).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Inquiry Assist");
  });

  it("保存した言語が、ブラウザの言語より優先される", async () => {
    storedEnglish();
    installFakeFetch({ ...bilingualConfig() });
    open("/");
    expect(
      await screen.findByRole("group", { name: "What can we help you with?" }),
    ).toBeInTheDocument();
    cleanup();

    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "ja");
    englishBrowser();
    open("/");
    expect(
      await screen.findByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("ja");
  });

  it("切り替えを押すと、固定の文言、種類と緊急度の名前、<html lang>、タブの題名が英語になり、選んだ言語が保存される", async () => {
    const user = userEvent.setup();
    installFakeFetch({ ...bilingualConfig() });
    open("/");

    const group = await screen.findByRole("group", { name: "言語" });
    const buttons = within(group).getAllByRole("button");
    expect(buttons.map((b) => b.textContent)).toEqual(["日本語", "English"]);
    expect(buttons[0]).toHaveAttribute("aria-current", "true");
    expect(buttons[1]).not.toHaveAttribute("aria-current");
    expect(await screen.findByText("使い方を知りたい")).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("ja");

    await user.click(within(group).getByRole("button", { name: "English" }));

    expect(
      await screen.findByRole("group", { name: "What can we help you with?" }),
    ).toBeInTheDocument();
    // 種類の名前と説明は API が返したもの
    expect(
      await screen.findByRole("button", {
        name: "I want to know how to use it",
      }),
    ).toHaveAccessibleDescription("Steps");
    expect(
      screen.getByRole("button", { name: "Not sure" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Write it in your own words" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("group", { name: "Is it urgent?" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Urgent" })).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "My inquiries" }),
    ).toBeInTheDocument();
    const english = screen.getByRole("button", { name: "English" });
    expect(english).toHaveAttribute("aria-current", "true");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Inquiry Assist");
    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe("en");
    expect(document.body.textContent).not.toMatch(
      /何をしたいですか|わからない|急ぎ/,
    );

    // 日本語に戻せる
    await user.click(screen.getByRole("button", { name: "日本語" }));
    expect(
      await screen.findByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
    expect(document.title).toBe("問い合わせアシスト");
    expect(window.localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe("ja");
  });

  it("切り替えると、設定を取得し直す。切り替えた後の fetch に Accept-Language: en が付く", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...bilingualConfig(),
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/intake/answers": {
        body: makeView({
          intake: {
            entries: [makeTypeEntry()],
            next: makeQuestion(),
            complete: false,
          },
        }),
      },
    });
    open("/");
    await screen.findByRole("button", { name: "使い方を知りたい" });
    const configs = () => countCalls(calls, "GET", "/api/config");
    expect(configs()).toHaveLength(1);
    expect(configs()[0].headers["accept-language"]).toBe("ja");

    await user.click(screen.getByRole("button", { name: "English" }));
    await screen.findByRole("button", { name: "I want to know how to use it" });

    expect(configs()).toHaveLength(2);
    expect(configs()[1].headers["accept-language"]).toBe("en");

    await user.click(
      screen.getByRole("button", { name: "I want to know how to use it" }),
    );
    await screen.findByText("どのシステムですか");
    const posts = calls.filter((c) => c.method === "POST");
    expect(posts.length).toBeGreaterThan(0);
    for (const post of posts)
      expect(post.headers["accept-language"]).toBe("en");
  });

  it("切り替えると、開いている問い合わせを取得し直し、入力の途中の文章は消えない", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...bilingualConfig(),
      "GET /api/inquiries/inq-1": (_b, headers) => ({
        body:
          headers["accept-language"] === "en"
            ? makeView({
                language: "en",
                intake: {
                  entries: [],
                  next: makeQuestion({
                    questionId: "since",
                    kind: "DETAIL",
                    text: "Since when is this happening?",
                    options: [{ id: "today", label: "Since today" }],
                  }),
                  complete: false,
                },
              })
            : makeView({
                intake: {
                  entries: [],
                  next: makeQuestion({
                    questionId: "since",
                    kind: "DETAIL",
                    text: "いつから起きていますか",
                    options: [{ id: "today", label: "今日から" }],
                  }),
                  complete: false,
                },
              }),
      }),
    });
    open("/inquiries/inq-1");

    await screen.findByRole("group", { name: "いつから起きていますか" });
    await user.click(screen.getByRole("button", { name: "文章で書く" }));
    const box = screen.getByLabelText(/うまくまとまっていなくて/);
    await user.type(box, "掲載期限のことです");

    await user.click(screen.getByRole("button", { name: "English" }));

    expect(
      await screen.findByRole("group", {
        name: "Since when is this happening?",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Since today" }),
    ).toBeInTheDocument();
    const gets = countCalls(calls, "GET", "/api/inquiries/inq-1");
    expect(gets).toHaveLength(2);
    expect(gets[1].headers["accept-language"]).toBe("en");
    // 入力の途中の文章は残る
    expect(
      screen.getByLabelText(/It is fine if it is not well organized/),
    ).toHaveValue("掲載期限のことです");
  });

  it("使える言語が1つなら、切り替えを出さない（API が1つだけ返す場合、設定が分からない場合）", async () => {
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ languages: ["ja"] }) },
    });
    open("/");
    await screen.findByRole("group", { name: "何をしたいですか" });
    expect(
      screen.queryByRole("group", { name: "言語" }),
    ).not.toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    // 1つしかないときは、保存した言語やブラウザの言語に関係なく、その言語で表示する
    storedEnglish();
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ languages: ["ja"] }) },
    });
    open("/");
    await waitFor(() =>
      expect(
        screen.getByRole("group", { name: "何をしたいですか" }),
      ).toBeVisible(),
    );
    expect(screen.queryByRole("button", { name: "English" })).toBeNull();
    cleanup();
    vi.unstubAllGlobals();

    installFakeFetch({
      "GET /api/config": { status: 500, body: { code: "X", message: "x" } },
    });
    open("/");
    await screen.findByRole("button", { name: /文章で書く|Write it/ });
    expect(screen.queryByRole("button", { name: "English" })).toBeNull();
  });

  it("API が画面の持たない言語だけを返すときは、日本語", async () => {
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ languages: ["fr", "ja"] }) },
    });
    open("/");
    await screen.findByRole("group", { name: "何をしたいですか" });
    expect(
      screen.queryByRole("group", { name: "言語" }),
    ).not.toBeInTheDocument();
  });
});

describe("言語をストリームに渡す", () => {
  /** EventSource の偽物。つないだ URL を残す。 */
  function stubEventSource() {
    const urls: string[] = [];
    class FakeEventSource {
      constructor(url: string) {
        urls.push(url);
      }
      addEventListener() {}
      close() {}
    }
    vi.stubGlobal("EventSource", FakeEventSource);
    return urls;
  }

  it("進捗のストリームと担当者のストリームの URL に、クエリの lang が付く", () => {
    const urls = stubEventSource();
    const close = subscribeAnswerStream(
      "inq-1",
      { onProgress() {}, onDone() {}, onFailed() {} },
      "en",
    );
    close();
    subscribeOperatorStream(
      { onFiled() {}, onUpdated() {}, onReconnected() {} },
      "ja",
    )();

    expect(urls).toEqual([
      "/api/inquiries/inq-1/answer-stream?lang=en",
      "/api/operator/stream?lang=ja",
    ]);
  });

  it("画面は、いまの言語で進捗のストリームを購読する", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(makeStructuredView({ ...INVESTIGATION_STATE })),
    });
    const { answers } = open("/inquiries/inq-1");

    await waitFor(() => expect(answers.subscriptions).toHaveLength(1));
    expect(answers.latest.language).toBe("en");
    expect(
      screen.getByRole("heading", { name: "Looking into it" }),
    ).toBeInTheDocument();
  });

  it("担当者の画面は、言語を切り替えると、ストリームをつなぎ直し、一覧と設定を取得し直す", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": (_b, headers) => ({
        body: {
          unreadCount: 0,
          items: [
            makeItem({
              read: true,
              type: {
                id: "LOOKS_WRONG",
                label:
                  headers["accept-language"] === "en"
                    ? "Something looks wrong"
                    : "おかしいかもしれない",
              },
            }),
          ],
        },
      }),
    });
    const { operator } = open("/operator");

    await screen.findByRole("button", { name: /おかしいかもしれない/ });
    expect(operator.subscriptions).toHaveLength(1);
    expect(operator.latest.language).toBe("ja");

    await user.click(screen.getByRole("button", { name: "English" }));

    await screen.findByRole("button", { name: /Something looks wrong/ });
    await waitFor(() => expect(operator.subscriptions).toHaveLength(2));
    expect(operator.subscriptions[0].closed).toBe(true);
    expect(operator.latest.language).toBe("en");
    expect(countCalls(calls, "GET", "/api/operator/inquiries")).toHaveLength(2);
    expect(countCalls(calls, "GET", "/api/config")).toHaveLength(2);
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Incoming inquiries");
    expect(
      screen.getByRole("heading", { name: "Incoming inquiries" }),
    ).toBeVisible();
  });
});

describe("英語の本人のページ", () => {
  /** 英語の画面で、指定の問い合わせを開く。 */
  function openEnglish(view: InquiryView, routes: Routes = {}) {
    storedEnglish();
    installFakeFetch({ ...bilingualConfig(), ...inquiry(view), ...routes });
    return open(`/inquiries/${view.id}`);
  }

  const english = (
    overrides: Partial<InquiryView> = {},
  ): Partial<InquiryView> => ({
    language: "en",
    ...overrides,
  });

  it("入口の質問: 質問、選択肢、「Not sure」、文章で書く、緊急度が英語で出る", async () => {
    openEnglish(
      makeView({
        language: "en",
        intake: {
          entries: [
            {
              ...makeTypeEntry("HOW_TO", "I want to know how to use it"),
              text: "What can we help you with?",
            },
          ],
          next: makeQuestion({
            questionId: "since",
            kind: "DETAIL",
            text: "Since when is this happening?",
            options: [{ id: "today", label: "Since today" }],
          }),
          complete: false,
        },
      }),
    );

    const group = await screen.findByRole("group", {
      name: "Since when is this happening?",
    });
    expect(
      within(group)
        .getAllByRole("button")
        .map((b) => b.textContent),
    ).toEqual(["Since today", "Not sure"]);
    expect(
      screen.getByRole("button", { name: "Write it in your own words" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Normal" })).toBeChecked();
    expect(
      screen.getByRole("button", { name: "Withdraw this inquiry" }),
    ).toBeInTheDocument();
    expect(textOutsideLanguageSwitch()).not.toMatch(JAPANESE);
  });

  it("言い直しの確認: 「Here is what I understood.」と「That's right」「Not quite」", async () => {
    openEnglish(makeStructuredView(english()));

    expect(
      await screen.findByText("Here is what I understood."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "That's right" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Not quite" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Back to the previous question" }),
    ).toBeInTheDocument();
  });

  it("調べている間と調べた結果: 「What I found」「Sources」「Details for the support team」、確かめのボタン", async () => {
    openEnglish(
      makeStructuredView(english({ ...RESULT_STATE, answer: groundedAnswer })),
    );

    expect(
      await screen.findByRole("heading", { name: "What I found" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Sources (1)")).toBeInTheDocument();
    expect(
      screen.getByText("Details for the support team"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Can you now move forward with what you wanted to do?"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "This solved it" })).toHaveClass(
      "primary",
    );
    expect(
      screen.getByRole("button", {
        name: "Not solved yet (send to the support team)",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText("[Code]", { exact: false })).toBeInTheDocument();
  });

  it("担当者が確認している: いまの状況、渡した直後の案内、返信が出る場所", async () => {
    openEnglish(makeFiledView(english({ messages: [] })));

    expect(
      await screen.findByRole("heading", { name: "Current status" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("The support team is looking into it"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "I sent this to the support team. I attached what you wrote as it is.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("The support team will reply on this page."),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Replies from the support team will appear here."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Add more details")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Write another inquiry" }),
    ).toBeInTheDocument();
  });

  it("聞き返されている: 状況、「Question」の見出し、入力欄の見出し", async () => {
    openEnglish(
      makeFiledView(
        english({
          ...ASKER_REPLY_STATE,
          messages: [
            makeMessage({
              id: "m1",
              kind: "ASK",
              body: "Since when is this happening?",
            }),
          ],
        }),
      ),
    );

    expect(
      await screen.findByText("The support team has a question for you"),
    ).toBeInTheDocument();
    expect(screen.getByText("Question")).toHaveClass("message-kind");
    expect(
      screen.getByLabelText("Answer the support team's question"),
    ).toBeInTheDocument();
  });

  it("担当者の結果が届いている: 状況、「Result」の見出し、確かめの問いと、解決していない点を書く欄", async () => {
    const user = userEvent.setup();
    openEnglish(
      makeFiledView(
        english({
          ...RESULT_STATE,
          sentImmediately: false,
          messages: [
            makeMessage({ id: "m1", kind: "RESULT", body: "Fixed it." }),
          ],
        }),
      ),
    );

    expect(
      await screen.findByText("The support team has sent you a result"),
    ).toBeInTheDocument();
    expect(screen.getByText("Result")).toHaveClass("message-kind");
    expect(
      screen.getByText("Can you now move forward with what you wanted to do?"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Not solved yet" }));
    expect(
      screen.getByLabelText("What is still unsolved? (optional)"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Send to the support team" }),
    ).toBeInTheDocument();
  });

  it.each([
    [
      "RESOLVED",
      "Resolved",
      "I am glad this is resolved. I will keep a record of it.",
    ],
    [
      "ANSWERED",
      "Answered",
      "This was closed because there was no reply. If it is not resolved, you can send more from this page.",
    ],
    [
      "DECLINED",
      "Declined",
      "The support team will not be taking action on this inquiry.",
    ],
    ["WITHDRAWN", "Withdrawn", "This inquiry has been withdrawn."],
  ] as const)(
    "終わり方 %s: 本人に見せる言葉と終わりの文言が英語で出る",
    async (ending, label, text) => {
      openEnglish(
        makeFiledView(
          english({
            ...closedState(ending),
            declined:
              ending === "DECLINED"
                ? { reasonKind: "OTHER", reason: "It works as designed." }
                : null,
            messages: [],
          }),
        ),
      );

      expect(await screen.findByText(text)).toBeInTheDocument();
      expect(
        screen.getByText(label, { selector: ".handling-status" }),
      ).toBeInTheDocument();
      if (ending === "DECLINED") {
        expect(screen.getByText("It works as designed.")).toBeInTheDocument();
      }
      expect(textOutsideLanguageSwitch()).not.toMatch(JAPANESE);
    },
  );

  it("取り下げの確認が英語で出る", async () => {
    const user = userEvent.setup();
    openEnglish(makeFiledView(english({ messages: [] })));

    await user.click(
      await screen.findByRole("button", { name: "Withdraw this inquiry" }),
    );

    expect(
      screen.getByText(
        "Withdraw this inquiry? You cannot add anything after you withdraw it.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Withdraw" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeInTheDocument();
  });

  it("送った問い合わせのボード: 列の名前、カードの本人に見せる言葉、日時の書き方（24時間）", async () => {
    storedEnglish();
    const at = new Date(2026, 10, 21, 0, 30).toISOString();
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/inquiries": {
        body: {
          unreadReplyCount: 1,
          items: [
            makeListItem({
              id: "a",
              language: "en",
              summary: "Please extend the deadline.",
              createdAt: at,
              messageCount: 2,
              unreadReply: true,
              ...ASKER_REPLY_STATE,
            }),
            makeListItem({ id: "b", language: "en", summary: "Waiting one." }),
            makeListItem({
              id: "c",
              language: "en",
              summary: "Done one.",
              ...closedState("RESOLVED"),
            }),
          ],
        },
      },
    });
    open("/inquiries");

    await screen.findByText("Please extend the deadline.");
    expect(
      screen
        .getAllByRole("heading", { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(["Your turn", "Their turn", "Closed"]);
    expect(
      screen.getByRole("region", { name: /^Your turn 1\s*item$/ }),
    ).toBeInTheDocument();
    const card = screen
      .getByText("Please extend the deadline.")
      .closest("li") as HTMLElement;
    expect(card).toHaveTextContent("The support team has a question for you");
    expect(card).toHaveTextContent("2 messages");
    expect(card).toHaveTextContent("Unread reply");
    // 24時間（午前0時は 00:30）で、英語の書き方
    expect(card.querySelector("time")?.textContent).toBe("11/21, 00:30");
    expect(
      screen.getByText("Please extend the deadline.", {
        selector: ".inquiry-summary",
      }),
    ).toHaveAttribute("lang", "en");
    expect(
      within(
        screen.getByRole("region", { name: /^Closed 1\s*item$/ }),
      ).getByText("Resolved"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /My inquiries/ }),
    ).toBeInTheDocument();
  });

  it("日本語では、従来の書き方（月/日 時:分）のまま", () => {
    const at = new Date(2026, 10, 21, 0, 30).toISOString();
    expect(formatFiledAt(at, "ja-JP")).toBe("11/21 00:30");
    expect(formatFiledAt(at, "en-US")).toBe("11/21, 00:30");
    expect(formatFiledAt("not a date", "en-US")).toBe("not a date");
  });
});

describe("英語の返事がないときの知らせ", () => {
  const CLOSES_AT = new Date(2026, 11, 1, 12, 0).toISOString();
  const REMINDED_AT = new Date(2026, 10, 24, 12, 0).toISOString();
  const REMIND_AT = new Date(2026, 10, 24, 9, 0).toISOString();

  it("本人のページ: 知らせた後だけ、英語の文言と英語の書式の日付（December 1）を出す", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeStructuredView({
          ...RESULT_STATE,
          language: "en",
          answer: groundedAnswer,
          noReply: {
            endsAs: "ANSWERED",
            remindAt: REMIND_AT,
            remindedAt: REMINDED_AT,
            closesAt: CLOSES_AT,
          },
        }),
      ),
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText(
        "There has been no reply, so this inquiry will be closed as “Answered” on December 1. Please choose below whether it was resolved. You can still send a follow-up from this page after it is closed.",
      ),
    ).toBeInTheDocument();
  });

  it("本人のページ: 渡していない「回答済み」の件に、続きを送る欄が出る", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeStructuredView({
          ...closedState("ANSWERED"),
          language: "en",
          handedOverAt: null,
          answer: groundedAnswer,
        }),
      ),
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText(
        "This was closed because there was no reply. If it is not resolved, you can send more from this page.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Add more details")).toBeInTheDocument();
    expect(
      screen.queryByText("Replies from the support team will appear here."),
    ).not.toBeInTheDocument();
  });

  it("担当者のパネル: 予定の2通りの行と、履歴の新しい言葉が英語で出る", async () => {
    const user = userEvent.setup();
    storedEnglish();
    let detail = makeDetail({
      read: true,
      language: "en",
      messages: [],
      ...RESULT_STATE,
      noReply: {
        endsAs: "ANSWERED",
        remindAt: REMIND_AT,
        remindedAt: null,
        closesAt: null,
      },
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
    });
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [makeItem({ read: true, language: "en", ...RESULT_STATE })],
        },
      },
      "GET /api/operator/inquiries/op-1": () => ({ body: detail }),
    });
    open("/operator");

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    expect(
      await within(panel).findByText(
        "If there is no reply, the requester will be reminded on November 24.",
      ),
    ).toBeInTheDocument();

    await user.click(within(panel).getByText("Status history"));
    const rows = within(
      within(panel).getByText("Status history").closest("details")!,
    ).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("Time limit");
    expect(rows[0]).toHaveTextContent("Requester reminded (no reply)");
    expect(rows[1]).toHaveTextContent("Closed after no reply");
    expect(rows[1]).toHaveTextContent("Closed (Answered)");

    detail = {
      ...detail,
      noReply: {
        endsAs: "ANSWERED",
        remindAt: REMIND_AT,
        remindedAt: REMINDED_AT,
        closesAt: CLOSES_AT,
      },
    };
    cleanup();
    open("/operator");
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const second = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    expect(
      await within(second).findByText(
        "The requester was reminded on November 24. If there is no reply, this will be closed as “Answered” on December 1.",
      ),
    ).toBeInTheDocument();
  });
});

describe("英語の聞き返しに返事がないときの画面", () => {
  const CLOSES_AT = new Date(2026, 11, 1, 12, 0).toISOString();
  const REMINDED_AT = new Date(2026, 10, 24, 12, 0).toISOString();
  const REMIND_AT = new Date(2026, 10, 24, 9, 0).toISOString();

  it("本人のページ: 知らせが、入力欄のすぐ上に英語で出る", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          ...ASKER_REPLY_STATE,
          language: "en",
          messages: [
            makeMessage({ id: "m1", kind: "ASK", body: "Since when?" }),
          ],
          noReply: {
            endsAs: "WITHDRAWN",
            remindAt: REMIND_AT,
            remindedAt: REMINDED_AT,
            closesAt: CLOSES_AT,
          },
        }),
      ),
    });
    open("/inquiries/inq-1");

    const notice = await screen.findByText(
      "There has been no reply, so this inquiry will be closed as “Withdrawn” on December 1. To continue, please answer the support team's question below. You can still send a follow-up from this page after it is closed.",
    );
    const box = screen.getByLabelText("Answer the support team's question");
    expect(
      notice.compareDocumentPosition(box) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("本人のページ: 時間で取り下げになった件の終わりの文言と、続きを送る欄", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          ...closedState("WITHDRAWN"),
          closedBy: "TIME",
          language: "en",
          messages: [],
        }),
      ),
    });
    open("/inquiries/inq-1");

    expect(
      await screen.findByText(
        "This inquiry was closed as withdrawn because there was no reply. You can continue by sending a message from this page.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Add more details")).toBeInTheDocument();
  });

  it("担当者のパネル: 知らせた後の行が「Withdrawn」で出る", async () => {
    const user = userEvent.setup();
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [
            makeItem({ read: true, language: "en", ...ASKER_REPLY_STATE }),
          ],
        },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          language: "en",
          messages: [],
          ...ASKER_REPLY_STATE,
          noReply: {
            endsAs: "WITHDRAWN",
            remindAt: REMIND_AT,
            remindedAt: REMINDED_AT,
            closesAt: CLOSES_AT,
          },
        }),
      },
    });
    open("/operator");

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    expect(
      await within(panel).findByText(
        "The requester was reminded on November 24. If there is no reply, this will be closed as “Withdrawn” on December 1.",
      ),
    ).toBeInTheDocument();
  });
});

describe("英語の引き継ぎと調査の状況", () => {
  it("本人のページ: 引き継いだ理由の1文が英語で出る", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          language: "en",
          handoverReason: "NO_ANSWER",
          messages: [],
        }),
      ),
    });
    open("/inquiries/inq-1");
    expect(
      await screen.findByText(
        "I looked into it but couldn't find a clear answer, so I've handed this over to the support team.",
      ),
    ).toBeInTheDocument();
    cleanup();

    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          language: "en",
          handoverReason: "TIMEOUT",
          messages: [],
        }),
      ),
    });
    open("/inquiries/inq-1");
    expect(
      await screen.findByText(
        "This is taking a while to look into, so I've handed it over to the support team. What I find will be passed on to them.",
      ),
    ).toBeInTheDocument();
    cleanup();

    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          language: "en",
          handoverReason: "ROUTE",
          messages: [],
        }),
      ),
    });
    open("/inquiries/inq-1");
    await screen.findByText("The support team is looking into it");
    expect(document.querySelector(".handover-reason")).toBeNull();
  });

  /** 英語の担当者の画面で、調査の状況と渡った理由を指定して、パネルを開く。 */
  async function openOperatorWith(detail: Parameters<typeof makeDetail>[0]) {
    const user = userEvent.setup();
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [makeItem({ read: true, language: "en" })],
        },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          language: "en",
          messages: [],
          ...detail,
        }),
      },
    });
    open("/operator");
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    return await screen.findByRole("region", { name: "Inquiry details" });
  }

  it.each([
    ["SENT_NOW", "The requester sent it without waiting for an answer"],
    ["UNRESOLVED", "The requester said it was not resolved"],
    ["BLOCKED", "Work is blocked"],
    ["ROUTE", "Staff decide on this type of inquiry"],
    ["NO_ANSWER", "AI could not answer"],
    ["TIMEOUT", "AI did not finish looking into it in time"],
    ["REOPENED", "The requester added to a closed inquiry"],
  ] as const)(
    "担当者のパネル: 渡った理由の行（%s）が英語で出る",
    async (reason, text) => {
      const panel = await openOperatorWith({ handoverReason: reason });
      expect(
        await within(panel).findByText(`Why it came to staff: ${text}`),
      ).toBeInTheDocument();
    },
  );

  it.each([
    [{ status: "NOT_RUN", audience: null }, "AI has not looked into this."],
    [
      { status: "RUNNING", audience: null },
      "AI is looking into this. The result will appear here when it finishes.",
    ],
    [
      { status: "DONE", audience: "ASKER" },
      "The requester has seen this result too.",
    ],
    [
      { status: "DONE", audience: "OPERATOR" },
      "The requester has not been shown this result.",
    ],
    [{ status: "FAILED", audience: null }, "AI's investigation failed."],
  ] as const)(
    "担当者のパネル: 調査の状況の行が英語で出る（%j）",
    async (inv, text) => {
      const panel = await openOperatorWith({
        investigation: {
          startedAt: null,
          finishedAt: null,
          unconfirmedQuestion: false,
          ...inv,
        },
      });
      expect(await within(panel).findByText(text)).toBeInTheDocument();
    },
  );

  it("担当者のパネル: 注記が英語で出る。調査をやり直すボタンは出ない", async () => {
    const panel = await openOperatorWith({
      investigation: {
        status: "NOT_RUN",
        audience: null,
        startedAt: null,
        finishedAt: null,
        unconfirmedQuestion: true,
      },
    });
    expect(
      await within(panel).findByText(
        "This is based on a restatement the requester has not confirmed.",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", {
        name: /Have AI look into it|Look into it again/,
      }),
    ).toBeNull();
  });
});

describe("英語の AI の案と返信の下書き", () => {
  const judgment = {
    text: "The behavior matches the documentation.",
    needsConsultation: true,
    consultationReason: "The spec is not documented",
    basedOn: ["A posting expires at its deadline."],
  };
  const procedure = {
    steps: [
      {
        target: "Job edit screen",
        action: "Change the deadline to tomorrow",
        check: "The posting appears in the list",
      },
    ],
    rollback: "Set the deadline back",
    basedOn: ["Expired postings are hidden."],
  };

  async function openAdvice(
    advice: object,
    extra: Parameters<typeof installFakeFetch>[0] = {},
  ) {
    const user = userEvent.setup();
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [makeItem({ read: true, language: "en" })],
        },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          language: "en",
          messages: [],
          advice: advice as never,
        }),
      },
      ...extra,
    });
    open("/operator");
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await within(panel).findByText("Original text");
    return { user, panel };
  }

  it("案の節の4通りが英語で出る", async () => {
    const running = await openAdvice({
      status: "RUNNING",
      judgment: null,
      procedure: null,
    });
    expect(
      within(running.panel).getByRole("heading", { name: /AI's suggestion/ }),
    ).toBeInTheDocument();
    expect(
      within(running.panel).getByText(
        "This is a suggestion AI drew from the facts above. The decision is yours.",
      ),
    ).toBeInTheDocument();
    expect(
      within(running.panel).getByText("AI is preparing a suggestion."),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const failed = await openAdvice({
      status: "FAILED",
      judgment: null,
      procedure: null,
    });
    expect(
      within(failed.panel).getByText("AI could not prepare a suggestion."),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const none = await openAdvice({
      status: "NOT_RUN",
      judgment: null,
      procedure: null,
    });
    expect(
      within(none.panel).queryByRole("heading", { name: /AI's suggestion/ }),
    ).not.toBeInTheDocument();
  });

  it("判断の案（相談が要りそう／要らなそう）と手順の案の項目が、英語で出る", async () => {
    const { panel } = await openAdvice({ status: "DONE", judgment, procedure });

    expect(
      within(panel).getByRole("heading", { name: "Suggested decision" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(/Consulting the spec owner looks necessary:/),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("The spec is not documented"),
    ).toBeInTheDocument();
    expect(within(panel).getAllByText("Based on")).toHaveLength(2);
    expect(
      within(panel).getByRole("heading", { name: "Suggested steps" }),
    ).toBeInTheDocument();
    const advice = within(panel)
      .getByRole("heading", { name: /AI's suggestion/ })
      .closest("section")!;
    for (const label of ["Target", "Action", "How to check"]) {
      expect(within(advice).getByText(label)).toBeInTheDocument();
    }
    expect(within(panel).getByText("How to undo")).toBeInTheDocument();
    expect(
      within(panel).getByText("Staff carry this out."),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    const calm = await openAdvice({
      status: "DONE",
      judgment: {
        ...judgment,
        needsConsultation: false,
        consultationReason: null,
      },
      procedure: null,
    });
    expect(
      within(calm.panel).getByText("Consultation does not look necessary."),
    ).toBeInTheDocument();
  });

  it("返信の下書きの欄が英語で出て、下書きが入ると英語の注意書きが出る", async () => {
    const { user, panel } = await openAdvice(
      { status: "NOT_RUN", judgment: null, procedure: null },
      {
        "POST /api/operator/inquiries/op-1/draft": {
          body: { body: "Let me check one thing.", language: "en" },
        },
      },
    );

    expect(
      within(panel).getByLabelText("What you want to say (optional)"),
    ).toBeInTheDocument();
    await user.click(
      within(panel).getByRole("button", { name: "Draft a question" }),
    );
    await waitFor(() =>
      expect(within(panel).getByLabelText("Send to the requester")).toHaveValue(
        "Let me check one thing.",
      ),
    );
    expect(
      within(panel).getByText(
        "This is an AI draft. Check and edit it before sending. It is written in the requester's language (English).",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Available when the box is empty"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "Draft a result" }),
    ).toBeDisabled();
  });
});

describe("英語の手順", () => {
  it("本人のページ: 見出し「Steps」と「Check: …」が英語で出る。手順の文章は書かれた言語のまま", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeStructuredView({
          ...RESULT_STATE,
          language: "ja",
          answer: {
            ...groundedAnswer,
            answered: true,
            steps: [
              { action: "求人管理画面で、対象の求人の編集を開く", check: null },
              {
                action: "掲載期限を変えて、保存する",
                check: "一覧に表示される",
              },
            ],
          },
        }),
      ),
    });
    open("/inquiries/inq-1");

    const heading = await screen.findByRole("heading", { name: "Steps" });
    const list = heading.nextElementSibling as HTMLElement;
    expect(list).toHaveAttribute("lang", "ja");
    expect(
      within(list).getByText("掲載期限を変えて、保存する"),
    ).toBeInTheDocument();
    expect(list.querySelectorAll(".step-check")).toHaveLength(1);
    expect(list.querySelector(".step-check")).toHaveTextContent(
      "Check: 一覧に表示される",
    );
  });
});

describe("書かれた内容の言語（lang 属性）", () => {
  it("本人の文章、言い直し、説明、AI が書いた事実には問い合わせの language、根拠の引用には ja が付く。画面の言語とは別", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeStructuredView({
          ...RESULT_STATE,
          language: "ja",
          utterance: "掲載期限のことです",
          answer: {
            ...groundedAnswer,
            memo: { ...groundedAnswer.memo, findings: ["延長上限は90日"] },
          },
        }),
      ),
    });
    open("/inquiries/inq-1");

    const utterance = await screen.findByText("掲載期限のことです");
    expect(utterance).toHaveAttribute("lang", "ja");
    expect(
      screen.getByText("求人の掲載期限を、何日まで延ばせるかを知りたい。"),
    ).toHaveAttribute("lang", "ja");
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toHaveAttribute("lang", "ja");
    expect(screen.getByText("延長上限は90日")).toHaveAttribute("lang", "ja");
    expect(screen.getByText("MAX_EXTENSION_DAYS = 90")).toHaveAttribute(
      "lang",
      "ja",
    );
    // 画面の固定の文言には付けない
    expect(screen.getByText("Here is what I understood.")).not.toHaveAttribute(
      "lang",
    );
  });

  it("英語の問い合わせでも、根拠の引用は ja。本人の追記には付け、担当者の発言には付けない", async () => {
    installFakeFetch({
      ...bilingualConfig(),
      ...inquiry(
        makeFiledView({
          ...RESULT_STATE,
          language: "en",
          sentImmediately: false,
          answer: groundedAnswer,
          messages: [
            makeMessage({ id: "m1", kind: "RESULT", body: "Fixed it." }),
            makeMessage({
              id: "m2",
              from: "ASKER",
              kind: "NOTE",
              body: "Thank you.",
            }),
          ],
        }),
      ),
    });
    open("/inquiries/inq-1");

    expect(await screen.findByText("Thank you.")).toHaveAttribute("lang", "en");
    expect(screen.getByText("Fixed it.")).not.toHaveAttribute("lang");
    expect(screen.getByText("MAX_EXTENSION_DAYS = 90")).toHaveAttribute(
      "lang",
      "ja",
    );
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toHaveAttribute("lang", "en");
  });
});

describe("英語の担当者の画面", () => {
  /** 英語の担当者の画面を開く。 */
  function openOperator(detailOverrides = {}, itemOverrides = {}) {
    storedEnglish();
    const item = makeItem({ read: true, language: "en", ...itemOverrides });
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: { unreadCount: 0, items: [item] },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({
          read: true,
          language: "en",
          messages: [],
          ...detailOverrides,
        }),
      },
    });
    return open("/operator");
  }

  it("ボードの列の名前、カードの区間・待っていること・終わり方が英語で出る", async () => {
    storedEnglish();
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 1,
          items: [
            makeItem({
              id: "a",
              summary: "Mine.",
              phase: "DECIDE",
              language: "en",
            }),
            makeItem({
              id: "b",
              summary: "Waiting.",
              read: true,
              ...ASKER_REPLY_STATE,
              language: "en",
            }),
            makeItem({
              id: "c",
              summary: "Check.",
              read: true,
              ...RESULT_STATE,
              language: "en",
            }),
            makeItem({
              id: "d",
              summary: "Done.",
              read: true,
              ...closedState("DECLINED"),
              language: "en",
            }),
          ],
        },
      },
    });
    open("/operator");

    await screen.findByText("Mine.");
    const board = screen.getByRole("region", { name: "Inquiry board" });
    expect(
      within(board)
        .getAllByRole("heading", { level: 3 })
        .map((h) => h.textContent),
    ).toEqual([
      "Staff's turn",
      "In consultation",
      "Requester's turn",
      "Closed",
    ]);
    const card = (text: string) =>
      screen.getByText(text).closest("button") as HTMLElement;
    expect(card("Mine.")).toHaveTextContent("Stage Decide");
    expect(card("Mine.")).toHaveTextContent("Unread");
    expect(card("Waiting.")).toHaveTextContent("Waiting for a reply");
    expect(card("Check.")).toHaveTextContent("Waiting for result check");
    expect(card("Done.")).toHaveTextContent("Declined");
    expect(screen.getByLabelText("1 unread")).toBeInTheDocument();
    expect(
      screen.getByRole("checkbox", { name: "Unread only" }),
    ).toBeInTheDocument();
    expect(within(board).getAllByRole("button").length).toBe(4);
  });

  it("パネルの操作が英語で出る（Ask the requester、Send the result、Decline、Reclassify、Status history）", async () => {
    const user = userEvent.setup();
    openOperator();

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await within(panel).findByText("Original text");

    expect(
      within(panel).getByText("Staff's turn (Staff is handling it)"),
    ).toBeInTheDocument();
    const stages = within(panel).getByRole("list", { name: "Stage" });
    expect(
      within(stages)
        .getAllByRole("listitem")
        .map((li) => li.textContent),
    ).toEqual(["Understand", "Decide", "Carry out"]);
    expect(
      within(stages).getByText("Decide", { selector: "li" }),
    ).toHaveAttribute("aria-current", "step");
    expect(
      within(panel).getByLabelText("Send to the requester"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "Ask the requester" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "Send the result" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Decline", { selector: "summary" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Reclassify", { selector: "summary" }),
    ).toBeInTheDocument();
    expect(within(panel).getByText("Status history")).toBeInTheDocument();
    expect(
      within(panel).getByText(
        "Ask the requester: waits for the requester's reply",
      ),
    ).toBeInTheDocument();

    await user.click(
      within(panel).getByText("Decline", { selector: "summary" }),
    );
    const kind = within(panel).getByLabelText("Reason type");
    expect(
      within(kind)
        .getAllByRole("option")
        .map((o) => o.textContent),
    ).toEqual([
      "Cannot reproduce",
      "Known limitation",
      "Out of scope",
      "Duplicate",
      "Other",
    ]);
    expect(
      within(panel).getByRole("button", { name: "Close as declined" }),
    ).toBeInTheDocument();
  });

  it("状態の履歴と置き直しの前後が、英語の言葉で出る", async () => {
    const user = userEvent.setup();
    openOperator({
      history: [
        {
          at: new Date(2026, 10, 21, 13, 5).toISOString(),
          actor: "OPERATOR",
          event: "PLACEMENT_CHANGED",
          phase: "DECIDE",
          turn: "OPERATOR",
          awaiting: "HANDLING",
          ending: null,
          detail: {
            before: { type: "Looks wrong", system: "System A", function: null },
            after: {
              type: "Do it for me",
              system: "System A",
              function: "Function A-1",
            },
          },
        },
      ],
    });

    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await user.click(await within(panel).findByText("Status history"));
    const row = within(panel).getByText("Reclassified").closest("li")!;
    expect(row).toHaveTextContent("Staff");
    expect(row).toHaveTextContent("11/21, 13:05");
    expect(row).toHaveTextContent(
      "Looks wrong, System A → Do it for me, System A, Function A-1",
    );
    expect(row).toHaveTextContent("Staff's turn (Staff is handling it)");
  });

  it("本人の言語が画面の言語と違うときだけ、「本人の言語」を出す", async () => {
    const user = userEvent.setup();
    openOperator({ language: "ja" }, { language: "ja" });
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    expect(
      await within(panel).findByText("Requester's language: 日本語"),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();
    window.localStorage.clear();

    // 日本語の画面で、本人の言語が英語
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [makeItem({ read: true, language: "en" })],
        },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({ read: true, language: "en", messages: [] }),
      },
    });
    open("/operator");
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const japanesePanel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    expect(
      await within(japanesePanel).findByText("本人の言語: English"),
    ).toBeInTheDocument();
    cleanup();
    vi.unstubAllGlobals();

    // 同じ言語なら出さない
    installFakeFetch({
      ...bilingualConfig(),
      "GET /api/operator/inquiries": {
        body: { unreadCount: 0, items: [makeItem({ read: true })] },
      },
      "GET /api/operator/inquiries/op-1": {
        body: makeDetail({ read: true, messages: [] }),
      },
    });
    open("/operator");
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const samePanel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    await within(samePanel).findByText("元の発話");
    expect(within(samePanel).queryByText(/本人の言語/)).not.toBeInTheDocument();
  });

  it("本人の文章と AI が書いたものには本人の言語の lang、根拠の引用には ja、担当者の発言には付けない", async () => {
    const user = userEvent.setup();
    openOperator({
      language: "en",
      utterance: "Please extend the deadline.",
      messages: [
        makeMessage({ id: "m1", body: "Checking." }),
        makeMessage({
          id: "m2",
          from: "ASKER",
          kind: "NOTE",
          body: "Thanks.",
        }),
      ],
    });
    await user.click(
      await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });

    expect(
      await within(panel).findByText("Please extend the deadline."),
    ).toHaveAttribute("lang", "en");
    expect(
      within(panel).getByText("掲載期限を過ぎると一覧から外れます。"),
    ).toHaveAttribute("lang", "en");
    expect(within(panel).getByText("if (expired) hide()")).toHaveAttribute(
      "lang",
      "ja",
    );
    expect(within(panel).getByText("Thanks.")).toHaveAttribute("lang", "en");
    expect(within(panel).getByText("Checking.")).not.toHaveAttribute("lang");
  });

  it("一覧のカードの言い直しには、本人の言語の lang が付く", async () => {
    openOperator({}, { language: "en" });
    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    expect(card.querySelector(".op-summary")).toHaveAttribute("lang", "en");
  });
});

describe("英語の開発者向けの部品", () => {
  it("モックの案内枠が英語で出る。タグは識別子のまま、例文は英語の語で受付に届く", async () => {
    const user = userEvent.setup();
    storedEnglish();
    installFakeFetch({
      "GET /api/config": (_b, headers) => ({
        body: {
          ...(headers["accept-language"] === "en" ? EN_CONFIG : makeConfig()),
          mock: true,
          skills: { reception: "bedrock", investigator: "mock" },
        },
      }),
    });
    open("/");

    expect(await screen.findByText("Partly mock")).toBeInTheDocument();
    await user.click(screen.getByText("Try example texts"));
    const bar = screen.getByRole("region", { name: "Mock scenarios" });
    expect(
      within(bar)
        .getByRole("group", { name: "Example situations" })
        .querySelectorAll("button"),
    ).toHaveLength(5);
    expect(
      within(bar).getByRole("button", { name: "[言い直しエラー]" }),
    ).toBeInTheDocument();
    expect(
      within(bar).getByText("Tags only affect the skills that run as mocks"),
    ).toBeInTheDocument();
    expect(
      within(bar).getByText(
        "Whatever you ask, the result is about the job posting deadline",
      ),
    ).toBeInTheDocument();

    await user.click(within(bar).getByRole("button", { name: "Looks wrong" }));
    expect(
      screen.getByLabelText(/It is fine if it is not well organized/),
    ).toHaveValue(
      "The applicant count in System A does not match, so it may be a bug.",
    );
  });

  it("「裏側を見る」が英語で出る", async () => {
    const user = userEvent.setup();
    storedEnglish();
    installFakeFetch({
      "GET /api/config": {
        body: makeConfig({ trace: true }),
      },
      ...inquiry(
        makeFiledView({
          messages: [],
          trace: [
            makeTraceEntry({ skill: "restater", failed: false }),
            makeTraceEntry({ skill: "restater", failed: true }),
          ],
        }),
      ),
    });
    open("/inquiries/inq-1");

    await user.click(
      await screen.findByText("Behind the scenes (for developers)"),
    );
    const stages = screen.getAllByRole("listitem", { name: "" });
    const restater = stages.find((s) => s.textContent?.includes("Restater"))!;
    expect(restater).toHaveTextContent("AI skill");
    expect(restater).toHaveTextContent("2 calls (total)");
    expect(restater).toHaveTextContent("Failed 1");
    expect(restater).toHaveTextContent("Mock");
    expect(restater).toHaveTextContent("Input 5,326 / Output 394 tokens");
    const verifier = stages.find((s) =>
      s.textContent?.includes("Verification (code)"),
    )!;
    expect(verifier).toHaveTextContent("Plain code");
    expect(verifier).toHaveTextContent("Not run yet");
  });
});
