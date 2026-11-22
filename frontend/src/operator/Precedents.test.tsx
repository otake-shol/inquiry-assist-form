import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import { LANGUAGE_STORAGE_KEY } from "../i18n/language.ts";
import {
  FakeOperatorStream,
  installFakeFetch,
  makeConfig,
  makeDetail,
  makeItem,
  makePrecedent,
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

describe("英語の担当者の画面: 先例", () => {
  it("節の見出し、リンク、機能の名前、基づく先例が英語で出る", async () => {
    const { panel } = await openPanel(
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
