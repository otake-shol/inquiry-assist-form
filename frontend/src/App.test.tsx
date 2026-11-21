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
import App from "./App.tsx";
import type { Question } from "./api/types.ts";
import {
  FakeStream,
  groundedAnswer,
  installFakeFetch,
  makeStructuredView,
  makeView,
  ungroundedAnswer,
  type RecordedCall,
} from "./test/fakes.ts";

type User = ReturnType<typeof userEvent.setup>;

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";
const FORBIDDEN = /0次回答|種別|判定|整形|起票|出典/;
const SUMMARY = "求人の掲載期限を、何日まで延ばせるかを知りたい。";

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
const Q_KIND: Question = {
  id: "q3",
  text: "困っているのはどちらですか",
  options: ["表示", "操作"],
};

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
});

/** 入力して送信する。 */
async function send(user: User, text = "求人の掲載期限を知りたいです") {
  await user.type(await screen.findByLabelText(INPUT_LABEL), text);
  await user.click(screen.getByRole("button", { name: "送信する" }));
}

/** 指定の呼び出しを探す。 */
function findCall(calls: RecordedCall[], method: string, path: string) {
  return calls.find((c) => c.method === method && c.path === path);
}

/** 画面に内部用語が出ていないことを確かめる。 */
function expectNoInternalWords() {
  expect(document.body.textContent).not.toMatch(FORBIDDEN);
}

/** 質問なしで言い直しまで進む API の組み合わせ。 */
function restatementRoutes(structured = makeStructuredView()) {
  return {
    "POST /api/inquiries": { status: 201, body: makeView() },
    "POST /api/inquiries/inq-1/answers": { body: structured },
  };
}

describe("会話の流れ", () => {
  it("(a)(n) 1問ずつ答えて言い直しに「合っています」から「解決しました」まで通せ、内部用語が出ない", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          utterance: "求人の掲載期限を知りたいです",
          questions: [Q_SCREEN, Q_WHEN],
        }),
      },
      "POST /api/inquiries/inq-1/answers": {
        body: makeStructuredView({ questions: [Q_SCREEN, Q_WHEN] }),
      },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({
          step: "ANSWERING",
          questions: [Q_SCREEN, Q_WHEN],
        }),
      },
      "POST /api/inquiries/inq-1/resolution": {
        body: makeView({
          step: "CLOSED",
          outcome: "SELF_RESOLVED",
          answer: groundedAnswer,
        }),
      },
    });
    render(<App subscribe={stream.subscribe} />);
    expectNoInternalWords();

    await send(user, "求人の掲載期限を知りたいです");
    // 本人の言葉は残り、受け取りと質問の数が示される
    expect(
      await screen.findByText("仕様の確認・相談として受け取りました"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("求人の掲載期限を知りたいです"),
    ).toBeInTheDocument();
    expect(screen.getByText("2つだけ教えてください")).toBeInTheDocument();
    expectNoInternalWords();

    // 1問ずつ出る
    expect(screen.queryByText(Q_WHEN.text)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "詳細" }));
    await user.type(await screen.findByLabelText(Q_WHEN.text), "先週");
    await user.click(screen.getByRole("button", { name: "次へ" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers")?.body,
    ).toEqual({
      answers: [
        { questionId: "q1", answer: "詳細" },
        { questionId: "q2", answer: "先週" },
      ],
    });

    // 言い直し。答えた質問は1行で残る
    expect(
      await screen.findByText("つまり、こういうことですね。"),
    ).toBeInTheDocument();
    expect(screen.getByText(SUMMARY)).toBeInTheDocument();
    expect(screen.getByText("先週")).toBeInTheDocument();
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
    expectNoInternalWords();

    // 調べた結果
    act(() =>
      stream.latest.handlers.onDone(
        makeView({
          step: "ANSWERED",
          answer: groundedAnswer,
          structured: makeStructuredView().structured,
          questions: [Q_SCREEN, Q_WHEN],
        }),
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
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(await screen.findByLabelText(INPUT_LABEL)).toHaveValue("");
    expect(screen.queryByText("調べた結果")).not.toBeInTheDocument();
  });

  it("(b) 答えた質問の行を押すと答え直せて、以降の回答は消える", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN, Q_KIND, Q_WHEN] }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    await user.click(await screen.findByRole("button", { name: "詳細" }));
    await user.click(await screen.findByRole("button", { name: "操作" }));
    // 3問目が出ている。1問目・2問目は要約の行
    expect(await screen.findByLabelText(Q_WHEN.text)).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: new RegExp(Q_SCREEN.text) }),
    );

    // 1問目が聞き直され、2問目の回答と3問目は消える
    expect(screen.getByRole("button", { name: "一覧" })).toBeInTheDocument();
    expect(screen.queryByLabelText(Q_WHEN.text)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: new RegExp(Q_KIND.text) }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("操作")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "一覧" }));
    // 2問目から聞き直す
    expect(
      await screen.findByRole("button", { name: "表示" }),
    ).toBeInTheDocument();
    // 最後まで答えるまで送信していない
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers"),
    ).toBeUndefined();
  });

  it("(c) 「わからない」は answer: null で送られる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN, Q_WHEN] }),
      },
      "POST /api/inquiries/inq-1/answers": { body: makeStructuredView() },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    await user.click(await screen.findByRole("button", { name: "わからない" }));
    // 自由記述の質問にも「わからない」がある
    await user.click(await screen.findByRole("button", { name: "わからない" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers")?.body,
    ).toEqual({
      answers: [
        { questionId: "q1", answer: null },
        { questionId: "q2", answer: null },
      ],
    });
  });

  it("(d) 質問が0個なら何も聞かずに言い直しまで進む", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch(restatementRoutes());
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);

    expect(
      await screen.findByText("つまり、こういうことですね。"),
    ).toBeInTheDocument();
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers")?.body,
    ).toEqual({ answers: [] });
    expect(screen.queryByText(/つだけ教えてください/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "前の質問に戻る" }),
    ).not.toBeInTheDocument();
  });

  it("(e) 「少し違います」の一言で作り直しを呼び、言い直しが差し替わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...restatementRoutes(),
      "POST /api/inquiries/inq-1/revision": {
        body: makeStructuredView({
          revisionsLeft: 1,
          structured: {
            ...makeStructuredView().structured!,
            summary: "消えたのは一昨日からで、理由を知りたい。",
          },
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
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
    const calls = installFakeFetch({
      ...restatementRoutes(makeStructuredView({ revisionsLeft: 0 })),
      "POST /api/inquiries/inq-1/send-now": {
        body: makeStructuredView({
          step: "CLOSED",
          outcome: "FILED",
          sentImmediately: true,
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
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
    const calls = installFakeFetch({
      ...restatementRoutes(),
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ step: "ANSWERING" }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
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

  it("(h) 「前の質問に戻る」は reopen を呼び、質問を最初から聞き直す", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN, Q_KIND] }),
      },
      "POST /api/inquiries/inq-1/answers": {
        body: makeStructuredView({ questions: [Q_SCREEN, Q_KIND] }),
      },
      "POST /api/inquiries/inq-1/reopen": {
        body: makeView({ questions: [Q_SCREEN, Q_KIND] }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    await user.click(await screen.findByRole("button", { name: "詳細" }));
    await user.click(await screen.findByRole("button", { name: "操作" }));
    await user.click(
      await screen.findByRole("button", { name: "前の質問に戻る" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/reopen"),
    ).toBeDefined();
    // 1問目から聞き直し、言い直しは消える
    expect(
      await screen.findByRole("button", { name: "一覧" }),
    ).toBeInTheDocument();
    expect(screen.getByText("2つだけ教えてください")).toBeInTheDocument();
    expect(
      screen.queryByText("つまり、こういうことですね。"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "表示" }),
    ).not.toBeInTheDocument();
  });

  it("(i) 受け取りの「変更」で内容を変えると質問が差し替わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN] }),
      },
      "PUT /api/inquiries/inq-1/category": {
        body: makeView({
          category: "WORK_REQUEST",
          questions: [
            { id: "wr-what", text: "何をしてほしいですか", options: [] },
            { id: "wr-when", text: "いつまでに必要ですか", options: [] },
            { id: "wr-target", text: "対象は何ですか", options: [] },
          ],
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    expect(await screen.findByText(Q_SCREEN.text)).toBeInTheDocument();
    // 選択肢は「変更」を押したときだけ出る
    expect(
      screen.queryByRole("button", { name: "作業の依頼" }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "変更" }));
    await user.click(screen.getByRole("button", { name: "作業の依頼" }));

    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/category")?.body,
    ).toEqual({ category: "WORK_REQUEST" });
    expect(
      await screen.findByText("作業の依頼として受け取りました"),
    ).toBeInTheDocument();
    expect(screen.getByText("3つだけ教えてください")).toBeInTheDocument();
    expect(screen.getByLabelText("何をしてほしいですか")).toBeInTheDocument();
    expect(screen.queryByText(Q_SCREEN.text)).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "仕様の確認・相談" }),
    ).not.toBeInTheDocument();
  });

  it("(j) 根拠なしの結果は見つからなかったと示し、担当者に送るが主のボタンになる", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    const calls = installFakeFetch({
      ...restatementRoutes(),
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ step: "ANSWERING" }),
      },
      "POST /api/inquiries/inq-1/resolution": {
        body: makeStructuredView({
          step: "CLOSED",
          outcome: "FILED",
          answer: ungroundedAnswer,
        }),
      },
    });
    render(<App subscribe={stream.subscribe} />);

    await send(user);
    await user.click(
      await screen.findByRole("button", { name: "合っています" }),
    );
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onDone(
        makeStructuredView({ step: "ANSWERED", answer: ungroundedAnswer }),
      ),
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
    expect(screen.getByRole("button", { name: "担当者に送る" })).toHaveClass(
      "primary",
    );

    await user.click(screen.getByRole("button", { name: "担当者に送る" }));
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution")?.body,
    ).toEqual({ resolved: false });
    expect(
      await screen.findByText(
        "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      ),
    ).toBeInTheDocument();
  });

  describe("(k) 終わりの文言", () => {
    const config = {
      body: {
        mock: false,
        filedGuidance: "担当者が内容を確認して連絡します。",
      },
    };

    it("作業の依頼は内容を添えて送ったことと送った後の案内を示す", async () => {
      const user = userEvent.setup();
      installFakeFetch({
        "GET /api/config": config,
        ...restatementRoutes(
          makeStructuredView({
            category: "WORK_REQUEST",
            categoryByAi: "WORK_REQUEST",
          }),
        ),
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({
            category: "WORK_REQUEST",
            step: "CLOSED",
            outcome: "FILED",
          }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await send(user);
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
      expectNoInternalWords();
    });

    it("障害は専用の窓口の案内と、目立つ形の案内文を示す", async () => {
      const user = userEvent.setup();
      installFakeFetch({
        "POST /api/inquiries": {
          status: 201,
          body: makeView({
            categoryByAi: "INCIDENT",
            category: "INCIDENT",
            questions: [
              {
                id: "incident-check",
                text: "いま業務やサービスが止まっていますか",
                options: ["はい", "いいえ"],
              },
            ],
          }),
        },
        "POST /api/inquiries/inq-1/answers": {
          body: makeView({
            category: "INCIDENT",
            step: "CLOSED",
            outcome: "INCIDENT_REDIRECTED",
            incidentGuidance: "障害対応窓口（内線1234）へ至急ご連絡ください。",
          }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await send(user, "求人管理画面が落ちています");
      expect(
        await screen.findByText("障害かもしれない件として受け取りました"),
      ).toBeInTheDocument();
      await user.click(await screen.findByRole("button", { name: "はい" }));

      expect(
        await screen.findByText("障害の連絡は専用の窓口で受け付けています。"),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("region", { name: "障害の案内" }),
      ).toHaveTextContent("障害対応窓口（内線1234）へ至急ご連絡ください。");
    });

    it("不具合かもしれない件は調べた結果を添えて送ったことを結果の上に示す", async () => {
      const user = userEvent.setup();
      const stream = new FakeStream();
      const bug = {
        category: "SUSPECTED_BUG",
        categoryByAi: "SUSPECTED_BUG",
      } as const;
      installFakeFetch({
        "GET /api/config": config,
        "POST /api/inquiries": { status: 201, body: makeView(bug) },
        "POST /api/inquiries/inq-1/answers": {
          body: makeStructuredView(bug),
        },
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({ ...bug, step: "ANSWERING" }),
        },
      });
      render(<App subscribe={stream.subscribe} />);

      await send(user, "件数がおかしい");
      expect(
        await screen.findByText("不具合かもしれない件として受け取りました"),
      ).toBeInTheDocument();
      await user.click(
        await screen.findByRole("button", { name: "合っています" }),
      );
      await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
      act(() =>
        stream.latest.handlers.onDone(
          makeStructuredView({
            ...bug,
            step: "CLOSED",
            outcome: "FILED",
            answer: groundedAnswer,
          }),
        ),
      );

      const notice = await screen.findByText(
        "いまの動きを調べました。この内容を添えて担当者に送りました。",
      );
      const resultHeading = screen.getByRole("heading", {
        name: "調べた結果",
      });
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
    });
  });

  describe("(l) エラーの導線", () => {
    /** 調べている最中まで進める。 */
    async function reachResearching(user: User, stream: FakeStream) {
      installFakeFetch({
        ...restatementRoutes(),
        "PUT /api/inquiries/inq-1/structured": {
          body: makeStructuredView({ step: "ANSWERING" }),
        },
      });
      render(<App subscribe={stream.subscribe} />);
      await send(user);
      await user.click(
        await screen.findByRole("button", { name: "合っています" }),
      );
      await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    }

    it("進捗ストリームの failed で再試行と「今すぐ担当者に送る」が出る", async () => {
      const user = userEvent.setup();
      const stream = new FakeStream();
      await reachResearching(user, stream);
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
      expect(
        screen.getByRole("button", { name: "今すぐ担当者に送る" }),
      ).toBeEnabled();

      await user.click(screen.getByRole("button", { name: "もう一度調べる" }));
      await waitFor(() => expect(stream.subscriptions).toHaveLength(2));
      expect(stream.subscriptions[0].closed).toBe(true);
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("拒否された場合は自動では調べられなかったと伝える", async () => {
      const user = userEvent.setup();
      const stream = new FakeStream();
      await reachResearching(user, stream);
      act(() =>
        stream.latest.handlers.onFailed({ code: "AI_REFUSED", message: "x" }),
      );

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "この内容は自動では調べられませんでした。担当者に直接送れます。",
      );
    });

    it("判定のAIエラー（inquiryId つき）では発話を残し、その id に今すぐ送れる", async () => {
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
        "POST /api/inquiries/saved-9/send-now": {
          body: makeView({
            id: "saved-9",
            categoryByAi: null,
            category: null,
            step: "CLOSED",
            outcome: "FILED",
            sentImmediately: true,
          }),
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await send(user, "つらい");
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "いま自動の調査を使えません。担当者に直接送れます。",
      );
      expect(screen.getByLabelText(INPUT_LABEL)).toHaveValue("つらい");

      await user.click(
        screen.getByRole("button", { name: "今すぐ担当者に送る" }),
      );

      expect(
        findCall(calls, "POST", "/api/inquiries/saved-9/send-now"),
      ).toBeDefined();
      expect(
        await screen.findByText(
          "担当者に送りました。書いていただいた内容をそのまま添えています。",
        ),
      ).toBeInTheDocument();
      // 受け取った内容が判定できていなくても壊れない
      expect(screen.getByText("内容を受け取りました")).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
      ).not.toBeInTheDocument();
    });

    it("inquiryId のないエラーでは今すぐ担当者に送るを出さず、汎用の文言にする", async () => {
      const user = userEvent.setup();
      installFakeFetch({
        "POST /api/inquiries": {
          status: 400,
          body: { code: "VALIDATION", message: "stacktrace here" },
        },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await send(user);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("うまく処理できませんでした");
      expect(alert).not.toHaveTextContent("stacktrace here");
      expect(
        screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
      ).not.toBeInTheDocument();
    });

    it("回答の送信に失敗したら「もう一度送る」で送り直せる", async () => {
      const user = userEvent.setup();
      let attempts = 0;
      const calls = installFakeFetch({
        "POST /api/inquiries": {
          status: 201,
          body: makeView({ questions: [Q_SCREEN] }),
        },
        "POST /api/inquiries/inq-1/answers": () =>
          ++attempts === 1
            ? { status: 503, body: { code: "AI_UNAVAILABLE", message: "x" } }
            : { body: makeStructuredView({ questions: [Q_SCREEN] }) },
      });
      render(<App subscribe={new FakeStream().subscribe} />);

      await send(user);
      await user.click(await screen.findByRole("button", { name: "詳細" }));
      await screen.findByRole("alert");
      await user.click(screen.getByRole("button", { name: "もう一度送る" }));

      expect(
        await screen.findByText("つまり、こういうことですね。"),
      ).toBeInTheDocument();
      const bodies = calls
        .filter((c) => c.path === "/api/inquiries/inq-1/answers")
        .map((c) => c.body);
      expect(bodies).toEqual([
        { answers: [{ questionId: "q1", answer: "詳細" }] },
        { answers: [{ questionId: "q1", answer: "詳細" }] },
      ]);
    });
  });

  it("(m) URL の ?text= が入力欄に入り、自動では送信しない", async () => {
    window.history.replaceState(
      {},
      "",
      "/?text=%E6%8E%B2%E8%BC%89%E6%9C%9F%E9%99%90",
    );
    const calls = installFakeFetch({});
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByLabelText(INPUT_LABEL)).toHaveValue("掲載期限");
    expect(calls.filter((c) => c.path === "/api/inquiries")).toHaveLength(0);
  });

  it("「今すぐ担当者に送る」は作成後から終わりまで下部にあり、send-now を呼んで完了になる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ questions: [Q_SCREEN] }),
      },
      "POST /api/inquiries/inq-1/send-now": {
        body: makeView({
          step: "CLOSED",
          outcome: "FILED",
          sentImmediately: true,
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
    await send(user);
    expect(
      await screen.findByText("待たずに人に見てもらいたいときはこちら。"),
    ).toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/send-now"),
    ).toBeDefined();
    expect(
      await screen.findByText(
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
  });
});

describe("入力とモック表示", () => {
  it("通信中は送信ボタンが無効になり二重送信できない", async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    installFakeFetch({});
    const fake = vi.mocked(fetch);
    const base = fake.getMockImplementation()!;
    fake.mockImplementation(async (input, init) => {
      if (String(input) === "/api/inquiries") {
        await gate;
        return new Response(
          JSON.stringify(makeView({ questions: [Q_SCREEN] })),
          { status: 201 },
        );
      }
      return base(input, init);
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    expect(screen.getByRole("button", { name: "送信する" })).toBeDisabled();

    release();
    expect(await screen.findByText(Q_SCREEN.text)).toBeInTheDocument();
  });

  it("モック時はバッジと例文ボタンが出て、押すと入力欄に例文が入る", async () => {
    const user = userEvent.setup();
    installFakeFetch({ "GET /api/config": { body: { mock: true } } });
    render(<App subscribe={new FakeStream().subscribe} />);

    const badge = await screen.findByText("モック");
    const details = badge.closest("details");
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute("open");
    await user.click(screen.getByText("例文を試す"));
    expect(details).toHaveAttribute("open");
    await user.click(screen.getByRole("button", { name: "[根拠なし]" }));
    expect(screen.getByLabelText(INPUT_LABEL)).toHaveValue(
      "求人の掲載期限は、何日まで延長できますか？ [根拠なし]",
    );
    const bar = screen.getByRole("region", { name: "モックのシナリオ" });
    expect(within(bar).getAllByRole("button")).toHaveLength(14);
    expect(within(bar).queryByText("種別の例")).not.toBeInTheDocument();
  });

  it("config の取得に失敗しても非モックとして描画される", async () => {
    installFakeFetch({
      "GET /api/config": { status: 500, body: { code: "X", message: "x" } },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByLabelText(INPUT_LABEL)).toBeInTheDocument();
    expect(screen.queryByText("モック")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("対象システム", () => {
  const systems = [
    { id: "job-admin", label: "求人管理画面" },
    { id: "billing", label: "請求管理" },
  ];
  const withSystems = { "GET /api/config": { body: { mock: false, systems } } };
  const bug = {
    category: "SUSPECTED_BUG",
    categoryByAi: "SUSPECTED_BUG",
  } as const;

  /** 作成の本文を取り出す。 */
  const createBody = (calls: RecordedCall[]) =>
    findCall(calls, "POST", "/api/inquiries")?.body;

  it("(a) 選んで送ると systemId が送られ、もう一度押すと解除される", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...withSystems,
      "POST /api/inquiries": { status: 201, body: makeView() },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    const job = await screen.findByRole("button", { name: "求人管理画面" });
    expect(job).toHaveAttribute("aria-pressed", "false");
    await user.click(job);
    expect(job).toHaveAttribute("aria-pressed", "true");
    // もう一度押すと解除。別のものを選べば付け替わる
    await user.click(job);
    expect(job).toHaveAttribute("aria-pressed", "false");
    await user.click(job);
    await user.click(screen.getByRole("button", { name: "請求管理" }));
    expect(job).toHaveAttribute("aria-pressed", "false");
    await user.click(screen.getByRole("button", { name: "請求管理" }));
    await user.click(job);
    await user.type(screen.getByLabelText(INPUT_LABEL), "掲載期限");
    await user.click(screen.getByRole("button", { name: "送信する" }));

    expect(createBody(calls)).toEqual({
      utterance: "掲載期限",
      systemId: "job-admin",
    });
  });

  it("(a) 未選択と「わからない」はどちらも systemId: null で送られる", async () => {
    for (const choose of [false, true]) {
      const user = userEvent.setup();
      const calls = installFakeFetch({
        ...withSystems,
        "POST /api/inquiries": { status: 201, body: makeView() },
      });
      const { unmount } = render(
        <App subscribe={new FakeStream().subscribe} />,
      );

      const unknown = await screen.findByRole("button", { name: "わからない" });
      if (choose) {
        await user.click(unknown);
        expect(unknown).toHaveAttribute("aria-pressed", "true");
      }
      await user.type(screen.getByLabelText(INPUT_LABEL), "掲載期限");
      await user.click(screen.getByRole("button", { name: "送信する" }));

      await waitFor(() =>
        expect(createBody(calls)).toEqual({
          utterance: "掲載期限",
          systemId: null,
        }),
      );
      unmount();
    }
  });

  it("(b) systems が空なら選択を出さない", async () => {
    installFakeFetch({
      "GET /api/config": { body: { mock: false, systems: [] } },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByLabelText(INPUT_LABEL)).toBeInTheDocument();
    expect(
      screen.queryByRole("group", { name: /どのシステムの話ですか/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "わからない" }),
    ).not.toBeInTheDocument();
  });

  it("(c) 受け取りに対象システムが出て、推測のときは推測と分かる", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      ...withSystems,
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          ...bug,
          system: systems[0],
          systemGuessed: true,
          questions: [Q_SCREEN],
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    expect(
      await screen.findByText(
        "求人管理画面の話だと推測しました。不具合かもしれない件として受け取りました",
      ),
    ).toBeInTheDocument();
  });

  it("(d) 「変更」から対象システムを直すと PUT …/system が呼ばれ、質問が差し替わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...withSystems,
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          ...bug,
          system: systems[0],
          systemGuessed: true,
          questions: [Q_SCREEN, Q_KIND],
        }),
      },
      "PUT /api/inquiries/inq-1/system": {
        body: makeView({
          ...bug,
          system: systems[1],
          systemGuessed: false,
          questions: [Q_KIND],
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    await screen.findByText(Q_SCREEN.text);
    await user.click(screen.getByRole("button", { name: "変更" }));
    const panel = screen.getByRole("group", { name: "どのシステムの話ですか" });
    // 推測された値は「選択済み」の扱いにならない
    expect(
      within(panel).getByRole("button", { name: "求人管理画面" }),
    ).toHaveAttribute("aria-pressed", "false");
    await user.click(within(panel).getByRole("button", { name: "請求管理" }));

    expect(findCall(calls, "PUT", "/api/inquiries/inq-1/system")?.body).toEqual(
      {
        systemId: "billing",
      },
    );
    expect(
      await screen.findByText(
        "請求管理の、不具合かもしれない件として受け取りました",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(Q_SCREEN.text)).not.toBeInTheDocument();
    expect(screen.getByText("1つだけ教えてください")).toBeInTheDocument();
  });

  it("(d) 「わからない」に戻すと systemId: null で直せる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      ...withSystems,
      "POST /api/inquiries": {
        status: 201,
        body: makeView({ ...bug, system: systems[0], questions: [Q_SCREEN] }),
      },
      "PUT /api/inquiries/inq-1/system": {
        body: makeView({ ...bug, system: null, questions: [Q_SCREEN, Q_KIND] }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await send(user);
    expect(
      await screen.findByText(
        "求人管理画面の、不具合かもしれない件として受け取りました",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "変更" }));
    const panel = screen.getByRole("group", { name: "どのシステムの話ですか" });
    await user.click(within(panel).getByRole("button", { name: "わからない" }));

    expect(findCall(calls, "PUT", "/api/inquiries/inq-1/system")?.body).toEqual(
      {
        systemId: null,
      },
    );
    expect(
      await screen.findByText("不具合かもしれない件として受け取りました"),
    ).toBeInTheDocument();
  });
});

describe("調査の結果と説明の失敗", () => {
  /** 確定して調べている最中まで進める。`researching` はそのときの PUT の応答。 */
  async function reachResearching(
    user: User,
    stream: FakeStream,
    researching = makeStructuredView({ step: "ANSWERING" }),
  ) {
    installFakeFetch({
      ...restatementRoutes(),
      "PUT /api/inquiries/inq-1/structured": { body: researching },
    });
    render(<App subscribe={stream.subscribe} />);
    await send(user);
    await user.click(
      await screen.findByRole("button", { name: "合っています" }),
    );
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
  }

  /** 結果まで進めて詳しい内容を返す。 */
  async function showResult(answer: typeof groundedAnswer) {
    const user = userEvent.setup();
    const stream = new FakeStream();
    await reachResearching(user, stream);
    act(() =>
      stream.latest.handlers.onDone(
        makeStructuredView({ step: "ANSWERED", answer }),
      ),
    );
    await screen.findByRole("heading", { name: "調べた結果" });
    return screen.getByText("担当者向けの詳しい内容").closest("details")!;
  }

  it("(a) 根拠を確かめられなかったことは、あるときだけ分かったこととは別の見出しで出る", async () => {
    const memo = await showResult({
      ...groundedAnswer,
      memo: {
        ...groundedAnswer.memo,
        unconfirmed: ["別の画面でも同じ制限がかかっている"],
      },
    });

    expect(within(memo).getByText("分かったこと")).toBeInTheDocument();
    expect(
      within(memo).getByText("根拠を確かめられなかったこと"),
    ).toBeInTheDocument();
    expect(
      within(memo).getByText("別の画面でも同じ制限がかかっている"),
    ).toBeInTheDocument();
    expect(
      within(memo).getByText(
        "調査で挙がりましたが、資料で裏づけを取れていません。",
      ),
    ).toBeInTheDocument();
    // 主の説明には影響しない
    expect(
      screen.getByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expect(memo).not.toHaveAttribute("open");
    expectNoInternalWords();
  });

  it("(a) unconfirmed が空でも、無い古い応答でも見出しは出ない", async () => {
    for (const unconfirmed of [[], undefined]) {
      const { unconfirmed: _drop, ...rest } = groundedAnswer.memo;
      void _drop;
      const memo = await showResult({
        ...groundedAnswer,
        memo: unconfirmed === undefined ? rest : { ...rest, unconfirmed },
      });
      expect(
        within(memo).queryByText("根拠を確かめられなかったこと"),
      ).not.toBeInTheDocument();
      expect(
        within(memo).queryByText(/裏づけを取れていません/),
      ).not.toBeInTheDocument();
      cleanup();
    }
  });

  it("(d) 説明だけが失敗しても failed で再試行と今すぐ担当者に送るが出る", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    await reachResearching(user, stream);
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
    expect(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    ).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "もう一度調べる" }));
    await waitFor(() => expect(stream.subscriptions).toHaveLength(2));
  });

  it("(e) ANSWERING の間は answer が入った応答でも調べている表示を保ち、結果を描かない", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    await reachResearching(
      user,
      stream,
      makeStructuredView({ step: "ANSWERING", answer: groundedAnswer }),
    );

    expect(
      screen.getByRole("heading", { name: "調べています" }),
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
