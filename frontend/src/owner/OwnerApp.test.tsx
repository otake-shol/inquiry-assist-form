import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import { LANGUAGE_STORAGE_KEY } from "../i18n/language.ts";
import { initialSituationOf } from "../operator/consultations.ts";
import {
  CONSULTATION_STATE,
  FakeOperatorStream,
  installFakeFetch,
  makeConfig,
  makeConsultation,
  makeDetail,
  makeOwnerItem,
  makePrecedent,
  type RecordedCall,
  type RouteResult,
} from "../test/fakes.ts";

beforeEach(() => {
  window.history.replaceState({}, "", "/owner");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

const DECISION_PATH = "/api/owner/inquiries/op-1/decision";

/** 相談中の件の詳細（材料つき）。 */
const consultingDetail = (overrides: Parameters<typeof makeDetail>[0] = {}) =>
  makeDetail({
    read: true,
    ...CONSULTATION_STATE,
    advice: {
      status: "DONE",
      judgment: {
        text: "期限ちょうどの扱いを確かめるとよい。",
        needsConsultation: true,
        consultationReason: "仕様の判断が要る",
        basedOn: ["期限切れで非表示になる"],
      },
      procedure: null,
    },
    consultations: [makeConsultation()],
    messages: [
      {
        id: "m1",
        from: "ASKER",
        kind: "NOTE",
        authorName: "本人",
        body: "昨日からです。",
        createdAt: "2026-11-21T06:00:00Z",
      },
    ],
    ...overrides,
  });

type Route =
  RouteResult | ((b: unknown, h: Record<string, string>) => RouteResult);

/**
 * 仕様の持ち主の画面を描く。
 * @param path 開くパス
 * @param routes 一覧、詳細、決定の API
 */
function openOwner(path: string, routes: Record<string, Route> = {}) {
  window.history.replaceState({}, "", path);
  const calls = installFakeFetch({
    "GET /api/config": { body: makeConfig() },
    "GET /api/owner/consultations": { body: { items: [makeOwnerItem()] } },
    "GET /api/owner/inquiries/op-1": { body: consultingDetail() },
    ...routes,
  });
  render(
    <Root
      subscribeOperator={new FakeOperatorStream().subscribe}
      subscribeAnswers={undefined}
    />,
  );
  return calls;
}

const callsTo = (calls: RecordedCall[], method: string, path: string) =>
  calls.filter((c) => c.method === method && c.path === path);

/** 詳細のパネルを開いた状態にして返す。 */
async function openDetail(routes: Record<string, Route> = {}) {
  const calls = openOwner("/owner", routes);
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
  );
  const panel = await screen.findByRole("region", {
    name: "問い合わせの詳細",
  });
  await within(panel).findByText("材料");
  return { calls, user, panel };
}

/** 決めるフォームを、すべて埋める（決めた人は、すでに選ばれているものとする）。 */
async function fillDecision(
  user: ReturnType<typeof userEvent.setup>,
  panel: HTMLElement,
) {
  await user.selectOptions(
    within(panel).getByLabelText("決定の種類"),
    "意図どおり",
  );
  await user.type(
    within(panel).getByLabelText("決定の内容"),
    "意図した仕様です。",
  );
  await user.type(within(panel).getByLabelText("理由"), "締め切りのため。");
}

describe("仕様の持ち主の画面: 相談の一覧", () => {
  it("見出し「決定を待っている相談」の下に、種類、システムと機能、緊急度、相談した文章、相談した人と日時、相談先を出す。長く待たせている順（API の順）", async () => {
    openOwner("/owner", {
      "GET /api/owner/consultations": {
        body: {
          items: [
            makeOwnerItem({
              consultationId: "c1",
              inquiryId: "op-1",
              question: "古い相談。",
              function: { id: "posting", label: "求人の掲載" },
              urgency: "BLOCKED",
              to: ["仕様の持ち主（開発用）", "システムAの持ち主"],
            }),
            makeOwnerItem({
              consultationId: "c2",
              inquiryId: "op-2",
              question: "新しい相談。",
              askedBy: "別の担当者",
            }),
          ],
        },
      },
    });

    expect(
      await screen.findByRole("heading", { name: "仕様の持ち主", level: 1 }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "決定を待っている相談" }),
    ).toBeInTheDocument();
    const cards = await screen.findAllByRole("button", { name: /相談。/ });
    expect(cards.map((c) => c.textContent)).toHaveLength(2);
    expect(cards[0]).toHaveTextContent("古い相談。");
    expect(cards[1]).toHaveTextContent("新しい相談。");
    expect(cards[0]).toHaveTextContent("おかしいかもしれない");
    expect(cards[0]).toHaveTextContent("求人管理画面 / 求人の掲載");
    expect(cards[0]).toHaveTextContent("業務が止まっている");
    expect(cards[0]).toHaveTextContent(
      "相談先: 仕様の持ち主（開発用）、システムAの持ち主",
    );
    expect(cards[0]).toHaveTextContent("担当者（開発用）");
    expect(cards[0]).toHaveTextContent(/\d+\/\d+/);
    // 緊急度は、通常のときは出さない
    expect(cards[1]).not.toHaveTextContent("通常");
    expect(cards[1]).toHaveTextContent("別の担当者");
    expect(document.title).toBe("決定を待っている相談");
  });

  it("相談が1件もなければ、「決定を待っている相談は、ありません。」と出す", async () => {
    openOwner("/owner", {
      "GET /api/owner/consultations": { body: { items: [] } },
    });

    expect(
      await screen.findByText("決定を待っている相談は、ありません。"),
    ).toBeInTheDocument();
  });

  it("一覧の上の「更新」を押すと、一覧を取り直す", async () => {
    let items = [makeOwnerItem({ question: "最初の相談。" })];
    const calls = openOwner("/owner", {
      "GET /api/owner/consultations": () => ({ body: { items } }),
    });
    const user = userEvent.setup();
    await screen.findByRole("button", { name: /最初の相談/ });
    expect(callsTo(calls, "GET", "/api/owner/consultations")).toHaveLength(1);

    items = [
      ...items,
      makeOwnerItem({
        inquiryId: "op-2",
        consultationId: "c2",
        question: "後から来た相談。",
      }),
    ];
    await user.click(screen.getByRole("button", { name: "更新" }));

    expect(
      await screen.findByRole("button", { name: /後から来た相談/ }),
    ).toBeInTheDocument();
    expect(callsTo(calls, "GET", "/api/owner/consultations")).toHaveLength(2);
  });

  it("一覧を取れなかったら、エラーを出す", async () => {
    openOwner("/owner", {
      "GET /api/owner/consultations": {
        status: 500,
        body: { code: "INTERNAL", message: "x" },
      },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "読み込めませんでした",
    );
  });
});

describe("仕様の持ち主の画面: 詳細", () => {
  it("カードを押すと URL が /owner/{id} になり、相談、材料、決めるの順に出す。材料には担当者のパネルの読み取りの部分が出る", async () => {
    const { calls, panel } = await openDetail();

    expect(window.location.pathname).toBe("/owner/op-1");
    expect(callsTo(calls, "GET", "/api/owner/inquiries/op-1")).toHaveLength(1);
    // 相談
    const consult = within(panel)
      .getByRole("heading", { name: "相談" })
      .closest("section")!;
    expect(consult).toHaveTextContent("担当者（開発用）");
    expect(consult).toHaveTextContent("仕様の持ち主（開発用）");
    expect(consult).toHaveTextContent(
      "期限ちょうどの時刻の扱いは、意図した仕様でしょうか。",
    );
    // 材料
    for (const heading of [
      "元の発話",
      "言い直し",
      "入口の質問と答え",
      "調べた結果",
      "AI の案",
      "やり取り",
    ]) {
      expect(
        within(panel).getByRole("heading", { name: new RegExp(heading) }),
      ).toBeInTheDocument();
    }
    expect(
      within(panel).getByText("昨日まで見えていた求人が今日は見えません"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("昨日からです。")).toBeInTheDocument();
    expect(
      within(panel).getByText("掲載期限を過ぎると一覧から外れます。"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("期限ちょうどの扱いを確かめるとよい。"),
    ).toBeInTheDocument();
    expect(panel.querySelector(".op-badges")).toHaveTextContent("求人管理画面");
    // 順序: 相談 → 材料 → 決める
    const order = ["相談", "材料", "決める"].map((name) =>
      within(panel).getByRole("heading", { name, level: 3 }),
    );
    const follows = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(order[0], order[1])).toBe(true);
    expect(follows(order[1], order[2])).toBe(true);
  });

  it("担当者の操作（区間、置き直す、調べ直す、下書き、本人に送る、相談する、見送る、訳）は出さない", async () => {
    const { panel } = await openDetail({
      "GET /api/owner/inquiries/op-1": {
        body: consultingDetail({ language: "en" }),
      },
    });

    for (const text of [
      "区間を切り替える",
      "置き直す",
      "本人に送る",
      "相談する",
      "見送る",
      "状態の履歴",
    ]) {
      expect(
        within(panel).queryByText(text, {
          selector: "summary, legend, button",
        }),
      ).toBeNull();
    }
    for (const name of [
      /調べ直す|AI に調べさせる/,
      /下書きさせる/,
      /訳を見る/,
      /相談を取り下げる/,
      /聞き返す/,
    ]) {
      expect(within(panel).queryByRole("button", { name })).toBeNull();
    }
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    // 本人の言語が違っても、訳のボタンは出さない（担当者の操作）
    expect(within(panel).queryByText(/本人の言語/)).toBeNull();
  });

  it("URL で直接開いても、詳細を出す", async () => {
    openOwner("/owner/op-1");

    const panel = await screen.findByRole("region", {
      name: "問い合わせの詳細",
    });
    expect(await within(panel).findByText("材料")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
    ).toHaveAttribute("aria-current", "true");
  });

  it("見つからない id（404 でも、形式の崩れ 400 でも）は、一覧を出したうえで「見つかりませんでした」と示し、詳細は開かない", async () => {
    openOwner("/owner/zzz", {
      "GET /api/owner/inquiries/zzz": {
        status: 404,
        body: { code: "NOT_FOUND", message: "x" },
      },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "この問い合わせは見つかりませんでした",
    );
    expect(
      await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).toBeNull();
  });

  it("相談が終わっている件（決まった、取り下げた）は、材料だけを出し、決めるフォームは出さない", async () => {
    const { panel } = await openDetail({
      "GET /api/owner/inquiries/op-1": {
        body: consultingDetail({
          consultations: [makeConsultation({ status: "DECIDED" })],
        }),
      },
    });

    expect(
      within(panel).getByText(
        "この相談は、すでに決まったか、取り下げられています。",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", { name: "決定を記録する" }),
    ).toBeNull();
  });

  it("「閉じる」で一覧に戻る", async () => {
    const { user, panel } = await openDetail();

    await user.click(within(panel).getByRole("button", { name: "閉じる" }));

    await waitFor(() => expect(window.location.pathname).toBe("/owner"));
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).toBeNull();
  });
});

describe("仕様の持ち主の画面: 決める", () => {
  it("状況の初期値は言い直しの文章。なければ本人が書いた文章の先頭200文字、それもなければ相談の文章", async () => {
    const { panel } = await openDetail();
    expect(within(panel).getByLabelText("状況")).toHaveValue(
      "求人が一覧に出なくなった理由を知りたい。",
    );
    expect(
      within(panel).getByText(
        "どんな状況についての決定かを、後から読む人に分かるように書きます",
      ),
    ).toBeInTheDocument();

    const consultation = { question: "相談の文章" };
    expect(
      initialSituationOf(
        { structured: null, utterance: "あ".repeat(300) },
        consultation,
      ),
    ).toBe("あ".repeat(200));
    expect(
      initialSituationOf({ structured: null, utterance: null }, consultation),
    ).toBe("相談の文章");
  });

  it("決めた人: 相談先が1人なら、最初から選んでおく", async () => {
    const { panel } = await openDetail();

    expect(within(panel).getByLabelText("決めた人")).toHaveValue(
      "仕様の持ち主（開発用）",
    );
  });

  it("相談先が複数のとき、決めた人は選ぶまで空。「ほかの人」を選ぶと、名前の入力欄が出る", async () => {
    const { user, panel } = await openDetail({
      "GET /api/owner/inquiries/op-1": {
        body: consultingDetail({
          consultations: [makeConsultation({ to: ["持ち主A", "持ち主B"] })],
        }),
      },
    });
    const by = within(panel).getByLabelText("決めた人");
    expect(by).toHaveValue("");
    await fillDecision(user, panel);
    const submit = within(panel).getByRole("button", {
      name: "決定を記録する",
    });
    expect(submit).toBeDisabled();

    await user.selectOptions(by, "持ち主B");
    expect(submit).toBeEnabled();

    await user.selectOptions(by, "ほかの人");
    expect(within(panel).getByLabelText("決めた人の名前")).toBeInTheDocument();
    expect(submit).toBeDisabled();
    await user.type(
      within(panel).getByLabelText("決めた人の名前"),
      "経理の担当",
    );
    expect(submit).toBeEnabled();
  });

  it("決定の種類は7つ。「決定を記録する」の下に、先例として残ること、本人への連絡は担当者が行うことを添える", async () => {
    const { panel } = await openDetail();

    const options = within(within(panel).getByLabelText("決定の種類"))
      .getAllByRole("option")
      .map((o) => o.textContent);
    expect(options).toEqual([
      "選んでください",
      "意図どおり",
      "不具合",
      "受ける",
      "受けない",
      "採用する",
      "見送る",
      "その他",
    ]);
    expect(
      within(panel).getByText(
        "決定は先例として残り、次の同じ種類の問い合わせで、担当者と AI が参照します。本人への連絡は、担当者が行います。",
      ),
    ).toBeInTheDocument();
  });

  it("入力が足りないと押せない（種類、内容、理由、状況のどれが欠けても）", async () => {
    const { user, panel } = await openDetail();
    const submit = within(panel).getByRole("button", {
      name: "決定を記録する",
    });
    expect(submit).toBeDisabled();

    await user.selectOptions(
      within(panel).getByLabelText("決定の種類"),
      "不具合",
    );
    expect(submit).toBeDisabled();
    await user.type(within(panel).getByLabelText("決定の内容"), "直します。");
    expect(submit).toBeDisabled();
    await user.type(within(panel).getByLabelText("理由"), "   ");
    expect(submit).toBeDisabled();
    await user.clear(within(panel).getByLabelText("理由"));
    await user.type(within(panel).getByLabelText("理由"), "不具合のため。");
    expect(submit).toBeEnabled();

    await user.clear(within(panel).getByLabelText("状況"));
    expect(submit).toBeDisabled();
  });

  it("押すと、決定を POST し、その相談は一覧から消えて、完了の文言が出る（詳細は閉じる）", async () => {
    const { calls, user, panel } = await openDetail({
      [`POST ${DECISION_PATH}`]: {
        body: makeDetail({ read: false, consultations: [] }),
      },
    });
    await fillDecision(user, panel);

    await user.click(
      within(panel).getByRole("button", { name: "決定を記録する" }),
    );

    expect(
      await screen.findByText("決定を記録しました。担当者の番に戻りました。"),
    ).toBeInTheDocument();
    expect(callsTo(calls, "POST", DECISION_PATH)).toHaveLength(1);
    expect(callsTo(calls, "POST", DECISION_PATH)[0].body).toEqual({
      decisionKind: "AS_INTENDED",
      decision: "意図した仕様です。",
      reason: "締め切りのため。",
      situation: "求人が一覧に出なくなった理由を知りたい。",
      decidedBy: "仕様の持ち主（開発用）",
    });
    expect(
      screen.queryByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
    ).toBeNull();
    expect(
      screen.getByText("決定を待っている相談は、ありません。"),
    ).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe("/owner"));
    expect(
      screen.queryByRole("region", { name: "問い合わせの詳細" }),
    ).toBeNull();
  });

  it("書き換えた状況と、「ほかの人」の名前を、そのまま送る", async () => {
    const { calls, user, panel } = await openDetail({
      "GET /api/owner/inquiries/op-1": {
        body: consultingDetail({
          consultations: [makeConsultation({ to: ["持ち主A", "持ち主B"] })],
        }),
      },
      [`POST ${DECISION_PATH}`]: { body: makeDetail({ read: false }) },
    });
    await fillDecision(user, panel);
    await user.clear(within(panel).getByLabelText("状況"));
    await user.type(
      within(panel).getByLabelText("状況"),
      "期限の時刻ちょうど。",
    );
    await user.selectOptions(
      within(panel).getByLabelText("決めた人"),
      "ほかの人",
    );
    await user.type(
      within(panel).getByLabelText("決めた人の名前"),
      "経理の担当",
    );

    await user.click(
      within(panel).getByRole("button", { name: "決定を記録する" }),
    );

    await waitFor(() =>
      expect(callsTo(calls, "POST", DECISION_PATH)).toHaveLength(1),
    );
    expect(callsTo(calls, "POST", DECISION_PATH)[0].body).toMatchObject({
      situation: "期限の時刻ちょうど。",
      decidedBy: "経理の担当",
    });
  });

  it("失敗したら、エラーを出し、入力は残り、一覧からも消えない", async () => {
    const { user, panel } = await openDetail({
      [`POST ${DECISION_PATH}`]: {
        status: 409,
        body: { code: "INVALID_STATE", message: "他の操作と競合しました" },
      },
    });
    await fillDecision(user, panel);

    await user.click(
      within(panel).getByRole("button", { name: "決定を記録する" }),
    );

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(within(panel).getByLabelText("決定の内容")).toHaveValue(
      "意図した仕様です。",
    );
    expect(
      screen.getAllByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
    ).toHaveLength(1);
    expect(
      screen.queryByText("決定を記録しました。担当者の番に戻りました。"),
    ).toBeNull();
  });
});

describe("仕様の持ち主の画面: 英語と、画面の行き来", () => {
  it("英語の画面では、見出し、項目、ボタン、決定の種類が英語になり、一覧と詳細を英語で取り直す", async () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    const calls = openOwner("/owner");
    const user = userEvent.setup();

    expect(
      await screen.findByRole("heading", {
        name: "Consultations waiting for a decision",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeInTheDocument();
    const card = await screen.findByRole("button", {
      name: /期限ちょうどの時刻の扱い/,
    });
    expect(card).toHaveTextContent("Consulted: 仕様の持ち主（開発用）");
    expect(document.documentElement.lang).toBe("en");
    expect(
      callsTo(calls, "GET", "/api/owner/consultations")[0].headers[
        "accept-language"
      ],
    ).toBe("en");

    await user.click(card);
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await within(panel).findByText("Material");
    for (const name of ["Consultation", "Material", "Decide"]) {
      expect(
        within(panel).getByRole("heading", { name, level: 3 }),
      ).toBeInTheDocument();
    }
    expect(within(panel).getByText("Asked by")).toBeInTheDocument();
    expect(within(panel).getByText("Asked at")).toBeInTheDocument();
    expect(
      within(panel).getByText(
        "Describe the situation so that later readers understand what this decision is about.",
      ),
    ).toBeInTheDocument();
    expect(
      within(within(panel).getByLabelText("Kind of decision"))
        .getAllByRole("option")
        .map((o) => o.textContent),
    ).toEqual([
      "Choose one",
      "As intended",
      "Defect",
      "Accept",
      "Do not accept",
      "Adopt",
      "Not now",
      "Other",
    ]);
    for (const label of ["Decision", "Reason", "Decided by", "Situation"]) {
      expect(within(panel).getByLabelText(label)).toBeInTheDocument();
    }
    expect(
      within(panel).getByRole("button", { name: "Record the decision" }),
    ).toBeDisabled();
    expect(
      within(panel).getByText(
        "The decision is kept as a precedent that staff and AI refer to for similar inquiries. Staff will tell the requester.",
      ),
    ).toBeInTheDocument();
  });

  it("英語の完了の文言と、相談が無いときの文言", async () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    openOwner("/owner", {
      [`POST ${DECISION_PATH}`]: { body: makeDetail({ read: false }) },
    });
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ }),
    );
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    await within(panel).findByText("Material");
    await user.selectOptions(
      within(panel).getByLabelText("Kind of decision"),
      "As intended",
    );
    await user.type(within(panel).getByLabelText("Decision"), "As intended.");
    await user.type(within(panel).getByLabelText("Reason"), "Deadline.");

    await user.click(
      within(panel).getByRole("button", { name: "Record the decision" }),
    );

    expect(
      await screen.findByText(
        "The decision was recorded. It is staff's turn again.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("No consultations are waiting for a decision."),
    ).toBeInTheDocument();
  });

  it("開発用の行き来のリンクに、本人の画面と担当者の画面を出す（モックのとき）。出さないときもある", async () => {
    openOwner("/owner", {
      "GET /api/config": { body: makeConfig({ mock: true }) },
    });

    expect(
      await screen.findByRole("link", { name: "開発用: 本人の画面へ" }),
    ).toHaveAttribute("href", "/");
    expect(
      screen.getByRole("link", { name: "開発用: 担当者の画面へ" }),
    ).toHaveAttribute("href", "/operator");
  });

  it("本人の画面と担当者の画面にも、仕様の持ち主の画面へのリンクがある", async () => {
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ mock: true }) },
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    window.history.replaceState({}, "", "/operator");
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const link = await screen.findByRole("link", {
      name: "開発用: 仕様の持ち主の画面へ",
    });
    expect(link).toHaveAttribute("href", "/owner");
    expect(
      screen.getByRole("link", { name: "開発用: 本人の画面へ" }),
    ).toHaveAttribute("href", "/");
  });
});

const UNCONFIRMED = "GET /api/precedents?unconfirmedOnly=true";

/** 確かめられていない先例を2件返す応答。 */
const twoUnconfirmed = () => ({
  [UNCONFIRMED]: {
    body: {
      items: [
        makePrecedent({
          id: "p1",
          decision: "会議で決まった1つ目。",
          decidedBy: "経理の担当",
          confirmed: false,
          recordedBy: "OPERATOR",
        }),
        makePrecedent({
          id: "p2",
          decision: "会議で決まった2つ目。",
          decisionKind: "DEFECT",
          confirmed: false,
          recordedBy: "OPERATOR",
          function: { id: "posting", label: "求人の掲載" },
        }),
      ],
    },
  },
});

/** 確かめてほしい先例の節の中の、1件のカード。 */
const unconfirmedCard = (text: string, region = "確かめてほしい先例") =>
  within(screen.getByRole("region", { name: region }))
    .getByText(text)
    .closest(".op-decision") as HTMLElement;

describe("仕様の持ち主の画面: 確かめてほしい先例", () => {
  it("相談の一覧の下に、確かめられていない先例を並べる。置き場所、内容、決めた人、「まだ確かめていません」の印、操作を出す", async () => {
    openOwner("/owner", twoUnconfirmed());

    const section = await screen.findByRole("region", {
      name: "確かめてほしい先例",
    });
    expect(
      within(section).getByRole("heading", { name: "確かめてほしい先例" }),
    ).toBeInTheDocument();
    // 相談の一覧のカードより後ろ（下）にある
    expect(
      screen
        .getByRole("button", { name: /期限ちょうどの時刻の扱い/ })
        .compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const first = unconfirmedCard("会議で決まった1つ目。");
    expect(first).toHaveTextContent("おかしいかもしれない");
    expect(first).toHaveTextContent("求人管理画面");
    expect(first).toHaveTextContent("経理の担当");
    expect(first).toHaveTextContent("決めた人が、まだ確かめていません");
    expect(
      within(first).getByRole("button", { name: "このとおりです" }),
    ).toBeEnabled();
    expect(within(first).getByRole("button", { name: "直す" })).toBeEnabled();
    expect(unconfirmedCard("会議で決まった2つ目。")).toHaveTextContent(
      "不具合",
    );
    expect(unconfirmedCard("会議で決まった2つ目。")).toHaveTextContent(
      "求人の掲載",
    );
  });

  it("確かめられていない先例がなければ、節を出さない。先例を取れなくても、相談の一覧は出す", async () => {
    openOwner("/owner", { [UNCONFIRMED]: { body: { items: [] } } });
    await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ });
    expect(
      screen.queryByRole("region", { name: "確かめてほしい先例" }),
    ).toBeNull();
    expect(screen.queryByText("確かめてほしい先例")).toBeNull();
  });

  it("先例の取得に失敗しても、節を出さず、相談の一覧は出る", async () => {
    openOwner("/owner", {
      [UNCONFIRMED]: { status: 500, body: { code: "INTERNAL", message: "x" } },
    });

    await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ });
    expect(screen.queryByText("確かめてほしい先例")).toBeNull();
  });

  it("「このとおりです」を押すと、POST …/confirm（本文なし）を呼び、その先例が節から消える。最後の1件が消えると、節も消える", async () => {
    const calls = openOwner("/owner", {
      ...twoUnconfirmed(),
      "POST /api/precedents/p1/confirm": {
        body: makePrecedent({ id: "p1", confirmed: true }),
      },
      "POST /api/precedents/p2/confirm": {
        body: makePrecedent({ id: "p2", confirmed: true }),
      },
    });
    const user = userEvent.setup();
    await screen.findByRole("region", { name: "確かめてほしい先例" });

    await user.click(
      within(unconfirmedCard("会議で決まった1つ目。")).getByRole("button", {
        name: "このとおりです",
      }),
    );

    await waitFor(() =>
      expect(screen.queryByText("会議で決まった1つ目。")).toBeNull(),
    );
    const confirm = callsTo(calls, "POST", "/api/precedents/p1/confirm");
    expect(confirm).toHaveLength(1);
    expect(confirm[0].body).toBeUndefined();
    expect(screen.getByText("会議で決まった2つ目。")).toBeInTheDocument();

    await user.click(
      within(unconfirmedCard("会議で決まった2つ目。")).getByRole("button", {
        name: "このとおりです",
      }),
    );
    await waitFor(() =>
      expect(screen.queryByText("確かめてほしい先例")).toBeNull(),
    );
  });

  it("「直す」を押すと、その場で項目（状況、決定の種類、内容、理由、決めた人）を直せる。保存すると、PUT で送り、節から消える", async () => {
    const calls = openOwner("/owner", {
      ...twoUnconfirmed(),
      "PUT /api/precedents/p1": {
        body: makePrecedent({ id: "p1", confirmed: true }),
      },
    });
    const user = userEvent.setup();
    await screen.findByRole("region", { name: "確かめてほしい先例" });
    const card = within(unconfirmedCard("会議で決まった1つ目。"));

    await user.click(card.getByRole("button", { name: "直す" }));

    expect(card.getByLabelText("決定の内容")).toHaveValue(
      "会議で決まった1つ目。",
    );
    expect(card.getByLabelText("決定の種類")).toHaveValue("AS_INTENDED");
    expect(card.getByLabelText("決めた人")).toHaveValue("経理の担当");
    expect(card.getByLabelText("状況")).toHaveValue(
      "掲載期限の時刻ちょうどに、求人が一覧から消える。",
    );
    // 直している間は、「このとおりです」は出さない
    expect(card.queryByRole("button", { name: "このとおりです" })).toBeNull();

    await user.selectOptions(card.getByLabelText("決定の種類"), "不具合");
    await user.clear(card.getByLabelText("決定の内容"));
    await user.type(card.getByLabelText("決定の内容"), "不具合として直す。");
    await user.clear(card.getByLabelText("決めた人"));
    await user.type(card.getByLabelText("決めた人"), "仕様の持ち主（開発用）");
    await user.click(card.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(screen.queryByText("会議で決まった1つ目。")).toBeNull(),
    );
    const put = calls.filter(
      (c) => c.method === "PUT" && c.path === "/api/precedents/p1",
    );
    expect(put).toHaveLength(1);
    expect(put[0].body).toEqual({
      situation: "掲載期限の時刻ちょうどに、求人が一覧から消える。",
      decisionKind: "DEFECT",
      decision: "不具合として直す。",
      reason: "締め切りの時刻を過ぎた応募を受け付けないため。",
      decidedBy: "仕様の持ち主（開発用）",
    });
  });

  it("直す途中で、空の項目があると保存できない。「やめる」で元の表示に戻り、API は呼ばない", async () => {
    const calls = openOwner("/owner", twoUnconfirmed());
    const user = userEvent.setup();
    await screen.findByRole("region", { name: "確かめてほしい先例" });
    const card = within(unconfirmedCard("会議で決まった1つ目。"));
    await user.click(card.getByRole("button", { name: "直す" }));

    await user.clear(card.getByLabelText("理由"));
    expect(card.getByRole("button", { name: "保存" })).toBeDisabled();
    await user.click(card.getByRole("button", { name: "やめる" }));

    expect(card.getByRole("button", { name: "このとおりです" })).toBeEnabled();
    expect(card.queryByLabelText("決定の内容")).toBeNull();
    expect(calls.filter((c) => c.method !== "GET")).toHaveLength(0);
  });

  it("確かめるのに失敗したら、エラーを出し、その先例は残る", async () => {
    openOwner("/owner", {
      ...twoUnconfirmed(),
      "POST /api/precedents/p1/confirm": {
        status: 500,
        body: { code: "INTERNAL", message: "x" },
      },
    });
    const user = userEvent.setup();
    await screen.findByRole("region", { name: "確かめてほしい先例" });
    const card = within(unconfirmedCard("会議で決まった1つ目。"));

    await user.click(card.getByRole("button", { name: "このとおりです" }));

    expect(await card.findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(card.getByRole("button", { name: "このとおりです" })).toBeEnabled();
  });

  it("「更新」で、確かめてほしい先例も取り直す", async () => {
    let items: unknown[] = [];
    const calls = openOwner("/owner", {
      [UNCONFIRMED]: () => ({ body: { items } }),
    });
    const user = userEvent.setup();
    await screen.findByRole("button", { name: /期限ちょうどの時刻の扱い/ });
    expect(screen.queryByText("確かめてほしい先例")).toBeNull();

    items = [
      makePrecedent({
        id: "p9",
        decision: "後から来た先例。",
        confirmed: false,
      }),
    ];
    await user.click(screen.getByRole("button", { name: "更新" }));

    expect(await screen.findByText("後から来た先例。")).toBeInTheDocument();
    expect(
      callsTo(calls, "GET", "/api/precedents?unconfirmedOnly=true"),
    ).toHaveLength(2);
  });
});

describe("仕様の持ち主の画面: 材料の中の先例と、先例へのリンク", () => {
  it("材料の「調べた結果」と「AI の案」の間に、同じ置き場所の先例を出す（決める人が過去の決定を見られる）", async () => {
    const { panel } = await openDetail({
      "GET /api/owner/inquiries/op-1": {
        body: consultingDetail({
          precedents: [
            makePrecedent({ id: "old", decision: "過去の決定です。" }),
          ],
        }),
      },
    });

    const section = within(panel)
      .getByRole("heading", { name: "同じ置き場所の先例" })
      .closest("section")!;
    expect(section).toHaveTextContent("過去の決定です。");
    const result = within(panel)
      .getByRole("heading", { name: "調べた結果" })
      .closest("section")!;
    expect(result.nextElementSibling).toBe(section);
    expect(section.nextElementSibling).toContainElement(
      within(panel).getByRole("heading", { name: /AI の案/ }),
    );
    // 先例は、決める欄より前（材料の中）にある
    expect(
      section.compareDocumentPosition(
        within(panel).getByRole("heading", { name: "決める" }),
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("上部に、先例の一覧へのリンク「先例」がある", async () => {
    openOwner("/owner");

    expect(await screen.findByRole("link", { name: "先例" })).toHaveAttribute(
      "href",
      "/precedents",
    );
  });

  it("英語では、確かめてほしい先例の見出しとボタンが英語で出る", async () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    openOwner("/owner", twoUnconfirmed());
    const user = userEvent.setup();

    await screen.findByRole("region", { name: "Precedents to confirm" });
    const card = within(
      unconfirmedCard("会議で決まった1つ目。", "Precedents to confirm"),
    );
    expect(
      card.getByRole("button", { name: "This is correct" }),
    ).toBeInTheDocument();
    await user.click(card.getByRole("button", { name: "Edit" }));
    expect(card.getByRole("button", { name: "Save" })).toBeEnabled();
    expect(card.getByRole("button", { name: "Cancel" })).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Precedents" }),
    ).toBeInTheDocument();
  });
});
