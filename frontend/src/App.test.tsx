import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import {
  FakeStream,
  groundedAnswer,
  installFakeFetch,
  makeStructuredView,
  makeView,
  ungroundedAnswer,
  type RecordedCall,
} from "./test/fakes.ts";

afterEach(() => {
  vi.unstubAllGlobals();
});

/** 入力画面で発話を送信する。 */
async function submitUtterance(
  user: ReturnType<typeof userEvent.setup>,
  text = "求人の掲載期限は何日まで延長できますか？",
) {
  await user.type(await screen.findByLabelText("困りごと"), text);
  await user.click(screen.getByRole("button", { name: "送信する" }));
}

/** 質問なしの仕様確認で、整形確認まで進めて確定し 0次回答の生成待ちにする。 */
async function goToAnswering(user: ReturnType<typeof userEvent.setup>) {
  await submitUtterance(user);
  await user.click(await screen.findByRole("button", { name: "次へ進む" }));
  await user.click(
    await screen.findByRole("button", { name: "この内容で進める" }),
  );
}

/** 指定の呼び出しを探す。 */
function findCall(calls: RecordedCall[], method: string, path: string) {
  return calls.find((c) => c.method === method && c.path === path);
}

describe("問い合わせフォームの流れ", () => {
  it("(a) 仕様確認の正常系を入力から「解決した」まで通せる", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          questions: [
            { id: "q1", text: "どの画面の話ですか", options: ["一覧", "詳細"] },
            { id: "q2", text: "いつ頃から気になっていますか", options: [] },
          ],
        }),
      },
      "POST /api/inquiries/inq-1/answers": { body: makeStructuredView() },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ step: "ANSWERING" }),
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

    await submitUtterance(user);
    expect(
      await screen.findByText("仕様の確認・相談として扱います"),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "詳細" }));
    await user.type(
      screen.getByLabelText("いつ頃から気になっていますか（自由記述）"),
      "先週",
    );
    await user.click(screen.getByRole("button", { name: "回答して次へ進む" }));

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers")?.body,
    ).toEqual({
      answers: [
        { questionId: "q1", answer: "詳細" },
        { questionId: "q2", answer: "先週" },
      ],
    });

    const goal = await screen.findByLabelText("やりたいこと（必須）");
    expect(goal).toHaveValue("掲載期限を延ばせる日数を知りたい");
    await user.click(screen.getByRole("button", { name: "この内容で進める" }));
    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/structured")?.body,
    ).toEqual({
      targetSystem: "求人管理画面",
      goal: "掲載期限を延ばせる日数を知りたい",
      whatHappened: null,
      expectedResult: null,
      urgency: "NORMAL",
    });

    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    expect(stream.latest.id).toBe("inq-1");
    act(() => stream.latest.handlers.onProgress("コードを検索しています"));
    act(() => stream.latest.handlers.onProgress("回答をまとめています"));
    const items = within(
      screen.getByRole("list", { name: "0次回答の進捗" }),
    ).getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual([
      "コードを検索しています",
      "回答をまとめています",
    ]);

    act(() =>
      stream.latest.handlers.onDone(
        makeView({ step: "ANSWERED", answer: groundedAnswer }),
      ),
    );
    expect(
      await screen.findByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
    expect(stream.latest.closed).toBe(true);
    // 出典とメモは折りたたみ（初期は閉じている）
    const citations = screen.getByText("出典（1件）").closest("details");
    expect(citations).not.toHaveAttribute("open");
    expect(
      screen.getByText("技術的な詳細（担当者向けメモ）").closest("details"),
    ).not.toHaveAttribute("open");

    await user.click(screen.getByRole("button", { name: "解決した" }));
    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/resolution")?.body,
    ).toEqual({ resolved: true });
    expect(
      await screen.findByRole("heading", { name: "解決として記録しました" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "新しい問い合わせを書く" }),
    );
    expect(await screen.findByLabelText("困りごと")).toHaveValue("");
  });

  it("(b) 種別を変えると質問が差し替わる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          questions: [{ id: "q1", text: "どの画面の話ですか", options: [] }],
        }),
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

    await submitUtterance(user);
    expect(await screen.findByText("どの画面の話ですか")).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "作業依頼" }));

    expect(
      findCall(calls, "PUT", "/api/inquiries/inq-1/category")?.body,
    ).toEqual({ category: "WORK_REQUEST" });
    expect(
      await screen.findByText("作業依頼として扱います"),
    ).toBeInTheDocument();
    expect(screen.getByText("いつまでに必要ですか")).toBeInTheDocument();
    expect(screen.queryByText("どの画面の話ですか")).not.toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "作業依頼" })).toBeChecked();
  });

  it("(c) 「わからない」は answer: null で送られる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          questions: [
            { id: "q1", text: "どの画面の話ですか", options: ["一覧", "詳細"] },
            { id: "q2", text: "いつ頃から気になっていますか", options: [] },
          ],
        }),
      },
      "POST /api/inquiries/inq-1/answers": { body: makeStructuredView() },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await submitUtterance(user);
    await screen.findByText("どの画面の話ですか");
    const submit = screen.getByRole("button", { name: "回答して次へ進む" });
    expect(submit).toBeDisabled();

    await user.click(screen.getByRole("radio", { name: "わからない" }));
    await user.click(screen.getByRole("checkbox", { name: "わからない" }));
    expect(submit).toBeEnabled();
    await user.click(submit);

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/answers")?.body,
    ).toEqual({
      answers: [
        { questionId: "q1", answer: null },
        { questionId: "q2", answer: null },
      ],
    });
  });

  it("(d) 根拠なしの回答は特定できなかったと示し、技術メモを折りたたみで見せる", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/answers": { body: makeStructuredView() },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ step: "ANSWERING" }),
      },
    });
    render(<App subscribe={stream.subscribe} />);

    await goToAnswering(user);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onDone(
        makeView({ step: "ANSWERED", answer: ungroundedAnswer }),
      ),
    );

    expect(
      await screen.findByText("一次情報からは答えを特定できませんでした"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/^出典（/)).not.toBeInTheDocument();
    const memo = screen
      .getByText("技術的な詳細（担当者向けメモ）")
      .closest("details");
    expect(memo).not.toHaveAttribute("open");
    expect(within(memo!).getByText("該当する仕様の記述")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "解決した" }),
    ).toBeInTheDocument();
  });

  it("(e) 作業依頼は整形確定で完了画面になり、0次回答は作らない", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    installFakeFetch({
      "POST /api/inquiries": {
        status: 201,
        body: makeView({
          categoryByAi: "WORK_REQUEST",
          category: "WORK_REQUEST",
          questions: [
            { id: "wr-what", text: "何をしてほしいですか", options: [] },
          ],
        }),
      },
      "POST /api/inquiries/inq-1/answers": {
        body: makeStructuredView({ category: "WORK_REQUEST" }),
      },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeView({
          category: "WORK_REQUEST",
          step: "CLOSED",
          outcome: "FILED",
        }),
      },
    });
    render(<App subscribe={stream.subscribe} />);

    await submitUtterance(user, "掲載期限を一括で延長してほしいです");
    await user.type(
      await screen.findByLabelText("何をしてほしいですか（自由記述）"),
      "一括延長",
    );
    await user.click(screen.getByRole("button", { name: "回答して次へ進む" }));
    await user.click(
      await screen.findByRole("button", { name: "この内容で進める" }),
    );

    expect(
      await screen.findByRole("heading", { name: "担当者に送りました" }),
    ).toBeInTheDocument();
    expect(stream.subscriptions).toHaveLength(0);
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
  });

  it("(f) 障害は案内が目立つ形で表示される", async () => {
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

    await submitUtterance(user, "求人管理画面が落ちています");
    await user.click(await screen.findByRole("radio", { name: "はい" }));
    await user.click(screen.getByRole("button", { name: "回答して次へ進む" }));

    const guidance = await screen.findByRole("region", { name: "障害の案内" });
    expect(guidance).toHaveTextContent(
      "障害対応窓口（内線1234）へ至急ご連絡ください。",
    );
    expect(
      screen.getByRole("heading", { name: "障害として案内します" }),
    ).toBeInTheDocument();
  });

  it("(g) ストリームの failed で再試行と「今すぐ担当者に送る」が出る", async () => {
    const user = userEvent.setup();
    const stream = new FakeStream();
    installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/answers": { body: makeStructuredView() },
      "PUT /api/inquiries/inq-1/structured": {
        body: makeStructuredView({ step: "ANSWERING" }),
      },
    });
    render(<App subscribe={stream.subscribe} />);

    await goToAnswering(user);
    await waitFor(() => expect(stream.subscriptions).toHaveLength(1));
    act(() =>
      stream.latest.handlers.onFailed({
        code: "AI_UNAVAILABLE",
        message: "model down",
      }),
    );

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("いまAIを利用できません");
    expect(alert).not.toHaveTextContent("model down");
    expect(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    ).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "もう一度試す" }));
    await waitFor(() => expect(stream.subscriptions).toHaveLength(2));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    act(() =>
      stream.latest.handlers.onDone(
        makeView({ step: "ANSWERED", answer: groundedAnswer }),
      ),
    );
    expect(
      await screen.findByText("掲載期限は最大90日まで延長できます。"),
    ).toBeInTheDocument();
  });

  it("(h) 「今すぐ担当者に送る」は send-now を呼び完了画面になる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/send-now": {
        body: makeView({
          step: "CLOSED",
          outcome: "FILED",
          sentImmediately: true,
        }),
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    // 問い合わせの作成前は出ない
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
    await submitUtterance(user);
    await user.click(
      await screen.findByRole("button", { name: "今すぐ担当者に送る" }),
    );

    expect(
      findCall(calls, "POST", "/api/inquiries/inq-1/send-now"),
    ).toBeDefined();
    expect(
      await screen.findByText(
        "いまの内容のまま担当者に送りました。担当者が内容を確認します。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
  });
});

describe("エラーとモック表示", () => {
  it("HTTP の AI_REFUSED は平易に伝え、今すぐ担当者に送るへ誘導する", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "POST /api/inquiries": { status: 201, body: makeView() },
      "POST /api/inquiries/inq-1/answers": {
        status: 422,
        body: { code: "AI_REFUSED", message: "refused" },
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await submitUtterance(user);
    await user.click(await screen.findByRole("button", { name: "次へ進む" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("AIがこの内容には回答できませんでした");
    expect(alert).toHaveTextContent("今すぐ担当者に送る");
    // 通信が終わったので操作できる
    expect(screen.getByRole("button", { name: "次へ進む" })).toBeEnabled();
  });

  it("判定のAIエラー（inquiryId つき）では発話を残し、今すぐ担当者に送るでその id に送れる", async () => {
    const user = userEvent.setup();
    const calls = installFakeFetch({
      "POST /api/inquiries": {
        status: 503,
        body: { code: "AI_UNAVAILABLE", message: "down", inquiryId: "saved-9" },
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

    await submitUtterance(user, "つらい");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("いまAIを利用できません");
    expect(screen.getByLabelText("困りごと")).toHaveValue("つらい");

    await user.click(
      screen.getByRole("button", { name: "今すぐ担当者に送る" }),
    );
    expect(
      findCall(calls, "POST", "/api/inquiries/saved-9/send-now"),
    ).toBeDefined();
    expect(
      await screen.findByText(
        "いまの内容のまま担当者に送りました。担当者が内容を確認します。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
  });

  it("inquiryId のないエラー（VALIDATION）では今すぐ担当者に送るを出さない", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "POST /api/inquiries": {
        status: 400,
        body: { code: "VALIDATION", message: "bad" },
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await submitUtterance(user);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(
      screen.queryByRole("button", { name: "今すぐ担当者に送る" }),
    ).not.toBeInTheDocument();
  });

  it("その他のエラーは汎用メッセージで、サーバーの文言は出さない", async () => {
    const user = userEvent.setup();
    installFakeFetch({
      "POST /api/inquiries": {
        status: 500,
        body: { code: "INTERNAL", message: "stacktrace here" },
      },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await submitUtterance(user);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("うまく処理できませんでした");
    expect(alert).not.toHaveTextContent("stacktrace here");
  });

  it("通信中は送信ボタンが無効になり二重送信できない", async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    const calls = installFakeFetch({});
    const fake = vi.mocked(fetch);
    const base = fake.getMockImplementation()!;
    fake.mockImplementation(async (input, init) => {
      if (String(input) === "/api/inquiries") {
        await gate;
        calls.push({ method: "POST", path: "/api/inquiries", body: undefined });
        return new Response(JSON.stringify(makeView()), { status: 201 });
      }
      return base(input, init);
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    await submitUtterance(user);
    expect(screen.getByRole("button", { name: "送信する" })).toBeDisabled();

    release();
    expect(
      await screen.findByText("仕様の確認・相談として扱います"),
    ).toBeInTheDocument();
  });

  it("モック時はバッジと例文ボタンが出て、押すと入力欄に例文が入る", async () => {
    const user = userEvent.setup();
    installFakeFetch({ "GET /api/config": { body: { mock: true } } });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByText("モック")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "[根拠なし]" }));
    expect(screen.getByLabelText("困りごと")).toHaveValue(
      "求人の掲載期限は、何日まで延長できますか？ [根拠なし]",
    );
    // 種別4つ + タグ7つ
    const bar = screen.getByRole("region", { name: "モックのシナリオ" });
    expect(within(bar).getAllByRole("button")).toHaveLength(11);
  });

  it("config の取得に失敗しても非モックとして描画される", async () => {
    installFakeFetch({
      "GET /api/config": { status: 500, body: { code: "X", message: "x" } },
    });
    render(<App subscribe={new FakeStream().subscribe} />);

    expect(await screen.findByLabelText("困りごと")).toBeInTheDocument();
    expect(screen.queryByText("モック")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
