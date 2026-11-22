import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import { LANGUAGE_STORAGE_KEY } from "../i18n/language.ts";
import {
  FakeOperatorStream,
  installFakeFetch,
  makeConfig,
  makePrecedent,
  type RecordedCall,
  type RouteResult,
} from "../test/fakes.ts";

beforeEach(() => {
  window.history.replaceState({}, "", "/precedents");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

const SYSTEMS = [
  {
    id: "job-admin",
    label: "求人管理画面",
    functions: [
      { id: "posting", label: "求人の掲載" },
      { id: "applicants", label: "応募者の一覧" },
    ],
  },
  { id: "billing", label: "請求管理", functions: [] },
];

type Route =
  RouteResult | ((b: unknown, h: Record<string, string>) => RouteResult);

/** 先例の一覧の応答（新しい順）。 */
const items = () => [
  makePrecedent({
    id: "new",
    inquiryId: "op-9",
    decision: "新しい決定。",
    decidedAt: "2026-11-23T04:00:00Z",
  }),
  makePrecedent({
    id: "old",
    inquiryId: "op-3",
    decision: "Old decision.",
    language: "en",
    confirmed: false,
    function: { id: "posting", label: "求人の掲載" },
    decisionKind: "REJECT",
  }),
];

/**
 * 先例の一覧の画面を描く。
 * @param path 開くパス（クエリつき）
 * @param routes API の応答
 */
function openPrecedents(
  path = "/precedents",
  routes: Record<string, Route> = {},
) {
  window.history.replaceState({}, "", path);
  const calls = installFakeFetch({
    "GET /api/config": { body: makeConfig({ systems: SYSTEMS }) },
    "GET /api/precedents": { body: { items: items() } },
    ...routes,
  });
  render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
  return calls;
}

const listCalls = (calls: RecordedCall[]) =>
  calls.filter(
    (c) => c.method === "GET" && c.path.startsWith("/api/precedents"),
  );

describe("先例の一覧の画面", () => {
  it("/precedents で出る。見出し、先例のカード（新しい順）、置き場所、決定の種類、内容、状況、理由、決めた人と日付、確かめられていない印を出す", async () => {
    openPrecedents();

    expect(
      await screen.findByRole("heading", { name: "先例の一覧", level: 1 }),
    ).toBeInTheDocument();
    expect(document.title).toBe("先例の一覧");
    const list = screen.getByRole("region", { name: "先例" });
    const cards = await waitFor(() => {
      const found = list.querySelectorAll(".op-decision");
      expect(found).toHaveLength(2);
      return Array.from(found) as HTMLElement[];
    });
    expect(cards[0]).toHaveTextContent("新しい決定。");
    expect(cards[1]).toHaveTextContent("Old decision.");
    expect(cards[0]).toHaveTextContent("おかしいかもしれない・求人管理画面");
    expect(cards[0]).toHaveTextContent("意図どおり");
    expect(cards[0]).toHaveTextContent("仕様の持ち主（開発用）");
    expect(cards[0]).toHaveTextContent(/\d+\/\d+/);
    expect(cards[0]).not.toHaveTextContent("まだ確かめていません");
    expect(cards[1]).toHaveTextContent("受けない");
    expect(cards[1]).toHaveTextContent("求人の掲載");
    expect(cards[1]).toHaveTextContent("決めた人が、まだ確かめていません");
    // 状況と理由も出る
    expect(cards[0]).toHaveTextContent(
      "掲載期限の時刻ちょうどに、求人が一覧から消える。",
    );
    expect(cards[0]).toHaveTextContent(
      "締め切りの時刻を過ぎた応募を受け付けないため。",
    );
  });

  it("先例の文章には、書かれたときの言語の lang が付く。置き場所の名前と決定の種類は、見る人の言語", async () => {
    openPrecedents();

    const english = await screen.findByText("Old decision.");
    expect(english.closest("dl")).toHaveAttribute("lang", "en");
    expect(screen.getByText("新しい決定。").closest("dl")).toHaveAttribute(
      "lang",
      "ja",
    );
  });

  it("先例ごとに、元の問い合わせ（担当者の詳細 /operator/{id}）へのリンクがある。押すとそのページへ移る", async () => {
    openPrecedents();
    const user = userEvent.setup();

    const links = await screen.findAllByRole("link", {
      name: "元の問い合わせを見る",
    });
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      "/operator/op-9",
      "/operator/op-3",
    ]);
    await user.click(links[0]);

    await waitFor(() =>
      expect(window.location.pathname).toBe("/operator/op-9"),
    );
  });

  it("先例がなければ「先例は、まだありません。」と出す", async () => {
    openPrecedents("/precedents", {
      "GET /api/precedents": { body: { items: [] } },
    });

    expect(
      await screen.findByText("先例は、まだありません。"),
    ).toBeInTheDocument();
  });

  it("この画面では先例を直せない（直す、このとおりです、保存の操作は出さない）", async () => {
    openPrecedents();

    await screen.findByText("新しい決定。");
    expect(screen.queryByRole("button", { name: "直す" })).toBeNull();
    expect(screen.queryByRole("button", { name: "このとおりです" })).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it("取得に失敗したら、エラーを出す", async () => {
    openPrecedents("/precedents", {
      "GET /api/precedents": {
        status: 500,
        body: { code: "INTERNAL", message: "x" },
      },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "読み込めませんでした",
    );
  });
});

describe("先例の一覧の画面: 絞り込み", () => {
  it("URL のクエリが、絞り込みの初期値になり、同じ絞り込みで API を呼ぶ", async () => {
    const calls = openPrecedents(
      "/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG",
      {
        "GET /api/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG":
          { body: { items: [items()[0]] } },
      },
    );

    await screen.findByText("新しい決定。");
    expect(screen.getByLabelText("システム")).toHaveValue("job-admin");
    await waitFor(() =>
      expect(screen.getByLabelText("機能")).toHaveValue("posting"),
    );
    expect(screen.getByLabelText("種類")).toHaveValue("LOOKS_WRONG");
    expect(screen.queryByText("Old decision.")).toBeNull();
    expect(listCalls(calls).map((c) => c.path)).toEqual([
      "/api/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG",
    ]);
  });

  it("選ぶと URL のクエリが変わり（履歴は積まない）、その絞り込みで取り直す。機能の選択肢は選んだシステムのもの", async () => {
    const calls = openPrecedents("/precedents", {
      "GET /api/precedents?systemId=job-admin": { body: { items: [] } },
      "GET /api/precedents?systemId=job-admin&functionId=applicants": {
        body: { items: [items()[0]] },
      },
      "GET /api/precedents?systemId=job-admin&functionId=applicants&type=HOW_TO":
        { body: { items: [] } },
    });
    const user = userEvent.setup();
    await screen.findByText("新しい決定。");
    const historyLength = window.history.length;
    // システムを選ぶまで、機能は選べない
    expect(screen.getByLabelText("機能")).toBeDisabled();

    await user.selectOptions(screen.getByLabelText("システム"), "求人管理画面");

    await waitFor(() =>
      expect(window.location.search).toBe("?systemId=job-admin"),
    );
    expect(
      await screen.findByText("先例は、まだありません。"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("機能")).toBeEnabled();
    expect(
      within(screen.getByLabelText("機能"))
        .getAllByRole("option")
        .map((o) => o.textContent),
    ).toEqual(["すべて", "求人の掲載", "応募者の一覧"]);

    await user.selectOptions(screen.getByLabelText("機能"), "応募者の一覧");
    await waitFor(() =>
      expect(window.location.search).toBe(
        "?systemId=job-admin&functionId=applicants",
      ),
    );
    expect(await screen.findByText("新しい決定。")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("種類"), "使い方を知りたい");
    await waitFor(() =>
      expect(window.location.search).toBe(
        "?systemId=job-admin&functionId=applicants&type=HOW_TO",
      ),
    );
    expect(window.history.length).toBe(historyLength);
    expect(listCalls(calls).map((c) => c.path)).toEqual([
      "/api/precedents",
      "/api/precedents?systemId=job-admin",
      "/api/precedents?systemId=job-admin&functionId=applicants",
      "/api/precedents?systemId=job-admin&functionId=applicants&type=HOW_TO",
    ]);
  });

  it("システムを変えると機能は外れる。「すべて」に戻すと、クエリから外れる", async () => {
    openPrecedents(
      "/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG",
      {
        "GET /api/precedents?systemId=job-admin&functionId=posting&type=LOOKS_WRONG":
          { body: { items: [] } },
        "GET /api/precedents?systemId=billing&type=LOOKS_WRONG": {
          body: { items: [] },
        },
        "GET /api/precedents?systemId=billing": { body: { items: [] } },
      },
    );
    const user = userEvent.setup();
    await screen.findByText("先例は、まだありません。");

    await user.selectOptions(screen.getByLabelText("システム"), "請求管理");
    await waitFor(() =>
      expect(window.location.search).toBe("?systemId=billing&type=LOOKS_WRONG"),
    );
    expect(screen.getByLabelText("機能")).toHaveValue("");

    await user.selectOptions(screen.getByLabelText("種類"), "すべて");
    await waitFor(() =>
      expect(window.location.search).toBe("?systemId=billing"),
    );
  });

  it("ブラウザの「戻る」「進む」など、URL が外から変わっても、絞り込みが追従する", async () => {
    const calls = openPrecedents("/precedents", {
      "GET /api/precedents?type=HOW_TO": { body: { items: [] } },
    });
    await screen.findByText("新しい決定。");

    act(() => {
      window.history.pushState({}, "", "/precedents?type=HOW_TO");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    await waitFor(() =>
      expect(screen.getByLabelText("種類")).toHaveValue("HOW_TO"),
    );
    expect(
      await screen.findByText("先例は、まだありません。"),
    ).toBeInTheDocument();
    expect(listCalls(calls).map((c) => c.path)).toEqual([
      "/api/precedents",
      "/api/precedents?type=HOW_TO",
    ]);
  });
});

describe("先例の一覧の画面: 言語と、ほかの画面への行き来", () => {
  it("ほかの画面へ戻るリンク（担当者のボード、仕様の持ち主の画面）と、言語の切り替えがある", async () => {
    openPrecedents();
    const user = userEvent.setup();

    expect(
      await screen.findByRole("link", { name: "担当者のボードへ" }),
    ).toHaveAttribute("href", "/operator");
    expect(
      screen.getByRole("link", { name: "仕様の持ち主の画面へ" }),
    ).toHaveAttribute("href", "/owner");
    expect(screen.getByRole("group", { name: "言語" })).toBeInTheDocument();

    await user.click(
      screen.getByRole("link", { name: "仕様の持ち主の画面へ" }),
    );
    await waitFor(() => expect(window.location.pathname).toBe("/owner"));
  });

  it("言語を切り替えると、英語の文言になり、Accept-Language を変えて取り直す。絞り込みは変わらない", async () => {
    const calls = openPrecedents("/precedents?type=HOW_TO", {
      "GET /api/precedents?type=HOW_TO": { body: { items: items() } },
    });
    const user = userEvent.setup();
    await screen.findByText("新しい決定。");

    await user.click(screen.getByRole("button", { name: "English" }));

    expect(
      await screen.findByRole("heading", { name: "Precedents", level: 1 }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("System")).toBeInTheDocument();
    expect(screen.getByLabelText("Function")).toBeInTheDocument();
    expect(screen.getByLabelText("Type")).toHaveValue("HOW_TO");
    expect(
      screen.getByRole("link", { name: "Staff board" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Spec owner screen" }),
    ).toBeInTheDocument();
    expect(
      (
        await screen.findAllByRole("link", {
          name: "Open the original inquiry",
        })
      ).length,
    ).toBe(2);
    expect(window.location.search).toBe("?type=HOW_TO");
    const last = listCalls(calls)[listCalls(calls).length - 1];
    expect(last.headers["accept-language"]).toBe("en");
    expect(document.documentElement.lang).toBe("en");
  });

  it("英語で、空の文言と「すべて」が英語で出る", async () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    openPrecedents("/precedents", {
      "GET /api/precedents": { body: { items: [] } },
    });

    expect(
      await screen.findByText("There are no precedents yet."),
    ).toBeInTheDocument();
    expect(
      within(screen.getByLabelText("System"))
        .getAllByRole("option")
        .map((o) => o.textContent)[0],
    ).toBe("All");
  });
});
