import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
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

const CONSULT_PATH = "/api/operator/inquiries/op-1/consultations";
const CANCEL_PATH = "/api/operator/inquiries/op-1/consultations/cancel";

/** 担当者の番の件の詳細（相談先の既定は1人）。 */
const handlingDetail = (overrides: Parameters<typeof makeDetail>[0] = {}) =>
  makeDetail({ read: true, messages: [], ...overrides });

/** 相談中の件の詳細。 */
const consultingDetail = (overrides: Parameters<typeof makeDetail>[0] = {}) =>
  makeDetail({
    read: true,
    messages: [],
    ...CONSULTATION_STATE,
    consultations: [
      makeConsultation({ to: ["仕様の持ち主（開発用）", "システムAの持ち主"] }),
    ],
    ...overrides,
  });

type Route =
  RouteResult | ((b: unknown, h: Record<string, string>) => RouteResult);

/**
 * 担当者のボードを描き、1件目を開く。
 * @param detail 1件目の詳細
 * @param item 一覧の1件目
 * @param extra そのほかの API
 * @param english 英語の画面で開くか
 */
async function openPanel(
  detail: ReturnType<typeof makeDetail>,
  item: ReturnType<typeof makeItem> = makeItem({ id: "op-1", read: true }),
  extra: Record<string, Route> = {},
  english = false,
) {
  if (english) window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
  const stream = new FakeOperatorStream();
  const calls = installFakeFetch({
    "GET /api/config": { body: makeConfig({ trace: false }) },
    "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [item] } },
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
  return { calls, stream, user, panel };
}

const callsTo = (calls: RecordedCall[], path: string) =>
  calls.filter((c) => c.method === "POST" && c.path === path);

describe("担当者のボード: 相談中の列", () => {
  it("「担当者の番」と「本人の番」の間に「相談中」の列を置く。番が仕様の持ち主の件が入り、相談先の名前を添える", async () => {
    const consulting = makeItem({
      id: "op-1",
      read: true,
      ...CONSULTATION_STATE,
      consultants: ["仕様の持ち主（開発用）", "システムAの持ち主"],
    });
    installFakeFetch({
      "GET /api/operator/inquiries": {
        body: { unreadCount: 0, items: [consulting] },
      },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ });
    const board = screen.getByRole("region", { name: "受付一覧" });
    expect(
      within(board)
        .getAllByRole("heading", { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(["担当者の番", "相談中", "本人の番", "終わり"]);
    const column = screen.getByRole("region", { name: "相談中 1件" });
    const card = within(column).getByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    expect(card).toHaveTextContent(
      "相談先: 仕様の持ち主（開発用）、システムAの持ち主",
    );
    // 区間は、担当者の画面の言葉で添える（相談中でも変わらない）
    expect(card).toHaveTextContent("区間 決まる");
    expect(
      screen.getByRole("region", { name: "担当者の番 0件" }),
    ).toBeInTheDocument();
  });

  it("相談先の名前が一覧に無いときは、名前を添えない", async () => {
    installFakeFetch({
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [makeItem({ read: true, ...CONSULTATION_STATE })],
        },
      },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    expect(card).not.toHaveTextContent("相談先");
  });

  it("updated で番が仕様の持ち主に変わると、カードが「相談中」の列へ移り、読み上げにも伝える", async () => {
    const stream = new FakeOperatorStream();
    installFakeFetch({
      "GET /api/operator/inquiries": {
        body: { unreadCount: 0, items: [makeItem({ read: true })] },
      },
    });
    render(<Root subscribeOperator={stream.subscribe} />);
    await screen.findByRole("button", { name: /求人が一覧に出なくなった/ });

    stream.latest.handlers.onUpdated(
      makeItem({ read: true, ...CONSULTATION_STATE }),
    );

    await within(
      await screen.findByRole("region", { name: "相談中 1件" }),
    ).findByRole("button", { name: /求人が一覧に出なくなった/ });
    expect(await screen.findByText(/を相談中に移しました/)).toBeInTheDocument();
  });
});

describe("担当者のパネル: 相談する", () => {
  it("本人に送る欄のすぐ下に、折りたたみ「相談する」を置く。既定の相談先がチェック済みで並び、説明を添える", async () => {
    const { panel } = await openPanel(
      handlingDetail({ defaultConsultants: ["仕様の持ち主（開発用）"] }),
    );

    const fold = within(panel).getByText("相談する").closest("details")!;
    expect(fold).not.toHaveAttribute("open");
    const send = within(panel).getByLabelText("本人に送る");
    const decline = within(panel)
      .getByText("見送る", { selector: "summary" })
      .closest("details")!;
    const follows = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(send, fold)).toBe(true);
    expect(follows(fold, decline)).toBe(true);
    expect(
      within(fold).getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
    ).toBeChecked();
    expect(
      within(fold).getByText(
        "相談している間は、仕様の持ち主の番になります。調べた結果や AI の案は、相談先にも見えます。",
      ),
    ).toBeInTheDocument();
    expect(
      within(fold).getByRole("button", { name: "仕様の持ち主に相談する" }),
    ).toBeDisabled();
  });

  it("押すと、相談先と相談したいことを POST し、仕様の持ち主の番になる（本人に送る、見送るは消え、取り下げが出る）", async () => {
    const { calls, user, panel } = await openPanel(
      handlingDetail(),
      undefined,
      {
        [`POST ${CONSULT_PATH}`]: {
          body: consultingDetail({
            consultations: [
              makeConsultation({
                question: "期限ちょうどの扱いは仕様ですか。",
              }),
            ],
          }),
        },
      },
    );
    await user.click(within(panel).getByText("相談する"));

    await user.type(
      within(panel).getByLabelText("相談したいこと"),
      "期限ちょうどの扱いは仕様ですか。",
    );
    await user.click(
      within(panel).getByRole("button", { name: "仕様の持ち主に相談する" }),
    );

    await within(panel).findByRole("button", { name: "相談を取り下げる" });
    expect(callsTo(calls, CONSULT_PATH)).toHaveLength(1);
    expect(callsTo(calls, CONSULT_PATH)[0].body).toEqual({
      to: ["仕様の持ち主（開発用）"],
      question: "期限ちょうどの扱いは仕様ですか。",
    });
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    expect(
      within(panel).queryByText("見送る", { selector: "summary" }),
    ).toBeNull();
    expect(within(panel).queryByLabelText("相談したいこと")).toBeNull();
    // 状態の行は、仕様の持ち主の番になる
    expect(within(panel).getByText(/仕様の持ち主の番/)).toBeInTheDocument();
  });

  it("既定の相談先のチェックを外せる。「ほかの人」に名前を書くと足せる。送るのは、チェックの入った名前と足した名前", async () => {
    const { calls, user, panel } = await openPanel(
      handlingDetail({
        defaultConsultants: ["仕様の持ち主（開発用）", "システムAの持ち主"],
      }),
      undefined,
      { [`POST ${CONSULT_PATH}`]: { body: consultingDetail() } },
    );
    await user.click(within(panel).getByText("相談する"));

    await user.click(
      within(panel).getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
    );
    expect(
      within(panel).getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
    ).not.toBeChecked();
    await user.type(within(panel).getByLabelText("ほかの人"), "経理の担当");
    await user.type(
      within(panel).getByLabelText("相談したいこと"),
      "確認です。",
    );
    await user.click(
      within(panel).getByRole("button", { name: "仕様の持ち主に相談する" }),
    );

    await waitFor(() => expect(callsTo(calls, CONSULT_PATH)).toHaveLength(1));
    expect(callsTo(calls, CONSULT_PATH)[0].body).toEqual({
      to: ["システムAの持ち主", "経理の担当"],
      question: "確認です。",
    });
  });

  it("相談先が1人もいないと押せない（既定の相談先が無く、名前も足していない。全員外した場合も）。名前を足すと押せる", async () => {
    const { user, panel } = await openPanel(
      handlingDetail({ defaultConsultants: [] }),
    );
    await user.click(within(panel).getByText("相談する"));
    const submit = within(panel).getByRole("button", {
      name: "仕様の持ち主に相談する",
    });

    await user.type(
      within(panel).getByLabelText("相談したいこと"),
      "確認です。",
    );
    expect(submit).toBeDisabled();

    await user.type(within(panel).getByLabelText("ほかの人"), "経理の担当");
    expect(submit).toBeEnabled();

    await user.clear(within(panel).getByLabelText("ほかの人"));
    expect(submit).toBeDisabled();
  });

  it("相談したいことが空（空白だけを含む）なら押せない。全員のチェックを外すと押せない", async () => {
    const { user, panel } = await openPanel(handlingDetail());
    await user.click(within(panel).getByText("相談する"));
    const submit = within(panel).getByRole("button", {
      name: "仕様の持ち主に相談する",
    });

    await user.type(within(panel).getByLabelText("相談したいこと"), "   ");
    expect(submit).toBeDisabled();
    await user.type(
      within(panel).getByLabelText("相談したいこと"),
      "確認です。",
    );
    expect(submit).toBeEnabled();

    await user.click(
      within(panel).getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
    );
    expect(submit).toBeDisabled();
  });

  it("失敗したら、既存のエラーを出し、入力した文章は残る", async () => {
    const { user, panel } = await openPanel(handlingDetail(), undefined, {
      [`POST ${CONSULT_PATH}`]: {
        status: 409,
        body: { code: "INVALID_STATE", message: "他の操作と競合しました" },
      },
    });
    await user.click(within(panel).getByText("相談する"));
    await user.type(
      within(panel).getByLabelText("相談したいこと"),
      "確認です。",
    );

    await user.click(
      within(panel).getByRole("button", { name: "仕様の持ち主に相談する" }),
    );

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "うまく処理できませんでした",
    );
    expect(within(panel).getByLabelText("相談したいこと")).toHaveValue(
      "確認です。",
    );
  });
});

describe("担当者のパネル: 相談している間", () => {
  it("「本人に送る」「相談する」「見送る」の代わりに、待っていること、相談した文章、「相談を取り下げる」を出す", async () => {
    const { panel } = await openPanel(consultingDetail());

    expect(
      within(panel).getByText(
        "仕様の持ち主の決定を待っています（相談先: 仕様の持ち主（開発用）、システムAの持ち主）",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(
        "期限ちょうどの時刻の扱いは、意図した仕様でしょうか。",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "相談を取り下げる" }),
    ).toBeEnabled();
    expect(within(panel).queryByLabelText("本人に送る")).toBeNull();
    expect(within(panel).queryByText("相談する")).toBeNull();
    expect(
      within(panel).queryByText("見送る", { selector: "summary" }),
    ).toBeNull();
    // 状態の行
    expect(
      within(panel).getByText("仕様の持ち主の番（仕様の持ち主の決定待ち）"),
    ).toBeInTheDocument();
  });

  it("取り下げると、本文なしで POST し、担当者の番に戻って、「相談する」と「本人に送る」が出る", async () => {
    const { calls, user, panel } = await openPanel(
      consultingDetail(),
      undefined,
      {
        [`POST ${CANCEL_PATH}`]: {
          body: handlingDetail({
            consultations: [
              makeConsultation({
                status: "CANCELED",
                closedAt: "2026-11-22T05:00:00Z",
              }),
            ],
          }),
        },
      },
    );

    await user.click(
      within(panel).getByRole("button", { name: "相談を取り下げる" }),
    );

    await within(panel).findByLabelText("本人に送る");
    expect(callsTo(calls, CANCEL_PATH)).toHaveLength(1);
    expect(callsTo(calls, CANCEL_PATH)[0].body).toBeUndefined();
    expect(within(panel).getByText("相談する")).toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", { name: "相談を取り下げる" }),
    ).toBeNull();
  });

  it("取り下げに失敗したら、エラーを出し、相談中のまま", async () => {
    const { user, panel } = await openPanel(consultingDetail(), undefined, {
      [`POST ${CANCEL_PATH}`]: {
        status: 409,
        body: { code: "INVALID_STATE", message: "他の操作と競合しました" },
      },
    });

    await user.click(
      within(panel).getByRole("button", { name: "相談を取り下げる" }),
    );

    expect(await within(panel).findByRole("alert")).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "相談を取り下げる" }),
    ).toBeEnabled();
  });
});

describe("担当者のパネル: 仕様の持ち主の決定", () => {
  const advice = {
    status: "DONE" as const,
    judgment: {
      text: "期限ちょうどの扱いを確かめるとよい。",
      needsConsultation: true,
      consultationReason: "仕様の判断が要る",
      basedOn: ["期限切れで非表示になる"],
    },
    procedure: null,
  };

  it("決定がなければ節を出さない", async () => {
    const { panel } = await openPanel(handlingDetail({ decisions: [] }));

    expect(
      within(panel).queryByRole("heading", { name: "仕様の持ち主の決定" }),
    ).toBeNull();
  });

  it("決定ごとに、種類、内容、理由、状況、決めた人と日付を、実線の枠で出す。AI の案（点線の枠）のすぐ下に置く", async () => {
    const { panel } = await openPanel(
      handlingDetail({
        advice,
        decisions: [
          makePrecedent(),
          makePrecedent({
            id: "prec-2",
            decisionKind: "DEFECT",
            decision: "不具合として直します。",
            language: "en",
          }),
        ],
      }),
    );

    const heading = within(panel).getByRole("heading", {
      name: "仕様の持ち主の決定",
    });
    const section = heading.closest("section")!;
    expect(section).toHaveClass("op-decisions");
    const cards = Array.from(section.querySelectorAll(".op-decision"));
    expect(cards).toHaveLength(2);
    const first = within(cards[0] as HTMLElement);
    expect(first.getByText("意図どおり")).toBeInTheDocument();
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
    expect(cards[0]).toHaveTextContent(/\d+\/\d+/);
    expect(
      within(cards[1] as HTMLElement).getByText("不具合"),
    ).toBeInTheDocument();
    // 決定の文章は、書かれたときの言語を持つ
    expect(
      within(cards[1] as HTMLElement)
        .getByText("不具合として直します。")
        .closest("dl"),
    ).toHaveAttribute("lang", "en");
    // 案は点線の枠、決定は実線の枠。決定は案のすぐ下
    const adviceSection = within(panel)
      .getByRole("heading", { name: /AI の案/ })
      .closest("section")!;
    expect(adviceSection).toHaveClass("op-advice");
    expect(adviceSection).not.toBe(section);
    expect(adviceSection.nextElementSibling).toBe(section);
    expect(section.querySelector(".op-advice")).toBeNull();
  });

  it("相談している間も、これまでの決定を出す", async () => {
    const { panel } = await openPanel(
      consultingDetail({ decisions: [makePrecedent()] }),
    );

    expect(
      within(panel).getByRole("heading", { name: "仕様の持ち主の決定" }),
    ).toBeInTheDocument();
  });
});

describe("担当者のパネル: 状態の履歴と渡った理由", () => {
  it("相談の出来事（相談した、決めた、取り下げた）と、起こした人「仕様の持ち主」を、日本語の言葉で出す", async () => {
    const at = "2026-11-22T03:00:00Z";
    const { user, panel } = await openPanel(
      handlingDetail({
        history: [
          {
            at,
            actor: "OPERATOR",
            event: "CONSULTED",
            phase: "DECIDE",
            turn: "OWNER",
            awaiting: "CONSULTATION",
            ending: null,
          },
          {
            at,
            actor: "OWNER",
            event: "DECIDED",
            phase: "DECIDE",
            turn: "OPERATOR",
            awaiting: "HANDLING",
            ending: null,
          },
          {
            at,
            actor: "OPERATOR",
            event: "CONSULTATION_CANCELED",
            phase: "DECIDE",
            turn: "OPERATOR",
            awaiting: "HANDLING",
            ending: null,
          },
        ],
      }),
    );

    await user.click(within(panel).getByText("状態の履歴"));
    const rows = within(
      within(panel).getByText("状態の履歴").closest("details")!,
    ).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("担当者");
    expect(rows[0]).toHaveTextContent("仕様の持ち主に相談した");
    expect(rows[0]).toHaveTextContent(
      "仕様の持ち主の番（仕様の持ち主の決定待ち）",
    );
    expect(rows[1]).toHaveTextContent("仕様の持ち主");
    expect(rows[1]).toHaveTextContent("仕様の持ち主が決めた");
    expect(rows[2]).toHaveTextContent("相談を取り下げた");
  });

  it("決まって担当者の番に戻った件は、渡った理由を「仕様の持ち主が決めた」と出す", async () => {
    const { panel } = await openPanel(
      handlingDetail({ handoverReason: "DECIDED" }),
    );

    expect(
      within(panel).getByText("渡った理由: 仕様の持ち主が決めた"),
    ).toBeInTheDocument();
  });
});

describe("英語の担当者の画面: 相談", () => {
  it("列、相談する、相談している間、決定、履歴が英語で出る", async () => {
    const at = "2026-11-22T03:00:00Z";
    const { user, panel } = await openPanel(
      handlingDetail({
        handoverReason: "DECIDED",
        decisions: [makePrecedent({ decisionKind: "REJECT", language: "en" })],
        history: [
          {
            at,
            actor: "OWNER",
            event: "DECIDED",
            phase: "DECIDE",
            turn: "OPERATOR",
            awaiting: "HANDLING",
            ending: null,
          },
        ],
      }),
      undefined,
      {},
      true,
    );

    expect(
      screen.getByRole("region", { name: "In consultation 0items" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Why it came to staff: The spec owner decided"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("heading", { name: "Spec owner's decision" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Do not accept", {
        selector: ".op-decision-kind",
      }),
    ).toBeInTheDocument();
    await user.click(within(panel).getByText("Status history"));
    const row = within(
      within(panel).getByText("Status history").closest("details")!,
    ).getByRole("listitem");
    expect(row).toHaveTextContent("Spec owner");
    expect(row).toHaveTextContent("Spec owner decided");

    await user.click(within(panel).getByText("Consult"));
    expect(
      within(panel).getByText(
        "While you consult, it is the spec owner's turn. They can see what was found and AI's suggestion.",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
    ).toBeChecked();
    expect(within(panel).getByLabelText("Someone else")).toBeInTheDocument();
    expect(
      within(panel).getByLabelText("What you want to ask"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "Consult the spec owner" }),
    ).toBeDisabled();
  });

  it("相談している間の表示と、相談中の列のカードが英語で出る", async () => {
    cleanup();
    vi.unstubAllGlobals();
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    installFakeFetch({
      "GET /api/config": { body: makeConfig({ trace: false }) },
      "GET /api/operator/inquiries": {
        body: {
          unreadCount: 0,
          items: [
            makeItem({
              read: true,
              ...CONSULTATION_STATE,
              consultants: ["Owner A", "Owner B"],
            }),
          ],
        },
      },
      "GET /api/operator/inquiries/op-1": {
        body: consultingDetail({
          consultations: [
            makeConsultation({
              to: ["Owner A", "Owner B"],
              question: "Is it intended?",
            }),
          ],
        }),
      },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);
    const user = userEvent.setup();

    const card = await screen.findByRole("button", {
      name: /求人が一覧に出なくなった/,
    });
    expect(card).toHaveTextContent("Consulted: Owner A, Owner B");
    await user.click(card);
    const panel = await screen.findByRole("region", {
      name: "Inquiry details",
    });
    expect(
      await within(panel).findByText(
        "Waiting for the spec owner's decision (consulted: Owner A, Owner B)",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole("button", { name: "Withdraw the consultation" }),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(
        /Spec owner's turn \(Waiting for the spec owner's decision\)/,
      ),
    ).toBeInTheDocument();
  });
});
