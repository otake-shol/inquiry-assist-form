import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import type { InquiryView, IntakeEntry } from "./api/types.ts";
import {
  FakeStream,
  TYPE_OPTIONS,
  groundedAnswer,
  installFakeFetch,
  makeConfig,
  makeFiledView,
  makeQuestion,
  makeStructuredView,
  makeTypeEntry,
  makeView,
  ungroundedAnswer,
  type RecordedCall,
  type RouteResult,
  CONFIRMATION_STATE,
  HANDED_OVER_AT,
  HANDLING_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  closedState,
} from "./test/fakes.ts";

type User = ReturnType<typeof userEvent.setup>;

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";
const FORBIDDEN = /0次回答|種別|種類|判定|整形|起票|出典|スキル|モデル/;
const SUMMARY = "求人の掲載期限を、何日まで延ばせるかを知りたい。";
const SEND_NOW = "答えを待たずに担当者に送る";

/** 詳しい質問（選択肢つき）。 */
const Q_SINCE = makeQuestion({
  questionId: "since",
  kind: "DETAIL",
  text: "いつから起きていますか",
  options: [
    { id: "today", label: "今日から" },
    { id: "this-week", label: "今週から" },
  ],
});
/** 詳しい質問（文章で答える）。 */
const Q_TARGET = makeQuestion({
  questionId: "target",
  kind: "DETAIL",
  text: "対象の取引先はどこですか",
  options: [],
});
/** 補足（書かずに進める）。 */
const Q_NOTE = makeQuestion({
  questionId: "note",
  kind: "NOTE",
  text: "補足があれば書いてください",
  options: [],
  canSkip: true,
});

const LOOKS_WRONG = {
  id: "LOOKS_WRONG",
  label: "おかしいかもしれない",
  by: "ASKER",
} as const;
const DO_FOR_ME = {
  id: "DO_FOR_ME",
  label: "やってほしい",
  by: "ASKER",
} as const;

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
});

/** 指定の呼び出しを探す。 */
function findCall(calls: RecordedCall[], method: string, path: string) {
  return calls.find((c) => c.method === method && c.path === path);
}

/** 指定のパスへの呼び出しを数える。 */
function countCalls(calls: RecordedCall[], method: string, path: string) {
  return calls.filter((c) => c.method === method && c.path === path).length;
}

/** 画面に内部用語が出ていないことを確かめる。 */
function expectNoInternalWords() {
  expect(document.body.textContent).not.toMatch(FORBIDDEN);
}

/** 答え終えた質問の1行を取り出す。 */
function entryRow(question: string) {
  return screen.getByText(question).closest(".answered-row")!;
}

/**
 * `/inquiries/inq-1` を開き、指定の問い合わせから再開した状態で描く。
 * @param view 再開する問い合わせ
 * @param routes ほかの API の応答
 * @param stream 進捗ストリームの偽物
 */
function openInquiry(
  view: InquiryView,
  routes: Record<string, RouteResult | ((body: unknown) => RouteResult)> = {},
  stream = new FakeStream(),
) {
  window.history.replaceState({}, "", `/inquiries/${view.id}`);
  const calls = installFakeFetch({
    [`GET /api/inquiries/${view.id}`]: { body: view },
    ...routes,
  });
  render(<App subscribe={stream.subscribe} />);
  return { calls, stream };
}

/** 入口の質問に答えた後の問い合わせ。 */
function intakeView(
  next: InquiryView["intake"]["next"],
  entries: IntakeEntry[] = [makeTypeEntry()],
  overrides: Partial<InquiryView> = {},
) {
  return makeView({
    intake: { entries, next, complete: next === null },
    ...overrides,
  });
}

/** 1問目で種類の選択肢を押す。 */
async function pickType(user: User, name = "仕組みを知りたい") {
  await user.click(await screen.findByRole("button", { name }));
}

/** 入口を終えて言い直しが出ている問い合わせから再開する。 */
function openRestatement(
  overrides: Partial<InquiryView> = {},
  routes: Record<string, RouteResult | ((body: unknown) => RouteResult)> = {},
  stream = new FakeStream(),
) {
  return openInquiry(makeStructuredView(overrides), routes, stream);
}

describe("最初の画面（入口の1問目）", () => {
  it("自由記述の欄ではなく、1問目と5つの種類の選択肢（名前と説明）、緊急度、「わからない」、「文章で書く」を出す。この時点では問い合わせを作らない", async () => {
    const calls = installFakeFetch({});
    render(<App subscribe={new FakeStream().subscribe} />);

    const group = await screen.findByRole("group", {
      name: "何をしたいですか",
    });
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    const buttons = within(group).getAllByRole("button");
    expect(
      buttons.map((b) => b.getAttribute("aria-labelledby") !== null),
    ).toEqual([true, true, true, true, true, false]);
    // 名前は選択肢の名前だけ。短い説明は説明として結びつく（名前と説明を縦に並べる）
    for (const type of TYPE_OPTIONS) {
      const button = within(group).getByRole("button", { name: type.label });
      expect(button).toHaveAccessibleDescription(type.hint);
      expect(button).toHaveTextContent(type.hint);
      expect(button.querySelector(".choice-name")).toHaveTextContent(
        type.label,
      );
    }
    // 「わからない」は選択肢の並びの最後。種類の選択肢は聞く順のまま
    expect(buttons.at(-1)).toHaveTextContent("わからない");
    expect(
      buttons
        .slice(0, 5)
        .map((b) => b.querySelector(".choice-name")?.textContent),
    ).toEqual(TYPE_OPTIONS.map((t) => t.label));
    expect(
      screen.getByRole("button", { name: "文章で書く" }),
    ).toBeInTheDocument();
    const urgency = screen.getByRole("group", { name: "急ぎですか" });
    expect(within(urgency).getAllByRole("radio")).toHaveLength(3);
    expect(within(urgency).getByRole("radio", { name: "通常" })).toBeChecked();
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();
  });

  it("押した時点で問い合わせを作り、その答えを送って次の質問を出す。URL は /inquiries/{id} に変わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(
          makeQuestion(),
          [makeTypeEntry("LOOKS_WRONG", "おかしいかもしれない")],
          { type: LOOKS_WRONG },
        ),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await pickType(user, "おかしいかもしれない");

    expect(await screen.findByText("どのシステムですか")).toBeInTheDocument();
    expect(findCall(calls, "POST", "/api/inquiries")?.body).toEqual({});
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "type", optionId: "LOOKS_WRONG" });
    // 作成してから答えを送る
    expect(
      calls.findIndex(
        (c) => c.path === "/api/inquiries" && c.method === "POST",
      ),
    ).toBeLessThan(
      calls.findIndex((c) => c.path === "/api/inquiries/inq-1/intake/answers"),
    );
    await waitFor(() =>
      expect(window.location.pathname).toBe("/inquiries/inq-1"),
    );
    // 取り直さずに、そのまま続く
    expect(countCalls(calls, "GET", "/api/inquiries/inq-1")).toBe(0);
    // 答えた質問は「質問 → 答え」の1行になり、1問目は消える
    expect(entryRow("何をしたいですか")).toHaveTextContent(
      "何をしたいですか→おかしいかもしれない",
    );
    expect(
      screen.queryByRole("group", { name: "何をしたいですか" }),
    ).not.toBeInTheDocument();
    // 新しい質問にフォーカスが移る
    await waitFor(() =>
      expect(screen.getByText("どのシステムですか")).toHaveFocus(),
    );
    expectNoInternalWords();
  });

  it("1問目の「わからない」も、作成してから unknown を送る", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(makeQuestion(), [
          {
            ...makeTypeEntry(),
            answer: { kind: "UNKNOWN" },
          },
        ]),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await user.click(await screen.findByRole("button", { name: "わからない" }));

    expect(await screen.findByText("どのシステムですか")).toBeInTheDocument();
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "type", unknown: true });
    expect(entryRow("何をしたいですか")).toHaveTextContent("わからない");
  });

  it("作成の応答が1問目でなくても、返った intake.next の質問 id で答えを送る", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          intake: {
            entries: [],
            next: makeQuestion({
              questionId: "type",
              kind: "TYPE",
              text: "何をしたいですか",
              options: TYPE_OPTIONS.map(({ id, label }) => ({ id, label })),
            }),
            complete: false,
          },
        }),
      },
      "POST /api/inquiries/inq-1/intake/answers": { body: makeView() },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await pickType(user, "使い方を知りたい");

    await waitFor(() =>
      expect(
        findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
      ).toEqual({ questionId: "type", optionId: "HOW_TO" }),
    );
  });

  it("作成の途中で通信中は、選択肢と緊急度を押せず二重に送れない", async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    installFakeFetch({});
    const fake = vi.mocked(fetch);
    const base = fake.getMockImplementation()!;
    fake.mockImplementation(async (input, init) => {
      if (String(input) === "/api/inquiries" && init?.method === "POST") {
        await gate;
        return new Response(JSON.stringify(makeView({ id: "inq-1" })), {
          status: 201,
        });
      }
      return base(input, init);
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await pickType(user);

    const group = screen.getByRole("group", { name: "何をしたいですか" });
    for (const button of within(group).getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
    expect(screen.getByRole("radio", { name: "急ぎ" })).toBeDisabled();
    expect(screen.getByText("少しお待ちください。")).toBeInTheDocument();
    release();
    await waitFor(() =>
      expect(
        screen.getByRole("group", { name: "何をしたいですか" }),
      ).toBeInTheDocument(),
    );
  });

  it("設定を取得できなくても壊れず、選択肢は出さずに「文章で書く」を出す", async () => {
    installFakeFetch({
      "GET /api/config": { status: 500, body: { code: "X", message: "x" } },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(
      await screen.findByRole("button", { name: "文章で書く" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("group", { name: "何をしたいですか" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("モック")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("group", { name: "急ぎですか" }),
    ).not.toBeInTheDocument();
  });
});

describe("質問の描き方", () => {
  it("選択肢つきの質問は、質問文とひも付いたボタンの並びで、最後に「わからない」がある。押すと optionId が送られる", async () => {
    const user = userEvent.setup();
    const next = intakeView(null, [makeTypeEntry()], {
      ...CONFIRMATION_STATE,
    });
    const { calls } = openInquiry(intakeView(Q_SINCE), {
      "POST /api/inquiries/inq-1/intake/answers": { body: next },
    });

    const group = await screen.findByRole("group", {
      name: "いつから起きていますか",
    });
    const buttons = within(group).getAllByRole("button");
    expect(buttons.map((b) => b.textContent)).toEqual([
      "今日から",
      "今週から",
      "わからない",
    ]);
    // 説明の無い選択肢は、ボタンを縦に積まない
    expect(group).not.toHaveClass("stacked");
    expect(screen.getByRole("button", { name: "わからない" })).toHaveClass(
      "quiet",
    );

    await user.click(within(group).getByRole("button", { name: "今週から" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "since", optionId: "this-week" });
  });

  it("補足以外の「わからない」は unknown を送る", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_SINCE), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(Q_TARGET, [makeTypeEntry()]),
      },
    });

    await user.click(await screen.findByRole("button", { name: "わからない" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "since", unknown: true });
    // 次の文章で答える質問にも「わからない」がある
    expect(
      await screen.findByRole("button", { name: "わからない" }),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("対象の取引先はどこですか"),
    ).toBeInTheDocument();
  });

  it("文章で答える質問は、入力欄と「次へ」「わからない」。空では送れず、前後の空白を除いて text を送る", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_TARGET), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(Q_NOTE, [makeTypeEntry()]),
      },
    });

    const box = await screen.findByLabelText("対象の取引先はどこですか");
    expect(box.tagName).toBe("TEXTAREA");
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
    await user.type(box, "   ");
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
    await user.type(box, "株式会社サンプル ");
    // 補足ではないので、「書かずに進む」は無い
    expect(
      screen.queryByRole("button", { name: "書かずに進む" }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "次へ" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "target", text: "株式会社サンプル" });
  });

  it("文章で答える質問の「わからない」も unknown を送る", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_TARGET), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(Q_NOTE, [makeTypeEntry()]),
      },
    });

    await user.click(await screen.findByRole("button", { name: "わからない" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "target", unknown: true });
  });

  it("補足を書かずに進められる場合は「次へ」と「書かずに進む」があり、「わからない」と「文章で書く」は出ない", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_NOTE), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(null, [makeTypeEntry()], { ...CONFIRMATION_STATE }),
      },
    });

    expect(
      await screen.findByLabelText("補足があれば書いてください"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "わからない" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "文章で書く" }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "書かずに進む" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "note", skipped: true });
  });

  it("補足に書いて「次へ」を押すと text が送られる", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_NOTE), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(null, [makeTypeEntry()], { ...CONFIRMATION_STATE }),
      },
    });

    await user.type(
      await screen.findByLabelText("補足があれば書いてください"),
      "先週から続いています",
    );
    await user.click(screen.getByRole("button", { name: "次へ" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "note", text: "先週から続いています" });
  });

  it("書かずには進めない補足（canSkip が偽）は、「書かずに進む」を出さず、API が返した文言を出す", async () => {
    openInquiry(
      intakeView(
        makeQuestion({
          questionId: "note",
          kind: "NOTE",
          text: "状況を一言で教えてください",
          options: [],
          canSkip: false,
        }),
      ),
    );

    expect(
      await screen.findByLabelText("状況を一言で教えてください"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "書かずに進む" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "わからない" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
    expect(
      screen.queryByText("補足があれば書いてください"),
    ).not.toBeInTheDocument();
  });

  it("選択肢のほかに文章でも答えられる質問（allowsText）は、文章の欄も出して text を送る", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(
      intakeView(makeQuestion({ ...Q_SINCE, allowsText: true })),
      {
        "POST /api/inquiries/inq-1/intake/answers": {
          body: intakeView(Q_NOTE, [makeTypeEntry()]),
        },
      },
    );

    await user.type(
      await screen.findByLabelText("選択肢にない場合は、文章で答えられます"),
      "昨日の夕方",
    );
    await user.click(screen.getByRole("button", { name: "次へ" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/answers")?.body,
    ).toEqual({ questionId: "since", text: "昨日の夕方" });
  });

  it("API の選択肢に説明が無い種類の質問でも、設定の説明を添える（戻って聞き直した1問目）", async () => {
    openInquiry(makeView());

    const group = await screen.findByRole("group", {
      name: "何をしたいですか",
    });
    await waitFor(() =>
      expect(
        within(group).getByRole("button", { name: "使い方を知りたい" }),
      ).toHaveAccessibleDescription("操作の手順"),
    );
    expect(group).toHaveClass("stacked");
  });
});

describe("答えた質問の要約", () => {
  const entries: IntakeEntry[] = [
    makeTypeEntry("LOOKS_WRONG", "おかしいかもしれない"),
    {
      questionId: "system",
      kind: "SYSTEM",
      text: "どのシステムですか",
      answer: { kind: "INFERRED", optionId: "system-a", label: "システムA" },
    },
    {
      questionId: "function",
      kind: "FUNCTION",
      text: "どの機能ですか",
      answer: { kind: "UNKNOWN" },
    },
    {
      questionId: "target",
      kind: "DETAIL",
      text: "対象の取引先はどこですか",
      answer: { kind: "TEXT", text: "株式会社サンプル" },
    },
  ];

  it("「質問 → 答え」の1行になり、AI が文章から置いた答えにだけ注記が付く", async () => {
    openInquiry(intakeView(Q_NOTE, entries));

    await screen.findByLabelText("補足があれば書いてください");
    expect(entryRow("何をしたいですか")).toHaveTextContent(
      "何をしたいですか→おかしいかもしれない",
    );
    expect(entryRow("どのシステムですか")).toHaveTextContent(
      "どのシステムですか→システムA（文章から読み取りました）",
    );
    expect(entryRow("どの機能ですか")).toHaveTextContent("わからない");
    expect(entryRow("どの機能ですか")).not.toHaveTextContent("読み取りました");
    expect(entryRow("対象の取引先はどこですか")).toHaveTextContent(
      "株式会社サンプル",
    );
    expect(screen.getAllByText("（文章から読み取りました）")).toHaveLength(1);
    // 入口の質問の間は、押して戻れるボタン
    expect(entryRow("どのシステムですか").tagName).toBe("BUTTON");
  });

  it("書かずに進んだ補足は、その旨を答えとして示す", async () => {
    openInquiry(
      makeView({
        intake: {
          entries: [
            makeTypeEntry(),
            {
              questionId: "note",
              kind: "NOTE",
              text: "補足があれば書いてください",
              answer: { kind: "SKIPPED" },
            },
          ],
          next: null,
          complete: true,
        },
      }),
    );

    await screen.findByText("補足があれば書いてください");
    expect(entryRow("補足があれば書いてください")).toHaveTextContent(
      "書かずに進みました",
    );
  });

  it("押すと intake/back が呼ばれ、その質問が聞き直される（それより後の答えは消える）", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_NOTE, entries), {
      "POST /api/inquiries/inq-1/intake/back": {
        body: intakeView(
          makeQuestion({ questionId: "system", kind: "SYSTEM" }),
          entries.slice(0, 1),
          { type: LOOKS_WRONG },
        ),
      },
    });

    await user.click(
      await screen.findByRole("button", { name: /どのシステムですか/ }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/back")?.body,
    ).toEqual({ questionId: "system" });
    const group = await screen.findByRole("group", {
      name: "どのシステムですか",
    });
    expect(
      within(group).getByRole("button", { name: "システムA" }),
    ).toBeEnabled();
    expect(screen.queryByText("どの機能ですか")).not.toBeInTheDocument();
    expect(screen.queryByText("株式会社サンプル")).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText("補足があれば書いてください"),
    ).not.toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByText("どのシステムですか", { selector: "p" }),
      ).toHaveFocus(),
    );
  });

  it("言い直しが出た後は、押して戻れない普通の行になる", async () => {
    openRestatement({
      intake: { entries, next: null, complete: true },
    });

    await screen.findByText("つまり、こういうことですね。");
    const row = entryRow("どのシステムですか");
    expect(row.tagName).toBe("DIV");
    expect(
      screen.queryByRole("button", { name: /どのシステムですか/ }),
    ).not.toBeInTheDocument();
  });
});

describe("文章で書く", () => {
  it("作成前に書いて送ると、text つきで問い合わせを作り、書いた文章が吹き出しとして残る。読み取れなかった質問だけが続く", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: intakeView(
          makeQuestion(),
          [
            {
              ...makeTypeEntry("LOOKS_WRONG", "おかしいかもしれない"),
              answer: {
                kind: "INFERRED",
                optionId: "LOOKS_WRONG",
                label: "おかしいかもしれない",
              },
            },
          ],
          { utterance: "応募者一覧の件数がおかしいです" },
        ),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    // 入力欄は、リンクを押すまで出ない
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "文章で書く" }));
    const box = screen.getByLabelText(INPUT_LABEL);
    expect(box).toHaveFocus();
    expect(screen.getByRole("button", { name: "送信する" })).toBeDisabled();
    await user.type(box, "応募者一覧の件数がおかしいです");
    await user.click(screen.getByRole("button", { name: "送信する" }));

    expect(await screen.findByText("どのシステムですか")).toBeInTheDocument();
    expect(findCall(calls, "POST", "/api/inquiries")?.body).toEqual({
      text: "応募者一覧の件数がおかしいです",
    });
    expect(document.querySelector(".utterance")?.textContent).toBe(
      "応募者一覧の件数がおかしいです",
    );
    expect(entryRow("何をしたいですか")).toHaveTextContent(
      "（文章から読み取りました）",
    );
    await waitFor(() =>
      expect(window.location.pathname).toBe("/inquiries/inq-1"),
    );
    // 送った文章の入力欄は閉じ、リンクに戻る
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "文章で書く" }),
    ).toBeInTheDocument();
    // 作成のあとに、別の答えを送らない
    expect(
      countCalls(calls, "POST", "/api/inquiries/inq-1/intake/answers"),
    ).toBe(0);
    expectNoInternalWords();
  });

  it("作成後に書いて送ると、intake/text に text を送る", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(makeQuestion()), {
      "POST /api/inquiries/inq-1/intake/text": {
        body: intakeView(
          makeQuestion({ questionId: "function", kind: "FUNCTION" }),
          [makeTypeEntry()],
          { utterance: "請求書の宛名のことです" },
        ),
      },
    });

    await user.click(await screen.findByRole("button", { name: "文章で書く" }));
    await user.type(
      screen.getByLabelText(INPUT_LABEL),
      "請求書の宛名のことです",
    );
    await user.click(screen.getByRole("button", { name: "送信する" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/intake/text")?.body,
    ).toEqual({ text: "請求書の宛名のことです" });
    expect(
      await screen.findByText("請求書の宛名のことです", {
        selector: ".utterance p",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
  });

  it("URL の ?text= は入力欄を開いて文章を入れるだけで、自動では送信しない", async () => {
    window.history.replaceState(
      {},
      "",
      "/?text=%E6%8E%B2%E8%BC%89%E6%9C%9F%E9%99%90",
    );
    const calls = installFakeFetch({});
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByLabelText(INPUT_LABEL)).toHaveValue("掲載期限");
    // 1問目も並んでいて、選択肢から始めることもできる
    expect(
      await screen.findByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
  });

  it("モックの例文は「文章で書く」の入力欄に入る（送信はしない）", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "GET /api/config": { body: makeConfig({ mock: true }) },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await user.click(await screen.findByText("例文を試す"));
    expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "[根拠なし]" }));

    expect(screen.getByLabelText(INPUT_LABEL)).toHaveValue(
      "求人の掲載期限は、何日まで延長できますか？ [根拠なし]",
    );
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
  });
});

describe("緊急度", () => {
  it("作成前に選ぶと、作成のリクエストに urgency が付く（選択肢から始める場合）", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ urgency: "HURRY" }),
      },
      "POST /api/inquiries/inq-1/intake/answers": {
        body: intakeView(makeQuestion(), [makeTypeEntry()], {
          urgency: "HURRY",
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await user.click(await screen.findByRole("radio", { name: "急ぎ" }));
    expect(screen.getByRole("radio", { name: "急ぎ" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "通常" })).not.toBeChecked();
    // 作成前は API を呼ばない
    expect(calls.filter((c) => c.method !== "GET")).toHaveLength(0);
    await pickType(user);

    await screen.findByText("どのシステムですか");
    expect(findCall(calls, "POST", "/api/inquiries")?.body).toEqual({
      urgency: "HURRY",
    });
    // 作成後も選ばれたまま
    expect(screen.getByRole("radio", { name: "急ぎ" })).toBeChecked();
  });

  it("作成前に選ぶと、文章で始める場合も urgency が付く", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: intakeView(makeQuestion(), [makeTypeEntry()], {
          urgency: "BLOCKED",
          utterance: "止まっています",
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await user.click(
      await screen.findByRole("radio", { name: "業務が止まっている" }),
    );
    await user.click(screen.getByRole("button", { name: "文章で書く" }));
    await user.type(screen.getByLabelText(INPUT_LABEL), "止まっています");
    await user.click(screen.getByRole("button", { name: "送信する" }));

    await screen.findByText("どのシステムですか");
    expect(findCall(calls, "POST", "/api/inquiries")?.body).toEqual({
      text: "止まっています",
      urgency: "BLOCKED",
    });
  });

  it("作成後に選ぶと PUT …/urgency を呼び、返ってきた値が選ばれた状態になる", async () => {
    const user = userEvent.setup();
    const { calls } = openInquiry(intakeView(Q_SINCE), {
      "PUT /api/inquiries/inq-1/urgency": {
        body: intakeView(Q_SINCE, [makeTypeEntry()], { urgency: "HURRY" }),
      },
    });

    const normal = await screen.findByRole("radio", { name: "通常" });
    expect(normal).toBeChecked();
    await user.click(screen.getByRole("radio", { name: "急ぎ" }));

    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/urgency")?.body,
    ).toEqual({
      urgency: "HURRY",
    });
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "急ぎ" })).toBeChecked(),
    );
    expect(screen.getByRole("radio", { name: "通常" })).not.toBeChecked();
  });

  it("緊急度の選択肢は、質問の下にあり、名前は API が返したものをそのまま出す", async () => {
    installFakeFetch({
      "GET /api/config": {
        body: makeConfig({
          urgencies: [
            { id: "NORMAL", label: "ふつう" },
            { id: "HURRY", label: "いそぎ" },
            { id: "BLOCKED", label: "止まっている" },
          ],
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    const group = await screen.findByRole("group", { name: "急ぎですか" });
    expect(
      within(group)
        .getAllByRole("radio")
        .map((r) => r.closest("label")?.textContent),
    ).toEqual(["ふつう", "いそぎ", "止まっている"]);
    const question = screen.getByRole("group", { name: "何をしたいですか" });
    expect(
      question.compareDocumentPosition(group) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("「業務が止まっている」で入口を終えると、すぐ届けたと伝える（言い直しは出さない）", async () => {
    const user = userEvent.setup();
    openInquiry(intakeView(Q_NOTE, [makeTypeEntry()], { urgency: "BLOCKED" }), {
      "POST /api/inquiries/inq-1/intake/answers": {
        body: makeFiledView({
          urgency: "BLOCKED",
          intake: { entries: [makeTypeEntry()], next: null, complete: true },
          structured: makeStructuredView().structured,
        }),
      },
    });

    await user.type(
      await screen.findByLabelText("補足があれば書いてください"),
      "請求画面が開きません",
    );
    await user.click(screen.getByRole("button", { name: "次へ" }));

    expect(
      await screen.findByText(
        "担当者にすぐ届けました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "合っています" }),
    ).not.toBeInTheDocument();
    // 入口の質問が終わったので、緊急度の選択も出ない
    expect(
      screen.queryByRole("group", { name: "急ぎですか" }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();
  });
});

describe("入口から結果までの流れ", () => {
  it("(a)(n) 選んで答え、言い直しに「合っています」から「解決しました」まで通せ、内部用語が出ない", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    const typeEntry = makeTypeEntry();
    const detailEntry: IntakeEntry = {
      questionId: "target",
      kind: "DETAIL",
      text: "対象の取引先はどこですか",
      answer: { kind: "TEXT", text: "株式会社サンプル" },
    };
    let answers = 0;
    const calls = installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/intake/answers": () =>
        ++answers === 1
          ? { body: intakeView(Q_TARGET, [typeEntry]) }
          : {
              body: makeStructuredView({
                intake: {
                  entries: [typeEntry, detailEntry],
                  next: null,
                  complete: true,
                },
              }),
            },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ ...INVESTIGATION_STATE, canSendNow: true }),
      },
      "POST /api/inquiries/inq-1/resolution": {
        body: makeView({
          ...closedState("RESOLVED"),
          answer: groundedAnswer,
        }),
      },
    });
    render(<App subscribe={stream.subscribe} />);
    await screen.findByRole("group", { name: "何をしたいですか" });
    expectNoInternalWords();

    await pickType(user);
    await user.type(
      await screen.findByLabelText("対象の取引先はどこですか"),
      "株式会社サンプル",
    );
    await user.click(screen.getByRole("button", { name: "次へ" }));

    // 言い直し。答えた質問は1行で残る
    expect(
      await screen.findByText("つまり、こういうことですね。"),
    ).toBeInTheDocument();
    expect(screen.getByText(SUMMARY)).toBeInTheDocument();
    expect(entryRow("対象の取引先はどこですか")).toHaveTextContent(
      "株式会社サンプル",
    );
    // 入口の質問の間のものは、確定までに消える
    expect(
      screen.queryByRole("group", { name: "急ぎですか" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "文章で書く" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();

    await user.click(screen.getByRole("button", { name: "合っています" }));
    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/structured")?.body,
    ).toEqual({});

    // 調べている間
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    expect(stream.latest.id).toBe("inq-1");
    act(() => stream.latest.handlers.onProgress("コードを検索しています"));
    act(() => stream.latest.handlers.onProgress("資料を照らし合わせています"));
    expect(
      screen.getByRole("heading", { name: "調べています" }),
    ).toBeInTheDocument();
    const progress = screen.getByRole("list", { name: "調べている内容" });
    expect(
      within(progress)
        .getAllByRole("listitem")
        .map((li) => li.textContent),
    ).toEqual(["コードを検索しています", "資料を照らし合わせています"]);
    expect(progress).toHaveAttribute("aria-live", "polite");
    // 答えを待たずに送れる（canSendNow が真）
    expect(
      screen.getByText("待たずに人に見てもらいたいときはこちら。"),
    ).toBeInTheDocument();
    expectNoInternalWords();

    // 調べた結果
    act(() =>
      stream.latest.handlers.onDone(
        makeStructuredView({ ...RESULT_STATE, answer: groundedAnswer }),
      ),
    );
    expect(
      await screen.findByRole("heading", { name: "調べた結果" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expect(stream.latest.closed).toBe(true);
    expect(
      screen.getByText("根拠にした資料（1件）").closest("details"),
    ).not.toHaveAttribute("open");
    expect(
      screen.getByText("担当者向けの詳しい内容").closest("details"),
    ).not.toHaveAttribute("open");
    expectNoInternalWords();

    await user.click(screen.getByRole("button", { name: "解決しました" }));
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution")?.body,
    ).toEqual({ resolved: true });
    expect(
      await screen.findByText(
        "解決してよかったです。この内容は記録しておきます。",
      ),
    ).toBeInTheDocument();
    expectNoInternalWords();
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(
      await screen.findByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("調べた結果")).not.toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("(e) 「少し違います」の一言で作り直しを呼び、言い直しが差し替わる", async () => {
    const user = userEvent.setup();
    const { calls } = openRestatement(
      {},
      {
        "POST /api/inquiries/inq-1/revision": {
          body: makeStructuredView({
            revisionsLeft: 1,
            structured: {
              ...makeStructuredView().structured!,
              summary: "消えたのは一昨日からで、理由を知りたい。",
            },
          }),
        },
      },
    );

    await user.click(
      await screen.findByRole("button", { name: "少し違います" }),
    );
    await user.type(screen.getByLabelText("どこが違いますか？"), "一昨日から");
    await user.click(screen.getByRole("button", { name: "作り直してもらう" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/revision")?.body,
    ).toEqual({ note: "一昨日から" });
    expect(
      await screen.findByText("消えたのは一昨日からで、理由を知りたい。"),
    ).toBeInTheDocument();
    expect(screen.queryByText(SUMMARY)).not.toBeInTheDocument();
    // 入力欄は閉じ、もう一度「少し違います」を選べる
    expect(
      screen.queryByLabelText("どこが違いますか？"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "少し違います" }),
    ).toBeInTheDocument();
  });

  it("(f) 作り直しを使い切ると案内が出て、「このまま担当者に送る」が send-now を呼ぶ", async () => {
    const user = userEvent.setup();
    const { calls } = openRestatement(
      { revisionsLeft: 0, canSendNow: true },
      {
        "POST /api/inquiries/inq-1/send-now": {
          body: makeFiledView({ structured: makeStructuredView().structured }),
        },
      },
    );

    expect(
      await screen.findByText(
        "うまく汲み取れませんでした。書いていただいた内容のまま担当者に送ることもできます。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "少し違います" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "自分で書き直す" }),
    ).toBeInTheDocument();
    // 使い切っても、合っていれば確定できる
    expect(screen.getByRole("button", { name: "合っています" })).toBeEnabled();
    // ページ下部の「答えを待たずに担当者に送る」は重ねて出さない
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("待たずに人に見てもらいたいときはこちら。"),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "このまま担当者に送る" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/send-now"),
    ).toBeDefined();
    expect(
      await screen.findByText(
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
  });

  it("(g) 「自分で書き直す」は書き直した文章を summary で確定する", async () => {
    const user = userEvent.setup();
    const { calls } = openRestatement(
      {},
      {
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({ ...INVESTIGATION_STATE }),
        },
      },
    );

    await user.click(
      await screen.findByRole("button", { name: "少し違います" }),
    );
    await user.click(screen.getByRole("button", { name: "自分で書き直す" }));
    const editor = screen.getByLabelText("言い直しを書き直してください");
    // 直す元として、いまの言い直しが入っている
    expect(editor).toHaveValue(SUMMARY);
    await user.clear(editor);
    await user.type(editor, "掲載期限を90日に延ばせるか確認したい。");
    await user.click(
      screen.getByRole("button", { name: "この内容で確定する" }),
    );

    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/structured")?.body,
    ).toEqual({ summary: "掲載期限を90日に延ばせるか確認したい。" });
  });

  it("(h) 「前の質問に戻る」は reopen を呼び、最後の質問が聞き直される", async () => {
    const user = userEvent.setup();
    const typeEntry = makeTypeEntry();
    const { calls } = openRestatement(
      {
        intake: {
          entries: [
            typeEntry,
            {
              questionId: "since",
              kind: "DETAIL",
              text: "いつから起きていますか",
              answer: { kind: "OPTION", optionId: "today", label: "今日から" },
            },
          ],
          next: null,
          complete: true,
        },
      },
      {
        "POST /api/inquiries/inq-1/reopen": {
          body: intakeView(Q_SINCE, [typeEntry]),
        },
      },
    );

    await user.click(
      await screen.findByRole("button", { name: "前の質問に戻る" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/reopen"),
    ).toBeDefined();
    const group = await screen.findByRole("group", {
      name: "いつから起きていますか",
    });
    expect(
      within(group).getByRole("button", { name: "今日から" }),
    ).toBeEnabled();
    // 言い直しは消え、答えた質問は押して戻れる形に戻る
    expect(
      screen.queryByText("つまり、こういうことですね。"),
    ).not.toBeInTheDocument();
    expect(entryRow("何をしたいですか").tagName).toBe("BUTTON");
  });

  it("答えた質問が無ければ「前の質問に戻る」を出さない", async () => {
    openRestatement({
      intake: { entries: [], next: null, complete: true },
    });

    await screen.findByText("つまり、こういうことですね。");
    expect(
      screen.queryByRole("button", { name: "前の質問に戻る" }),
    ).not.toBeInTheDocument();
  });
});

describe("種類ごとの結果", () => {
  const config = {
    "GET /api/config": {
      body: makeConfig({ filedGuidance: "担当者が内容を確認して連絡します。" }),
    },
  };

  /** 確定して調べている最中まで進め、届いた最後の応答で結果を出す。 */
  async function reachResult(
    user: User,
    view: Partial<InquiryView>,
    done: InquiryView,
    routes: Record<string, RouteResult | ((body: unknown) => RouteResult)> = {},
  ) {
    const stream = new FakeStream();
    const { calls } = openRestatement(
      view,
      {
        ...config,
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({ ...view, ...INVESTIGATION_STATE }),
        },
        ...routes,
      },
      stream,
    );
    await user.click(
      await screen.findByRole("button", { name: "合っています" }),
    );
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() => stream.latest.handlers.onDone(done));
    return calls;
  }

  it("(j) 根拠なしの結果は、done が担当者の番（HANDLING）で届き、見つからなかったと示し、引き継いだ理由を添える。本人に送らせるボタンは出さない", async () => {
    const user = userEvent.setup();
    const calls = await reachResult(
      user,
      {},
      makeFiledView({
        structured: makeStructuredView().structured,
        sentImmediately: false,
        handoverReason: "NO_ANSWER",
        answer: ungroundedAnswer,
        messages: [],
      }),
    );

    expect(
      await screen.findByText("手元の資料からは答えを見つけられませんでした。"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/^根拠にした資料/)).not.toBeInTheDocument();
    const memo = screen.getByText("担当者向けの詳しい内容").closest("details")!;
    expect(memo).not.toHaveAttribute("open");
    expect(
      within(memo).getByText("調べたが見つからなかったこと"),
    ).toBeInTheDocument();
    expect(within(memo).getByText("該当する仕様の記述")).toBeInTheDocument();
    // 調べている画面から、引き継いだ画面に切り替わる（届いた状態だけで決まる）
    expect(
      screen.queryByRole("heading", { name: "調べています" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("担当者が確認しています")).toBeInTheDocument();
    expect(
      screen.getByText(
        "調べましたが、はっきりした答えが見つかりませんでした。担当者に引き継ぎました。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    // 本人に、送るかどうかを選ばせない
    for (const name of [
      "担当者に送る",
      "解決しました",
      "まだ解決していません（担当者に送る）",
    ]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
    expect(
      screen.queryByText("やりたかったことは、進められそうですか。"),
    ).not.toBeInTheDocument();
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution"),
    ).toBeUndefined();
  });

  it("時間切れは、done が HANDLING・answer なしで届き、調べた結果は出さずに、引き継いだ状態を出す", async () => {
    const user = userEvent.setup();
    await reachResult(
      user,
      {},
      makeFiledView({
        structured: makeStructuredView().structured,
        sentImmediately: false,
        handoverReason: "TIMEOUT",
        answer: null,
        messages: [],
      }),
    );

    expect(
      await screen.findByText("担当者が確認しています"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "調べるのに時間がかかっているため、担当者に引き継ぎました。調べた内容は、担当者に渡します。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "調べた結果" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "調べています" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("追加で伝える")).toBeInTheDocument();
    expectNoInternalWords();
  });

  it("「やってほしい」で自分でできる手順が見つかったときは、「自分でできました」と「担当者に頼む」を出す", async () => {
    const user = userEvent.setup();
    const doForMe = { type: DO_FOR_ME };
    const calls = await reachResult(
      user,
      doForMe,
      makeStructuredView({
        ...doForMe,
        ...RESULT_STATE,
        answer: { ...groundedAnswer, answered: true },
      }),
      {
        "POST /api/inquiries/inq-1/resolution": (body) =>
          (body as { resolved: boolean }).resolved
            ? {
                body: makeView({
                  ...closedState("RESOLVED"),
                  answer: groundedAnswer,
                }),
              }
            : {
                body: makeFiledView({
                  ...doForMe,
                  sentImmediately: false,
                  answer: groundedAnswer,
                }),
              },
      },
    );

    const mine = await screen.findByRole("button", {
      name: "自分でできました",
    });
    const ask = screen.getByRole("button", { name: "担当者に頼む" });
    expect(mine).toHaveClass("primary");
    expect(ask).not.toHaveClass("primary");
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /まだ解決していません/ }),
    ).not.toBeInTheDocument();

    await user.click(ask);
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution")?.body,
    ).toEqual({ resolved: false });
    expect(
      await screen.findByText(
        "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      ),
    ).toBeInTheDocument();
    // 本人が頼んだ場合は、「手順が見つからなかった」旨の案内は出さない
    expect(
      screen.queryByText("調べた内容を添えて、担当者に送りました。"),
    ).not.toBeInTheDocument();
  });

  it("「自分でできました」は resolved: true を送り、自己解決の文言で終わる", async () => {
    const user = userEvent.setup();
    const doForMe = { type: DO_FOR_ME };
    const calls = await reachResult(
      user,
      doForMe,
      makeStructuredView({
        ...doForMe,
        ...RESULT_STATE,
        answer: { ...groundedAnswer, answered: true },
      }),
      {
        "POST /api/inquiries/inq-1/resolution": {
          body: makeView({
            ...doForMe,
            ...closedState("RESOLVED"),
            answer: groundedAnswer,
          }),
        },
      },
    );

    await user.click(
      await screen.findByRole("button", { name: "自分でできました" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution")?.body,
    ).toEqual({ resolved: true });
    expect(
      await screen.findByText(
        "解決してよかったです。この内容は記録しておきます。",
      ),
    ).toBeInTheDocument();
  });

  it("「やってほしい」で手順が見つからなかったときは、結果の上に調べた内容を添えて送ったと示す", async () => {
    const user = userEvent.setup();
    const doForMe = { type: DO_FOR_ME };
    await reachResult(
      user,
      doForMe,
      makeStructuredView({
        ...doForMe,
        ...HANDLING_STATE,
        handedOverAt: HANDED_OVER_AT,
        answer: ungroundedAnswer,
        messages: [],
      }),
    );

    const notice = await screen.findByText(
      "調べた内容を添えて、担当者に送りました。",
    );
    const resultHeading = screen.getByRole("heading", { name: "調べた結果" });
    expect(
      notice.compareDocumentPosition(resultHeading) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen.getByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    // 解決の判定は求めない
    expect(
      screen.queryByRole("button", { name: "自分でできました" }),
    ).not.toBeInTheDocument();
  });

  it("「おかしいかもしれない」は、調べた結果を添えて送ったことを結果の上に示す", async () => {
    const user = userEvent.setup();
    const wrong = { type: LOOKS_WRONG };
    await reachResult(
      user,
      wrong,
      makeStructuredView({
        ...wrong,
        ...HANDLING_STATE,
        handedOverAt: HANDED_OVER_AT,
        answer: groundedAnswer,
        messages: [],
      }),
    );

    const notice = await screen.findByText(
      "いまの動きを調べました。この内容を添えて担当者に送りました。",
    );
    const resultHeading = screen.getByRole("heading", { name: "調べた結果" });
    // 結果の上に表示される
    expect(
      notice.compareDocumentPosition(resultHeading) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen.getByText(
        "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    // 解決の判定は求めない
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();
  });

  it("「変えてほしい」は、言い直しを確かめた時点で調べずに送り、送った後の案内を示す", async () => {
    const user = userEvent.setup();
    openRestatement(
      {
        type: {
          id: "CHANGE_REQUEST",
          label: "変えてほしい",
          by: "ASKER",
        },
      },
      {
        ...config,
        "PUT /api/inquiries/inq-1/structured": {
          body: makeFiledView({
            sentImmediately: false,
            structured: makeStructuredView().structured,
          }),
        },
      },
    );

    await user.click(
      await screen.findByRole("button", { name: "合っています" }),
    );

    expect(
      await screen.findByText(
        "担当者に送りました。ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("担当者が内容を確認して連絡します。"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "調べています" }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();
  });

  it("種類や対象システムを変える操作と、障害の案内は出ない", async () => {
    openInquiry(intakeView(Q_SINCE, [makeTypeEntry()], { type: LOOKS_WRONG }));

    await screen.findByText("いつから起きていますか");
    expect(
      screen.queryByRole("button", { name: "変更" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/として受け取りました/)).not.toBeInTheDocument();
    expect(screen.queryByText(/障害/)).not.toBeInTheDocument();
  });
});

describe("答えを待たずに送る", () => {
  it("canSendNow が真の調べている間だけページの下部に出て、send-now を呼んで完了になる", async () => {
    const user = userEvent.setup();
    const { calls } = openRestatement(
      { ...INVESTIGATION_STATE, canSendNow: true },
      {
        "POST /api/inquiries/inq-1/send-now": {
          body: makeFiledView({ structured: makeStructuredView().structured }),
        },
      },
    );

    expect(
      await screen.findByText("待たずに人に見てもらいたいときはこちら。"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: SEND_NOW }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/send-now"),
    ).toBeDefined();
    expect(
      await screen.findByText(
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
  });

  it("canSendNow が偽なら、どの段階でも出さない", async () => {
    openRestatement({ ...INVESTIGATION_STATE, canSendNow: false });

    await screen.findByRole("heading", { name: "調べています" });
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("待たずに人に見てもらいたいときはこちら。"),
    ).not.toBeInTheDocument();
  });

  it("入口の質問の間は、canSendNow が真でも出さない", async () => {
    openInquiry(intakeView(Q_SINCE, [makeTypeEntry()], { canSendNow: true }));

    await screen.findByText("いつから起きていますか");
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
  });

  it("調べた結果の画面でも、canSendNow が真なら出る", async () => {
    openRestatement({
      ...RESULT_STATE,
      answer: groundedAnswer,
      canSendNow: true,
    });

    expect(
      await screen.findByRole("button", { name: SEND_NOW }),
    ).toBeInTheDocument();
  });
});

describe("エラーの導線", () => {
  /** 調べている最中まで進める。 */
  function reachResearching(stream: FakeStream) {
    return openRestatement(
      { ...INVESTIGATION_STATE, canSendNow: true },
      {},
      stream,
    );
  }

  it("進捗ストリームの failed で再試行と「答えを待たずに担当者に送る」が出る", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    reachResearching(stream);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onFailed({
        code: "AI_UNAVAILABLE",
        message: "model down",
      }),
    );

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "いま自動の調査を使えません。担当者に直接送れます。",
    );
    expect(alert).not.toHaveTextContent("model down");
    expect(screen.getByRole("button", { name: SEND_NOW })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "もう一度調べる" }));
    await waitFor(() => expect(stream.subscriptions).toHaveLength(2));
    expect(stream.subscriptions[0].closed).toBe(true);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("拒否された場合は自動では調べられなかったと伝える", async () => {
    const stream = new FakeStream();
    reachResearching(stream);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onFailed({ code: "AI_REFUSED", message: "x" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "この内容は自動では調べられませんでした。担当者に直接送れます。",
    );
  });

  describe("入口の最後の答えのあとに問いを組み立てられなかった場合（言い直しの失敗）", () => {
    const stalled = (overrides: Partial<InquiryView> = {}) =>
      makeView({
        canSendNow: true,
        intake: {
          entries: [
            makeTypeEntry(),
            {
              questionId: "note",
              kind: "NOTE",
              text: "補足があれば書いてください",
              answer: { kind: "TEXT", text: "先週から" },
            },
          ],
          next: null,
          complete: true,
        },
        utterance: "先週から",
        ...overrides,
      });

    /** 補足に答えるところから始め、最後の答えの後に言い直しが失敗する状態にする。 */
    async function failCompose(
      user: User,
      routes: Record<
        string,
        RouteResult | ((body: unknown) => RouteResult)
      > = {},
    ) {
      let gets = 0;
      const opened = openInquiry(intakeView(Q_NOTE), {
        "GET /api/inquiries/inq-1": () => ({
          body: ++gets === 1 ? intakeView(Q_NOTE) : stalled(),
        }),
        "POST /api/inquiries/inq-1/intake/answers": {
          status: 503,
          body: {
            code: "AI_UNAVAILABLE",
            message: "down",
            inquiryId: "inq-1",
          },
        },
        ...routes,
      });
      await user.type(
        await screen.findByLabelText("補足があれば書いてください"),
        "先週から",
      );
      await user.click(screen.getByRole("button", { name: "次へ" }));
      return opened;
    }

    it("エラーの表示の中に「もう一度試す」と「担当者に送る」を出す。ページの下の送る操作は出さない", async () => {
      const user = userEvent.setup();
      await failCompose(user);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(
        "いま自動の調査を使えません。担当者に直接送れます。",
      );
      expect(alert).not.toHaveTextContent("down");
      expect(
        await within(alert).findByRole("button", { name: "もう一度試す" }),
      ).toBeEnabled();
      expect(
        within(alert).getByRole("button", { name: "担当者に送る" }),
      ).toBeEnabled();
      expect(
        screen.queryByRole("button", { name: SEND_NOW }),
      ).not.toBeInTheDocument();
      // 保存された答えが画面に反映され、止まった質問は残らない
      expect(
        screen.queryByLabelText("補足があれば書いてください"),
      ).not.toBeInTheDocument();
      expectNoInternalWords();
    });

    it("「もう一度試す」は intake/compose を呼び、言い直しに進む", async () => {
      const user = userEvent.setup();
      const { calls } = await failCompose(user, {
        "POST /api/inquiries/inq-1/intake/compose": {
          body: makeStructuredView({
            intake: stalled().intake,
            utterance: "先週から",
          }),
        },
      });

      const alert = await screen.findByRole("alert");
      await user.click(
        await within(alert).findByRole("button", { name: "もう一度試す" }),
      );

      expect(
        findCall(calls, "POST", "/api/inquiries/inq-1/intake/compose"),
      ).toBeDefined();
      expect(
        await screen.findByText("つまり、こういうことですね。"),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("「担当者に送る」は send-now を呼んで、送った旨で終わる", async () => {
      const user = userEvent.setup();
      const { calls } = await failCompose(user, {
        "POST /api/inquiries/inq-1/send-now": {
          body: makeFiledView({ utterance: "先週から" }),
        },
      });

      const alert = await screen.findByRole("alert");
      await user.click(
        await within(alert).findByRole("button", { name: "担当者に送る" }),
      );

      expect(
        findCall(calls, "POST", "/api/inquiries/inq-1/send-now"),
      ).toBeDefined();
      expect(
        await screen.findByText(
          "担当者に送りました。書いていただいた内容をそのまま添えています。",
        ),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("canSendNow が偽なら「担当者に送る」は出さず、「もう一度試す」だけを出す", async () => {
      const user = userEvent.setup();
      let gets = 0;
      openInquiry(intakeView(Q_NOTE), {
        "GET /api/inquiries/inq-1": () => ({
          body:
            ++gets === 1 ? intakeView(Q_NOTE) : stalled({ canSendNow: false }),
        }),
        "POST /api/inquiries/inq-1/intake/answers": {
          status: 503,
          body: { code: "AI_REFUSED", message: "x", inquiryId: "inq-1" },
        },
      });
      await user.click(
        await screen.findByRole("button", { name: "書かずに進む" }),
      );

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(
        "この内容は自動では調べられませんでした。担当者に直接送れます。",
      );
      expect(
        await within(alert).findByRole("button", { name: "もう一度試す" }),
      ).toBeInTheDocument();
      expect(
        within(alert).queryByRole("button", { name: "担当者に送る" }),
      ).not.toBeInTheDocument();
    });

    it("文章で始めた作成が言い直しの失敗になった場合も、保存された問い合わせを開いて同じ導線を出す", async () => {
      const user = userEvent.setup();
      const calls = installFakeFetch({
        "POST /api/inquiries": {
          status: 503,
          body: {
            code: "AI_UNAVAILABLE",
            message: "down",
            inquiryId: "saved-9",
          },
        },
        "GET /api/inquiries/saved-9": {
          body: stalled({ id: "saved-9", utterance: "つらい" }),
        },
        "POST /api/inquiries/saved-9/send-now": {
          body: makeFiledView({ id: "saved-9", utterance: "つらい" }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await user.click(
        await screen.findByRole("button", { name: "文章で書く" }),
      );
      await user.type(screen.getByLabelText(INPUT_LABEL), "つらい");
      await user.click(screen.getByRole("button", { name: "送信する" }));

      const alert = await screen.findByRole("alert");
      expect(
        await within(alert).findByRole("button", { name: "担当者に送る" }),
      ).toBeInTheDocument();
      expect(
        within(alert).getByRole("button", { name: "もう一度試す" }),
      ).toBeInTheDocument();
      await waitFor(() =>
        expect(window.location.pathname).toBe("/inquiries/saved-9"),
      );
      // 送った文章は本人の言葉として残り、入力欄は閉じる
      expect(
        screen.getByText("つらい", { selector: ".utterance p" }),
      ).toBeInTheDocument();
      expect(screen.queryByLabelText(INPUT_LABEL)).not.toBeInTheDocument();

      await user.click(
        within(alert).getByRole("button", { name: "担当者に送る" }),
      );
      expect(
        findCall(calls, "POST", "/api/inquiries/saved-9/send-now"),
      ).toBeDefined();
      expect(
        await screen.findByText(
          "担当者に送りました。書いていただいた内容をそのまま添えています。",
        ),
      ).toBeInTheDocument();
    });
  });

  it("答えの送信に失敗しても（組み立ての失敗ではない）、質問を残して汎用の文言にする", async () => {
    const user = userEvent.setup();
    openInquiry(intakeView(Q_SINCE), {
      "POST /api/inquiries/inq-1/intake/answers": {
        status: 400,
        body: { code: "VALIDATION", message: "stacktrace here" },
      },
    });

    await user.click(await screen.findByRole("button", { name: "今日から" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("うまく処理できませんでした");
    expect(alert).not.toHaveTextContent("stacktrace here");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    // もう一度押せる
    expect(screen.getByRole("button", { name: "今日から" })).toBeEnabled();
  });

  it("作成に失敗し、保存された問い合わせも無いときは、答えを待たずに送る案内を出さず汎用の文言にする", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "POST /api/inquiries": {
        status: 500,
        body: { code: "INTERNAL", message: "boom" },
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await pickType(user);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("うまく処理できませんでした");
    expect(
      screen.queryByRole("button", { name: SEND_NOW }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
  });
});

describe("モック表示", () => {
  it("モック時はバッジと例文ボタンが出る。状況の例は5つの種類、動きの例は12個のタグ", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ mock: true }) },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    const badge = await screen.findByText("モック");
    const details = badge.closest("details");
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute("open");
    await user.click(screen.getByText("例文を試す"));
    expect(details).toHaveAttribute("open");
    const bar = screen.getByRole("region", { name: "モックのシナリオ" });
    const situations = within(bar).getByRole("group", { name: "状況の例" });
    expect(
      within(situations)
        .getAllByRole("button")
        .map((b) => b.textContent),
    ).toEqual([
      "使い方を知りたい",
      "仕組みを知りたい",
      "おかしいかもしれない",
      "やってほしい",
      "変えてほしい",
    ]);
    const actions = within(bar).getByRole("group", { name: "動きの例" });
    expect(within(actions).getAllByRole("button")).toHaveLength(12);
    expect(
      within(actions).getByRole("button", { name: "[言い直しエラー]" }),
    ).toBeInTheDocument();
    expect(within(bar).getAllByRole("button")).toHaveLength(17);
    expect(
      within(bar).getByText(/「文章で書く」の入力欄に入ります/),
    ).toBeInTheDocument();

    // 種類の例文を選ぶと、その文章が入力欄に入る
    await user.click(
      within(situations).getByRole("button", { name: "やってほしい" }),
    );
    expect(screen.getByLabelText(INPUT_LABEL)).toHaveValue(
      "掲載期限を一括で延長してほしいです。",
    );
  });

  it("問い合わせを作った後は例文ボタンを使えない", async () => {
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ mock: true }) },
      "GET /api/inquiries/inq-1": { body: intakeView(Q_SINCE) },
    });
    window.history.replaceState({}, "", "/inquiries/inq-1");
    render(<App subscribe={new FakeStream().subscribe} />);

    const bar = await screen.findByRole("region", { name: "モックのシナリオ" });
    for (const button of within(bar).getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
  });

  describe("スキルごとの実装の表示", () => {
    const ollamaExceptInvestigator = {
      reception: "ollama",
      interviewer: "ollama",
      restater: "ollama",
      investigator: "mock",
      explainer: "ollama",
    };

    it("(a) skills が無い・全モックなら従来どおり「モック」で内訳や注記は出ない", async () => {
      for (const skills of [
        undefined,
        { reception: "mock", investigator: "mock" },
      ]) {
        installFakeFetch({
          "GET /api/config": { body: makeConfig({ mock: true, skills }) },
        });
        const { unmount } = render(
          <App subscribe={new FakeStream().subscribe} />,
        );

        expect(await screen.findByText("モック")).toBeInTheDocument();
        expect(screen.queryByText("一部モック")).not.toBeInTheDocument();
        expect(screen.queryByText(/手元のモデル/)).not.toBeInTheDocument();
        expect(
          screen.queryByText(/モックで動いているスキルにだけ効きます/),
        ).not.toBeInTheDocument();
        unmount();
      }
    });

    it("(b) 一部が ollama なら「一部モック」と内訳、タグの注記、調査がモックの注記が出る", async () => {
      installFakeFetch({
        "GET /api/config": {
          body: makeConfig({ mock: true, skills: ollamaExceptInvestigator }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      expect(await screen.findByText("一部モック")).toBeInTheDocument();
      expect(
        screen.getByText(
          "受付・聞き手・言い直し・説明は手元のモデル、調査はモックで動いています",
        ),
      ).toBeInTheDocument();
      expect(
        screen.getByText("タグは、モックで動いているスキルにだけ効きます"),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          "何を尋ねても、求人の掲載期限についての調査結果が返ります",
        ),
      ).toBeInTheDocument();
    });

    it("(b) 調査が実際のモデルなら、調査の注記は出さない", async () => {
      installFakeFetch({
        "GET /api/config": {
          body: makeConfig({
            mock: true,
            skills: {
              ...ollamaExceptInvestigator,
              investigator: "ollama",
              reception: "mock",
            },
          }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      expect(await screen.findByText("一部モック")).toBeInTheDocument();
      expect(
        screen.getByText("タグは、モックで動いているスキルにだけ効きます"),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(/求人の掲載期限についての調査結果が返ります/),
      ).not.toBeInTheDocument();
    });

    it("(c) mock が false なら案内枠を出さない", async () => {
      installFakeFetch({
        "GET /api/config": {
          body: makeConfig({
            mock: false,
            skills: {
              reception: "ollama",
              interviewer: "ollama",
              restater: "ollama",
              investigator: "ollama",
              explainer: "ollama",
            },
          }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      expect(
        await screen.findByRole("group", { name: "何をしたいですか" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: "モックのシナリオ" }),
      ).not.toBeInTheDocument();
      expect(screen.queryByText(/モック/)).not.toBeInTheDocument();
    });
  });
});

describe("調査の結果と説明の失敗", () => {
  /** 調べている最中まで進める。`researching` は取得した問い合わせ。 */
  function reachResearching(
    stream: FakeStream,
    researching = makeStructuredView({
      ...INVESTIGATION_STATE,
      canSendNow: true,
    }),
  ) {
    return openInquiry(researching, {}, stream);
  }

  /** 結果まで進めて詳しい内容を返す。 */
  async function showResult(answer: typeof groundedAnswer) {
    const stream = new FakeStream();
    reachResearching(stream);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onDone(
        makeStructuredView({ ...RESULT_STATE, answer }),
      ),
    );
    await screen.findByRole("heading", { name: "調べた結果" });
    return screen.getByText("担当者向けの詳しい内容").closest("details")!;
  }

  it("(a) unconfirmed に中身が入った応答が来ても、本人の画面には見出しも事実の文も出さない", async () => {
    const memo = await showResult({
      ...groundedAnswer,
      memo: {
        ...groundedAnswer.memo,
        unconfirmed: ["期限切れの求人は、再掲載ボタンから元に戻せる"],
      },
    });

    // 担当者向けの詳しい内容は従来どおり出るが、確かめられなかったことは出ない
    expect(within(memo).getByText("分かったこと")).toBeInTheDocument();
    expect(
      within(memo).queryByText("根拠を確かめられなかったこと"),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("再掲載ボタン");
    expect(document.body.textContent).not.toContain("裏づけを取れていません");
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expectNoInternalWords();
  });

  it("(d) 説明だけが失敗しても failed で再試行と答えを待たずに送るが出る", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    reachResearching(stream);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() => stream.latest.handlers.onProgress("根拠を確かめています"));
    act(() => stream.latest.handlers.onProgress("答えをまとめています"));
    act(() =>
      stream.latest.handlers.onFailed({
        code: "AI_UNAVAILABLE",
        message: "説明に失敗",
      }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "いま自動の調査を使えません。担当者に直接送れます。",
    );
    // 届いた進捗は残り、結果は描かない
    const progress = screen.getByRole("list", { name: "調べている内容" });
    expect(
      within(progress)
        .getAllByRole("listitem")
        .map((li) => li.textContent),
    ).toEqual(["根拠を確かめています", "答えをまとめています"]);
    expect(
      screen.queryByRole("heading", { name: "調べた結果" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: SEND_NOW })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "もう一度調べる" }));
    await waitFor(() => expect(stream.subscriptions).toHaveLength(2));
  });

  it("(e) ANSWERING の間は answer が入った応答でも調べている表示を保ち、結果を描かない", async () => {
    const stream = new FakeStream();
    reachResearching(
      stream,
      makeStructuredView({ ...INVESTIGATION_STATE, answer: groundedAnswer }),
    );

    expect(
      await screen.findByRole("heading", { name: "調べています" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "調べた結果" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("掲載期限は最大90日まで延長できます。"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
  });
});

describe("問いに答えられたか", () => {
  const OFF_TOPIC = "ご質問への答えは、手元の資料では確認できませんでした。";

  /** 調べている最中まで進め、届いた最後の応答で結果を出す。 */
  async function showResult(
    user: User,
    done: ReturnType<typeof makeStructuredView>,
    extra: Partial<InquiryView> = {},
  ) {
    const stream = new FakeStream();
    openRestatement(
      extra,
      {
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({ ...extra, ...INVESTIGATION_STATE }),
        },
        "POST /api/inquiries/inq-1/resolution": {
          body: makeFiledView({
            structured: makeStructuredView().structured,
            sentImmediately: false,
            answer: done.answer,
          }),
        },
      },
      stream,
    );
    await user.click(
      await screen.findByRole("button", { name: "合っています" }),
    );
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() => stream.latest.handlers.onDone(done));
    await screen.findByRole("heading", { name: "調べた結果" });
  }

  const relatedAnswer = {
    ...groundedAnswer,
    answered: false,
    askerExplanation: "掲載期限は最大90日まで延長できます。",
  };

  it("(a) 根拠ありで答えている場合は説明を主に出し、解決しましたが主のボタン", async () => {
    const user = userEvent.setup();
    await showResult(
      user,
      makeStructuredView({
        ...RESULT_STATE,
        answer: { ...groundedAnswer, answered: true },
      }),
    );

    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expect(screen.queryByText(OFF_TOPIC)).not.toBeInTheDocument();
    expect(screen.queryByText("関連して分かったこと")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解決しました" })).toHaveClass(
      "primary",
    );
  });

  it("(a) 根拠はあるが答えていない場合は、答えを確認できなかったと先に示し、説明は関連として控えめに出す。担当者に渡った後なので、送らせるボタンは出さない", async () => {
    await showResult(
      userEvent.setup(),
      makeFiledView({
        structured: makeStructuredView().structured,
        sentImmediately: false,
        handoverReason: "NO_ANSWER",
        answer: relatedAnswer,
        messages: [],
      }),
    );

    const off = screen.getByText(OFF_TOPIC);
    const related = screen.getByRole("heading", {
      name: "関連して分かったこと",
    });
    const explanation = screen.getByText(
      "掲載期限は最大90日まで延長できます。",
    );
    const follows = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(off, related)).toBe(true);
    expect(follows(related, explanation)).toBe(true);
    expect(explanation).toHaveClass("related");
    // 折りたたみは従来どおり
    expect(
      screen.getByText("根拠にした資料（1件）").closest("details"),
    ).not.toHaveAttribute("open");
    expect(
      screen.getByText("担当者向けの詳しい内容").closest("details"),
    ).not.toHaveAttribute("open");
    expectNoInternalWords();

    expect(
      screen.getByText(
        "調べましたが、はっきりした答えが見つかりませんでした。担当者に引き継ぎました。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "担当者に送る" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
  });

  it("(a) 根拠なしの場合は見つからなかったと示し、関連の見出しは出ない", async () => {
    const user = userEvent.setup();
    await showResult(
      user,
      makeFiledView({
        structured: makeStructuredView().structured,
        sentImmediately: false,
        handoverReason: "NO_ANSWER",
        answer: { ...ungroundedAnswer, answered: false },
        messages: [],
      }),
    );

    expect(
      screen.getByText("手元の資料からは答えを見つけられませんでした。"),
    ).toBeInTheDocument();
    expect(screen.queryByText(OFF_TOPIC)).not.toBeInTheDocument();
    expect(screen.queryByText("関連して分かったこと")).not.toBeInTheDocument();
  });

  it("(b) answered が無い応答は grounded と同じ値として表示する", async () => {
    const user = userEvent.setup();
    await showResult(
      user,
      makeStructuredView({ ...RESULT_STATE, answer: groundedAnswer }),
    );

    expect(groundedAnswer).not.toHaveProperty("answered");
    expect(screen.queryByText(OFF_TOPIC)).not.toBeInTheDocument();
    expect(screen.queryByText("関連して分かったこと")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解決しました" })).toHaveClass(
      "primary",
    );
  });

  it("(c) おかしいかもしれない件でも、答えていないときは同じ見せ方で、担当者に送ったことも示す", async () => {
    const user = userEvent.setup();
    await showResult(
      user,
      makeStructuredView({
        type: LOOKS_WRONG,
        ...HANDLING_STATE,
        handedOverAt: HANDED_OVER_AT,
        answer: relatedAnswer,
      }),
      { type: LOOKS_WRONG },
    );

    expect(
      screen.getByText(
        "いまの動きを調べました。この内容を添えて担当者に送りました。",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(OFF_TOPIC)).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "関連して分かったこと" }),
    ).toBeInTheDocument();
    // 解決の判定は求めない
    expect(
      screen.queryByRole("button", { name: "解決しました" }),
    ).not.toBeInTheDocument();
    expectNoInternalWords();
  });
});
