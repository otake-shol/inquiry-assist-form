import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import { LANGUAGE_STORAGE_KEY } from "../i18n/language.ts";
import {
  CONSULTATION_STATE,
  FakeOperatorStream,
  installFakeFetch,
  makeConfig,
  makeConsultation,
  makeDetail,
  makeItem,
  makePrecedent,
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

const DECISION_PATH = "/api/operator/inquiries/op-1/decision";

type Route =
  RouteResult | ((b: unknown, h: Record<string, string>) => RouteResult);

/** 担当者の番の件の詳細。 */
const handlingDetail = (overrides: Parameters<typeof makeDetail>[0] = {}) =>
  makeDetail({ read: true, messages: [], ...overrides });

/**
 * 担当者のボードを描き、1件目のパネルを開く。
 * @param detail 1件目の詳細
 * @param extra そのほかの API
 * @param english 英語の画面で開くか
 */
async function openPanel(
  detail: ReturnType<typeof makeDetail>,
  extra: Record<string, Route> = {},
  english = false,
) {
  if (english) window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
  const stream = new FakeOperatorStream();
  const calls = installFakeFetch({
    "GET /api/config": { body: makeConfig({ trace: false }) },
    "GET /api/operator/inquiries": {
      body: { unreadCount: 0, items: [makeItem({ id: "op-1", read: true })] },
    },
    "GET /api/operator/inquiries/op-1": { body: detail },
    ...extra,
  });
  render(<Root subscribeOperator={stream.subscribe} />);
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ }),
  );
  const panel = await screen.findByRole("region", {
    name: english ? "Inquiry details" : "問い合わせの詳細",
  });
  await within(panel).findByText(detail.utterance!);
  return { calls, user, panel };
}

const callsTo = (calls: RecordedCall[], path: string) =>
  calls.filter((c) => c.method === "POST" && c.path === path);

/** 記録する決定の入力欄を、決定を記録するフォームの中から取る。 */
const recordFold = (panel: HTMLElement) =>
  within(panel)
    .getByText("決定を記録する（会議などで決まった場合）")
    .closest("details")!;

describe("担当者のパネル: 同じ置き場所の先例", () => {
  it("「調べた結果」と「AI の案」の間に、見出し「同じ置き場所の先例」の節を置く。先例ごとに、種類、内容、理由、状況、決めた人と日付を、実線の枠で出す", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        advice: {
          status: "DONE",
          judgment: {
            text: "確かめるとよい。",
            needsConsultation: false,
            consultationReason: null,
            basedOn: ["期限切れで非表示になる"],
          },
          procedure: null,
        },
        precedents: [
          makePrecedent({ id: "p1", decisionKind: "ACCEPT" }),
          makePrecedent({
            id: "p2",
            decision: "二つ目の決定。",
            language: "en",
          }),
        ],
      }),
    );

    const section = within(panel)
      .getByRole("heading", { name: "同じ置き場所の先例" })
      .closest("section")!;
    expect(section).toHaveClass("op-precedents");
    const result = within(panel)
      .getByRole("heading", { name: "調べた結果" })
      .closest("section")!;
    const advice = within(panel)
      .getByRole("heading", { name: /AI の案/ })
      .closest("section")!;
    expect(result.nextElementSibling).toBe(section);
    expect(section.nextElementSibling).toBe(advice);
    const cards = Array.from(section.querySelectorAll(".op-decision"));
    expect(cards).toHaveLength(2);
    const first = within(cards[0] as HTMLElement);
    expect(first.getByText("受ける")).toBeInTheDocument();
    expect(
      first.getByText(
        "期限ちょうどから期限切れとして扱うのは、意図した仕様です。",
      ),
    ).toBeInTheDocument();
    expect(
      first.getByText("締め切りの時刻を過ぎた応募を受け付けないため。"),
    ).toBeInTheDocument();
    expect(
      first.getByText("掲載期限の時刻ちょうどに、求人が一覧から消える。"),
    ).toBeInTheDocument();
    expect(cards[0]).toHaveTextContent("仕様の持ち主（開発用）");
    expect(
      within(cards[1] as HTMLElement)
        .getByText("二つ目の決定。")
        .closest("dl"),
    ).toHaveAttribute("lang", "en");
    expect(
      within(panel).queryByText("同じ置き場所の先例は、まだありません。"),
    ).toBeNull();
  });

  it("先例がなければ、「同じ置き場所の先例は、まだありません。」と出す（古い API で欠けていても）", async () => {
    const detail = handlingDetail();
    delete detail.precedents;
    const { panel } = await openPanel(detail);

    expect(
      within(panel).getByText("同じ置き場所の先例は、まだありません。"),
    ).toBeInTheDocument();
  });

  it("機能が違う先例には、その機能の名前を添える。同じ機能、機能が決まっていない先例には添えない", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        function: { id: "posting", label: "求人の掲載", by: "ASKER" },
        precedents: [
          makePrecedent({
            id: "same",
            decision: "同じ機能の決定。",
            function: { id: "posting", label: "求人の掲載" },
          }),
          makePrecedent({
            id: "other",
            decision: "別の機能の決定。",
            function: { id: "applicants", label: "応募者の一覧" },
          }),
          makePrecedent({
            id: "none",
            decision: "機能なしの決定。",
            function: null,
          }),
        ],
      }),
    );

    const card = (text: string) =>
      within(panel).getByText(text).closest(".op-decision") as HTMLElement;
    expect(card("別の機能の決定。")).toHaveTextContent("機能: 応募者の一覧");
    expect(card("同じ機能の決定。")).not.toHaveTextContent("機能:");
    expect(card("機能なしの決定。")).not.toHaveTextContent("機能:");
  });

  it("決めた人が確かめていない先例には、「決めた人が、まだ確かめていません」の印を付ける。確かめた先例には付けない", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        precedents: [
          makePrecedent({
            id: "u",
            decision: "確かめていない決定。",
            confirmed: false,
            recordedBy: "OPERATOR",
          }),
          makePrecedent({ id: "c", decision: "確かめた決定。" }),
        ],
      }),
    );

    const card = (text: string) =>
      within(panel).getByText(text).closest(".op-decision") as HTMLElement;
    expect(card("確かめていない決定。")).toHaveTextContent(
      "決めた人が、まだ確かめていません",
    );
    expect(card("確かめた決定。")).not.toHaveTextContent(
      "まだ確かめていません",
    );
  });

  it("節の末尾に、先例の一覧へのリンクを置く。この置き場所（システム、種類。機能が決まっていれば機能）で絞ってある", async () => {
    const { user, panel } = await openPanel(handlingDetail());

    const link = within(panel).getByRole("link", { name: "先例の一覧を見る" });
    expect(link).toHaveAttribute(
      "href",
      "/precedents?systemId=job-admin&type=LOOKS_WRONG",
    );
    const section = link.closest("section")!;
    expect(section).toHaveTextContent("同じ置き場所の先例");
    expect(section.lastElementChild).toContainElement(link);

    await user.click(link);
    await waitFor(() => expect(window.location.pathname).toBe("/precedents"));
    expect(window.location.search).toBe("?systemId=job-admin&type=LOOKS_WRONG");
  });

  it("機能が決まっている件のリンクには、機能も入れる。置き場所が未定の項目は入れない", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        function: { id: "posting", label: "求人の掲載", by: "ASKER" },
      }),
    );
    expect(
      within(panel).getByRole("link", { name: "先例の一覧を見る" }),
    ).toHaveAttribute(
      "href",
      "/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG",
    );
  });

  it("置き場所が未定なら、絞り込みなしの一覧へのリンクにする", async () => {
    const { panel } = await openPanel(
      handlingDetail({ system: null, type: null }),
    );
    expect(
      within(panel).getByRole("link", { name: "先例の一覧を見る" }),
    ).toHaveAttribute("href", "/precedents");
  });

  it("担当者のボードの上部に、先例の一覧へのリンク「先例」がある", async () => {
    installFakeFetch({
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    expect(await screen.findByRole("link", { name: "先例" })).toHaveAttribute(
      "href",
      "/precedents",
    );
  });
});

describe("担当者のパネル: AI の案の「基づく先例」", () => {
  const ref = {
    id: "p1",
    decision: "期限ちょうどから期限切れとして扱う。",
    decidedBy: "仕様の持ち主（開発用）",
    decidedAt: "2026-11-22T04:00:00Z",
  };

  it("判断の案と手順の案に、「基づく先例」として、決定の内容、決めた人、日付を並べる。事実がなければ「基づく事実」は出さない", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        advice: {
          status: "DONE",
          judgment: {
            text: "先例を当てはめられそうです。",
            needsConsultation: false,
            consultationReason: null,
            basedOn: [],
            basedOnPrecedents: [ref],
          },
          procedure: {
            steps: [{ target: "設定", action: "変える", check: "表示される" }],
            rollback: null,
            basedOn: ["期限切れで非表示になる"],
            basedOnPrecedents: [{ ...ref, id: "p2", decision: "別の先例。" }],
          },
        },
      }),
    );

    const advice = within(panel)
      .getByRole("heading", { name: /AI の案/ })
      .closest("section")!;
    const parts = Array.from(advice.querySelectorAll(".op-advice-part"));
    expect(parts).toHaveLength(2);
    const judgment = within(parts[0] as HTMLElement);
    expect(
      judgment.getByRole("heading", { name: "基づく先例" }),
    ).toBeInTheDocument();
    expect(judgment.queryByRole("heading", { name: "基づく事実" })).toBeNull();
    const item = judgment
      .getByText("期限ちょうどから期限切れとして扱う。")
      .closest("li")!;
    expect(item).toHaveTextContent("仕様の持ち主（開発用）");
    expect(item).toHaveTextContent(/\d+\/\d+/);
    const procedure = within(parts[1] as HTMLElement);
    expect(
      procedure.getByRole("heading", { name: "基づく事実" }),
    ).toBeInTheDocument();
    expect(
      procedure.getByRole("heading", { name: "基づく先例" }),
    ).toBeInTheDocument();
    expect(procedure.getByText("別の先例。")).toBeInTheDocument();
  });

  it("古い応答で basedOnPrecedents が欠けていても、これまでどおり事実だけを出す", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        advice: {
          status: "DONE",
          judgment: {
            text: "確かめるとよい。",
            needsConsultation: false,
            consultationReason: null,
            basedOn: ["期限切れで非表示になる"],
          },
          procedure: null,
        },
      }),
    );

    expect(
      within(panel).getByRole("heading", { name: "基づく事実" }),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("heading", { name: "基づく先例" }),
    ).toBeNull();
    expect(
      within(
        within(panel)
          .getByRole("heading", { name: /AI の案/ })
          .closest("section")!,
      ).getByText("期限切れで非表示になる"),
    ).toBeInTheDocument();
  });
});

describe("担当者のパネル: 決定を記録する", () => {
  it("担当者の番のとき、「相談する」の下に、折りたたみ「決定を記録する（会議などで決まった場合）」を置く。説明を添える", async () => {
    const { panel } = await openPanel(handlingDetail());

    const fold = recordFold(panel);
    expect(fold).not.toHaveAttribute("open");
    const consult = within(panel)
      .getByText("相談する", { selector: "summary" })
      .closest("details")!;
    const decline = within(panel)
      .getByText("見送る", { selector: "summary" })
      .closest("details")!;
    const follows = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(consult, fold)).toBe(true);
    expect(follows(fold, decline)).toBe(true);
    expect(
      within(fold).getByText(
        "決めた人が後から確かめられるように、仕様の持ち主の画面に出ます。",
      ),
    ).toBeInTheDocument();
    expect(
      within(fold).getByRole("button", { name: "決定を記録する" }),
    ).toBeDisabled();
  });

  it("持ち主の「決める」と同じ項目。状況の初期値は言い直し。決めた人は名前を書く欄で、相談先の名前を候補に出す", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        defaultConsultants: ["仕様の持ち主（開発用）", "システムAの持ち主"],
      }),
    );

    const fold = within(recordFold(panel));
    expect(fold.getByLabelText("状況")).toHaveValue(
      "求人が一覧に出なくなった理由を知りたい。",
    );
    const kind = fold.getByLabelText("決定の種類");
    expect(
      within(kind)
        .getAllByRole("option")
        .map((o) => o.textContent),
    ).toEqual([
      "選んでください",
      "意図どおり",
      "不具合",
      "受ける",
      "受けない",
      "採用する",
      "見送る",
      "その他",
    ]);
    const by = fold.getByLabelText("決めた人");
    expect(by.tagName).toBe("INPUT");
    expect(by).toHaveValue("");
    const listId = by.getAttribute("list")!;
    const options = Array.from(
      document.getElementById(listId)!.querySelectorAll("option"),
    ).map((o) => o.getAttribute("value"));
    expect(options).toEqual(["仕様の持ち主（開発用）", "システムAの持ち主"]);
  });

  it("入力が足りないと押せない。埋めて押すと、POST …/decision に決定を送り、決定の節に出る（状態は担当者の番のまま）", async () => {
    const recorded = makePrecedent({
      decision: "会議で、意図した仕様と決まった。",
      decidedBy: "経理の担当",
      confirmed: false,
      recordedBy: "OPERATOR",
    });
    const { calls, user, panel } = await openPanel(handlingDetail(), {
      [`POST ${DECISION_PATH}`]: {
        body: handlingDetail({ decisions: [recorded] }),
      },
    });
    await user.click(
      within(panel).getByText("決定を記録する（会議などで決まった場合）"),
    );
    const fold = within(recordFold(panel));
    const submit = fold.getByRole("button", { name: "決定を記録する" });

    await user.selectOptions(fold.getByLabelText("決定の種類"), "意図どおり");
    await user.type(fold.getByLabelText("決定の内容"), "意図した仕様です。");
    expect(submit).toBeDisabled();
    await user.type(fold.getByLabelText("理由"), "会議で決まったため。");
    expect(submit).toBeDisabled();
    await user.type(fold.getByLabelText("決めた人"), "経理の担当");
    expect(submit).toBeEnabled();

    await user.click(submit);

    expect(
      await within(panel).findByRole("heading", {
        name: "仕様の持ち主の決定",
      }),
    ).toBeInTheDocument();
    expect(callsTo(calls, DECISION_PATH)).toHaveLength(1);
    expect(callsTo(calls, DECISION_PATH)[0].body).toEqual({
      decisionKind: "AS_INTENDED",
      decision: "意図した仕様です。",
      reason: "会議で決まったため。",
      situation: "求人が一覧に出なくなった理由を知りたい。",
      decidedBy: "経理の担当",
    });
    // 担当者の番のまま。確かめられていない印が付く
    expect(within(panel).getByLabelText("本人に送る")).toBeInTheDocument();
    expect(
      within(panel)
        .getByText("会議で、意図した仕様と決まった。")
        .closest(".op-decision"),
    ).toHaveTextContent("決めた人が、まだ確かめていません");
    // 記録できたら、入力は空に戻る
    expect(within(recordFold(panel)).getByLabelText("決定の内容")).toHaveValue(
      "",
    );
  });

  it("相談している間も出る。記録すると、相談が決まり、担当者の番に戻る", async () => {
    const consulting = handlingDetail({
      ...CONSULTATION_STATE,
      consultations: [makeConsultation({ to: ["持ち主A", "持ち主B"] })],
    });
    const { calls, user, panel } = await openPanel(consulting, {
      [`POST ${DECISION_PATH}`]: {
        body: handlingDetail({
          handoverReason: "DECIDED",
          decisions: [makePrecedent()],
          consultations: [makeConsultation({ status: "DECIDED" })],
        }),
      },
    });

    // 相談している間の表示と並んで、折りたたみがある
    expect(
      within(panel).getByRole("button", { name: "相談を取り下げる" }),
    ).toBeInTheDocument();
    const fold = within(recordFold(panel));
    // 候補には、相談先の名前も出る
    const listId = fold.getByLabelText("決めた人").getAttribute("list")!;
    expect(
      Array.from(
        document.getElementById(listId)!.querySelectorAll("option"),
      ).map((o) => o.getAttribute("value")),
    ).toEqual(["仕様の持ち主（開発用）", "持ち主A", "持ち主B"]);
    await user.click(
      within(panel).getByText("決定を記録する（会議などで決まった場合）"),
    );
    await user.selectOptions(fold.getByLabelText("決定の種類"), "不具合");
    await user.type(fold.getByLabelText("決定の内容"), "直します。");
    await user.type(fold.getByLabelText("理由"), "不具合のため。");
    await user.type(fold.getByLabelText("決めた人"), "持ち主A");
    await user.click(fold.getByRole("button", { name: "決定を記録する" }));

    await within(panel).findByLabelText("本人に送る");
    expect(callsTo(calls, DECISION_PATH)).toHaveLength(1);
    expect(
      within(panel).queryByRole("button", { name: "相談を取り下げる" }),
    ).toBeNull();
    expect(
      within(panel).getByText("渡った理由: 仕様の持ち主が決めた"),
    ).toBeInTheDocument();
  });

  it("失敗したら、既存のエラーを出し、入力は残る", async () => {
    const { user, panel } = await openPanel(handlingDetail(), {
      [`POST ${DECISION_PATH}`]: {
        status: 409,
        body: { code: "INVALID_STATE", message: "他の操作と競合しました" },
      },
    });
    await user.click(
      within(panel).getByText("決定を記録する（会議などで決まった場合）"),
    );
    const fold = within(recordFold(panel));
    await user.selectOptions(fold.getByLabelText("決定の種類"), "不具合");
    await user.type(fold.getByLabelText("決定の内容"), "直します。");
    await user.type(fold.getByLabelText("理由"), "不具合のため。");
    await user.type(fold.getByLabelText("決めた人"), "経理の担当");

    await user.click(fold.getByRole("button", { name: "決定を記録する" }));

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(fold.getByLabelText("決定の内容")).toHaveValue("直します。");
  });
});

describe("英語の担当者の画面: 先例", () => {
  it("節の見出し、空の文言、リンク、印、基づく先例、決定を記録するが英語で出る", async () => {
    const { user, panel } = await openPanel(
      handlingDetail({
        function: { id: "posting", label: "Posting", by: "ASKER" },
        advice: {
          status: "DONE",
          judgment: {
            text: "Apply the precedent.",
            needsConsultation: false,
            consultationReason: null,
            basedOn: [],
            basedOnPrecedents: [
              {
                id: "p1",
                decision: "Treat it as expired.",
                decidedBy: "Owner",
                decidedAt: "2026-11-22T04:00:00Z",
              },
            ],
          },
          procedure: null,
        },
        precedents: [
          makePrecedent({
            decision: "Treat it as expired.",
            language: "en",
            confirmed: false,
            function: { id: "applicants", label: "Applicants" },
          }),
        ],
      }),
      {},
      true,
    );

    expect(
      within(panel).getByRole("heading", {
        name: "Precedents for the same place",
      }),
    ).toBeInTheDocument();
    const card = within(panel)
      .getAllByText("Treat it as expired.")
      .map((el) => el.closest(".op-decision"))
      .find((el) => el !== null) as HTMLElement;
    expect(card).toHaveTextContent(
      "Not yet confirmed by the person who decided",
    );
    expect(card).toHaveTextContent("Function: Applicants");
    expect(
      within(panel).getByRole("link", { name: "See all precedents" }),
    ).toHaveAttribute(
      "href",
      "/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG",
    );
    expect(
      within(panel).getByRole("heading", { name: "Based on precedents" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Precedents" })).toHaveAttribute(
      "href",
      "/precedents",
    );
    await user.click(
      within(panel).getByText(
        "Record a decision (made in a meeting or elsewhere)",
      ),
    );
    expect(
      within(panel).getByText(
        "It will appear on the spec owner's screen so that the person who decided can confirm it.",
      ),
    ).toBeInTheDocument();
  });

  it("先例がないときの文言が英語で出る", async () => {
    const { panel } = await openPanel(handlingDetail(), {}, true);

    expect(
      within(panel).getByText(
        "There are no precedents for the same place yet.",
      ),
    ).toBeInTheDocument();
  });
});
