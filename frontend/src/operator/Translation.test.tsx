import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import type { Translation } from "../api/types.ts";
import { LANGUAGE_STORAGE_KEY } from "../i18n/language.ts";
import {
  FakeOperatorStream,
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

const TRANSLATE_PATH = "/api/operator/inquiries/op-1/translation";

/** 英語で書かれた本人の問い合わせ（日本語の画面で見る）。 */
const englishDetail = () =>
  makeDetail({
    read: true,
    language: "en",
    utterance: "The job posting disappeared.",
    structured: {
      summary: "The user wants to know why the posting disappeared.",
      targetSystem: "Job admin",
      goal: "Know the reason",
      whatHappened: null,
      expectedResult: null,
    },
    answer: {
      grounded: true,
      askerExplanation: "A posting is hidden after its deadline.",
      steps: [],
      memo: { findings: [], notFound: [], nextChecks: [] },
      citations: [],
    },
    messages: [
      makeMessage({
        id: "m-asker",
        from: "ASKER",
        kind: "NOTE",
        authorName: "本人",
        body: "Since yesterday.",
      }),
      makeMessage({
        id: "m-operator",
        from: "OPERATOR",
        kind: "ASK",
        body: "いつからですか。",
      }),
    ],
  });

/** 日本語への訳（本人の発言、説明、言い直し、発話。画面にない key を1つ含む）。 */
const japaneseTranslation = (overrides: Partial<Translation> = {}) => ({
  from: "en",
  to: "ja",
  items: [
    {
      key: "utterance",
      original: "The job posting disappeared.",
      translated: "求人の掲載が消えました。",
    },
    {
      key: "structured.summary",
      original: "The user wants to know why the posting disappeared.",
      translated: "掲載が消えた理由を知りたい。",
    },
    {
      key: "answer.askerExplanation",
      original: "A posting is hidden after its deadline.",
      translated: "掲載は期限の後に非表示になります。",
    },
    {
      key: "message:m-asker",
      original: "Since yesterday.",
      translated: "昨日からです。",
    },
    { key: "message:gone", original: "x", translated: "画面にない訳" },
  ],
  ...overrides,
});

/** 担当者の画面を、英語の本人の件を開いた状態にして返す。 */
async function openEnglishAsker(
  translate:
    RouteResult | ((b: unknown, h: Record<string, string>) => RouteResult) = {
    body: japaneseTranslation(),
  },
  options: {
    screen?: "ja" | "en";
    detail?: ReturnType<typeof makeDetail>;
  } = {},
) {
  if (options.screen === "en") {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
  }
  const detail = options.detail ?? englishDetail();
  const stream = new FakeOperatorStream();
  const calls = installFakeFetch({
    "GET /api/config": (_b, headers) => ({
      body: makeConfig({
        trace: true,
        languages:
          headers["accept-language"] === "en" ? ["en", "ja"] : ["ja", "en"],
      }),
    }),
    "GET /api/operator/inquiries": {
      body: {
        unreadCount: 0,
        items: [makeItem({ read: true, language: detail.language })],
      },
    },
    "GET /api/operator/inquiries/op-1": () => ({ body: detail }),
    [`POST ${TRANSLATE_PATH}`]: translate,
  });
  render(<Root subscribeOperator={stream.subscribe} />);
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
  );
  const panel = await screen.findByRole("region", {
    name: options.screen === "en" ? "Inquiry details" : "問い合わせの詳細",
  });
  await within(panel).findByText(detail.utterance!);
  return { calls, stream, user, panel };
}

const translateCalls = (calls: RecordedCall[]) =>
  calls.filter((c) => c.method === "POST" && c.path === TRANSLATE_PATH);

describe("担当者のパネル: 訳を見る", () => {
  it("本人の言語が画面の言語と違うときだけ、「本人の言語」の行にボタンが出る", async () => {
    const { panel } = await openEnglishAsker();
    const row = within(panel).getByText(/本人の言語: English/);
    expect(within(row).getByRole("button", { name: "訳を見る" })).toBeEnabled();
    cleanup();
    vi.unstubAllGlobals();

    // 同じ言語（日本語）なら、行もボタンも出さない
    const same = await openEnglishAsker(
      { body: japaneseTranslation() },
      { detail: makeDetail({ read: true, messages: [] }) },
    );
    expect(
      within(same.panel).queryByRole("button", { name: "訳を見る" }),
    ).not.toBeInTheDocument();
    expect(translateCalls(same.calls)).toHaveLength(0);
  });

  it("押すと、本文なしで POST し、各文章のすぐ下に訳を添える。原文は残り、画面にない key は無視する", async () => {
    const { calls, user, panel } = await openEnglishAsker();

    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));

    expect(
      await within(panel).findByText("求人の掲載が消えました。"),
    ).toBeInTheDocument();
    expect(translateCalls(calls)).toHaveLength(1);
    expect(translateCalls(calls)[0].body).toBeUndefined();
    expect(translateCalls(calls)[0].headers["accept-language"]).toBe("ja");

    const pairs: [string, string][] = [
      ["The job posting disappeared.", "求人の掲載が消えました。"],
      [
        "The user wants to know why the posting disappeared.",
        "掲載が消えた理由を知りたい。",
      ],
      [
        "A posting is hidden after its deadline.",
        "掲載は期限の後に非表示になります。",
      ],
      ["Since yesterday.", "昨日からです。"],
    ];
    for (const [original, translated] of pairs) {
      const originalNode = within(panel).getByText(original, {
        selector: "p",
      });
      const note = originalNode.nextElementSibling as HTMLElement;
      expect(note).toHaveClass("op-translation");
      expect(within(note).getByText("訳（AI）")).toBeInTheDocument();
      const body = within(note).getByText(translated);
      expect(body).toHaveAttribute("lang", "ja");
    }
    expect(within(panel).getAllByText("訳（AI）")).toHaveLength(4);
    expect(within(panel).queryByText("画面にない訳")).not.toBeInTheDocument();
    // 担当者の発言には、訳を添えない
    expect(
      within(panel).getByText("いつからですか。").nextElementSibling,
    ).toBeNull();
  });

  it("訳を出している間、ボタンは「訳を隠す」になり、押すと訳を隠して原文だけに戻る。もう一度押すと取り直す", async () => {
    const { calls, user, panel } = await openEnglishAsker();
    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));
    await within(panel).findByText("求人の掲載が消えました。");

    await user.click(within(panel).getByRole("button", { name: "訳を隠す" }));

    expect(within(panel).queryByText("訳（AI）")).not.toBeInTheDocument();
    expect(
      within(panel).queryByText("求人の掲載が消えました。"),
    ).not.toBeInTheDocument();
    expect(
      within(panel).getByText("The job posting disappeared."),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "訳を見る" }),
    ).toBeInTheDocument();
    // 隠すだけで、API は呼ばない
    expect(translateCalls(calls)).toHaveLength(1);

    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));
    await within(panel).findByText("求人の掲載が消えました。");
    expect(translateCalls(calls)).toHaveLength(2);
  });

  it("訳を取っている間は、ボタンを押せない（二重押しで2回呼ばない）", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const { calls, user, panel } = await openEnglishAsker(() => ({
      body: japaneseTranslation(),
    }));
    // 応答を保留するため、fetch を包む
    const base = globalThis.fetch;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (String(input) === TRANSLATE_PATH) await gate;
        return base(input, init);
      }),
    );

    const button = within(panel).getByRole("button", { name: "訳を見る" });
    await user.click(button);
    expect(button).toBeDisabled();
    await user.click(button);
    release();

    await within(panel).findByText("求人の掲載が消えました。");
    expect(translateCalls(calls)).toHaveLength(1);
    expect(
      within(panel).getByRole("button", { name: "訳を隠す" }),
    ).toBeEnabled();
  });

  it("訳を出している間に updated が届いたら、訳を取り直す。出していなければ取らない", async () => {
    const { calls, stream, user, panel } = await openEnglishAsker();

    // 出していない間は、取らない
    act(() =>
      stream.latest.handlers.onUpdated(
        makeItem({ read: true, language: "en" }),
      ),
    );
    await waitFor(() =>
      expect(
        calls.filter(
          (c) =>
            c.method === "GET" && c.path === "/api/operator/inquiries/op-1",
        ),
      ).toHaveLength(2),
    );
    expect(translateCalls(calls)).toHaveLength(0);

    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));
    await within(panel).findByText("求人の掲載が消えました。");
    expect(translateCalls(calls)).toHaveLength(1);

    act(() =>
      stream.latest.handlers.onUpdated(
        makeItem({ read: true, language: "en" }),
      ),
    );

    await waitFor(() => expect(translateCalls(calls)).toHaveLength(2));
    expect(
      within(panel).getByText("求人の掲載が消えました。"),
    ).toBeInTheDocument();
  });

  it("画面の言語を切り替えたら訳を隠す。別の件を開いても隠す", async () => {
    const { calls, user, panel } = await openEnglishAsker();
    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));
    await within(panel).findByText("求人の掲載が消えました。");

    await user.click(screen.getByRole("button", { name: "English" }));

    const english = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await within(english).findByText("The job posting disappeared.");
    expect(
      screen.queryByText("求人の掲載が消えました。"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Translation (AI)")).not.toBeInTheDocument();
    // 切り替えのあとに、勝手に取り直さない
    expect(translateCalls(calls)).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "日本語" }));
    const japanese = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    await within(japanese).findByText("The job posting disappeared.");
    expect(within(japanese).queryByText("訳（AI）")).not.toBeInTheDocument();
    expect(
      within(japanese).getByRole("button", { name: "訳を見る" }),
    ).toBeInTheDocument();
  });

  it("失敗したら、既存のエラーを出し、原文は見えたまま。もう一度押せる", async () => {
    let fail = true;
    const { calls, user, panel } = await openEnglishAsker(() =>
      fail
        ? {
            status: 502,
            body: { code: "AI_UNAVAILABLE", message: "訳せませんでした" },
          }
        : { body: japaneseTranslation() },
    );

    await user.click(within(panel).getByRole("button", { name: "訳を見る" }));

    const alert = await within(panel).findByRole("alert");
    expect(alert).toHaveTextContent("うまく処理できませんでした");
    expect(within(panel).queryByText("訳（AI）")).not.toBeInTheDocument();
    expect(
      within(panel).getByText("The job posting disappeared."),
    ).toBeInTheDocument();
    const button = within(panel).getByRole("button", { name: "訳を見る" });
    expect(button).toBeEnabled();

    fail = false;
    await user.click(button);
    await within(panel).findByText("求人の掲載が消えました。");
    expect(within(panel).queryByRole("alert")).not.toBeInTheDocument();
    expect(translateCalls(calls)).toHaveLength(2);
  });

  it("英語の画面では、文言が英語になり、訳の lang は訳した先の言語になる", async () => {
    const { user, panel } = await openEnglishAsker(
      {
        body: {
          from: "ja",
          to: "en",
          items: [
            {
              key: "utterance",
              original: "昨日まで見えていた求人が今日は見えません",
              translated: "[Sample translation] The posting is gone.",
            },
          ],
        },
      },
      {
        screen: "en",
        detail: makeDetail({ read: true, language: "ja", messages: [] }),
      },
    );
    expect(
      within(panel).getByText(/Requester's language: 日本語/),
    ).toBeInTheDocument();

    await user.click(
      within(panel).getByRole("button", { name: "Show translation" }),
    );

    const body = await within(panel).findByText(
      "[Sample translation] The posting is gone.",
    );
    expect(body).toHaveAttribute("lang", "en");
    expect(within(panel).getByText("Translation (AI)")).toBeInTheDocument();
    expect(
      within(panel).getByText("昨日まで見えていた求人が今日は見えません"),
    ).toBeInTheDocument();
    await user.click(
      within(panel).getByRole("button", { name: "Hide translation" }),
    );
    expect(
      within(panel).getByRole("button", { name: "Show translation" }),
    ).toBeInTheDocument();
  });

  it("実行記録は、下書きの後に「訳」の段を出し、記録があれば動いたものとして示す", async () => {
    const detail = englishDetail();
    detail.trace = [
      makeTraceEntry({ skill: "drafter", provider: "mock", model: null }),
      makeTraceEntry({ skill: "translator", provider: "mock", model: null }),
    ];
    const { user, panel } = await openEnglishAsker(undefined, { detail });

    await user.click(within(panel).getByText(/裏側を見る/));
    const stages = within(panel)
      .getAllByRole("listitem")
      .map((li) => li.textContent ?? "");
    const drafter = stages.findIndex((t) => t.startsWith("下書き"));
    const translator = stages.findIndex((t) => t.startsWith("訳"));
    expect(drafter).toBeGreaterThanOrEqual(0);
    expect(translator).toBe(drafter + 1);
    expect(stages[translator]).toContain("モック");
    expect(stages[translator]).not.toContain("まだ動いていません");
  });
});
