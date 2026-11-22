import { describe, expect, it } from "vitest";
import {
  ASKER_REPLY_STATE,
  HANDED_OVER_AT,
  HANDLING_STATE,
  INVESTIGATION_STATE,
  RESULT_STATE,
  TYPE_OPTIONS,
  TYPE_QUESTION,
  closedState,
  groundedAnswer,
  makeFiledView,
  makeQuestion,
  makeStructuredView,
  makeTypeEntry,
  makeView,
} from "../test/fakes.ts";
import {
  canAppend,
  canWithdraw,
  composeStalled,
  firstQuestionOf,
  pageSections,
  readTextParam,
  resultFromOperator,
  sectionKey,
  showSendNowFooter,
} from "./page.ts";

/** 部分の種類を並べて返す。 */
const kinds = (sections: ReturnType<typeof pageSections>) =>
  sections.map((s) => s.kind);

const systemEntry = {
  questionId: "system",
  kind: "SYSTEM",
  text: "どのシステムですか",
  answer: { kind: "UNKNOWN" },
} as const;

const LOOKS_WRONG = {
  id: "LOOKS_WRONG",
  label: "おかしいかもしれない",
  by: "ASKER",
} as const;

describe("pageSections: 入口から AI の答えまで", () => {
  it("問い合わせが無ければ、作成前の1問目だけ", () => {
    expect(pageSections(null)).toEqual([
      { kind: "question", questionId: "type" },
    ]);
  });

  it("入口の質問に答えている間は、本人の文章、答え終えた質問（押せる）、次の質問の順に並べる", () => {
    const view = makeView({
      utterance: "掲載期限のことです",
      intake: {
        entries: [makeTypeEntry(), systemEntry],
        next: makeQuestion({ questionId: "function", kind: "FUNCTION" }),
        complete: false,
      },
    });
    expect(pageSections(view)).toEqual([
      { kind: "utterance" },
      { kind: "entry", index: 0, interactive: true },
      { kind: "entry", index: 1, interactive: true },
      { kind: "question", questionId: "function" },
    ]);
  });

  it("文章が無ければ吹き出しを置かず、入口が終わって次の質問が無ければ質問を置かない", () => {
    expect(kinds(pageSections(makeView()))).toEqual(["question"]);
    const stalled = makeView({
      intake: { entries: [makeTypeEntry()], next: null, complete: true },
    });
    expect(kinds(pageSections(stalled))).toEqual(["entry"]);
  });

  it("言い直しの確認と確定後で別の部分になり、確定後は答えた行が押せなくなる", () => {
    const open = pageSections(makeStructuredView());
    expect(open.at(-1)).toMatchObject({
      kind: "restatement",
      confirmed: false,
    });
    expect(open.filter((s) => s.kind === "entry")).toEqual([
      { kind: "entry", index: 0, interactive: false },
    ]);
    const confirmed = pageSections(makeStructuredView(INVESTIGATION_STATE));
    expect(kinds(confirmed).slice(-2)).toEqual(["restatement", "researching"]);
    expect(confirmed.at(-2)).toMatchObject({ confirmed: true });
  });

  it("AI の答えを確かめる間は、結果を並べるが、終わりの文言ややり取りは並べない", () => {
    const answered = makeStructuredView({
      ...RESULT_STATE,
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(answered)).slice(-2)).toEqual([
      "restatement",
      "result",
    ]);
    expect(kinds(pageSections(answered))).not.toContain("closing");
    expect(kinds(pageSections(answered))).not.toContain("followup");
  });

  it("調べている間は、answer があっても結果を並べず、調べている表示を保つ", () => {
    const view = makeStructuredView({
      ...INVESTIGATION_STATE,
      answer: groundedAnswer,
    });
    const list = kinds(pageSections(view));
    expect(list).toContain("researching");
    expect(list).not.toContain("result");
  });
});

describe("pageSections: 担当者に渡した後", () => {
  it("担当者に渡しても終わりの文言は出さず、「いまの状況」とやり取りを並べる", () => {
    const list = kinds(pageSections(makeFiledView()));
    expect(list).not.toContain("closing");
    expect(list.at(-1)).toBe("followup");
  });

  it("担当者の番、聞き返されている、担当者の結果が届いている、のどれでもやり取りを並べる", () => {
    for (const state of [HANDLING_STATE, ASKER_REPLY_STATE, RESULT_STATE]) {
      const view = makeFiledView({ ...state, answer: groundedAnswer });
      const list = kinds(pageSections(view));
      expect(list).toContain("followup");
      expect(list).not.toContain("closing");
    }
  });

  it("終わりの文言を出すのは、終わり方があるときだけ。担当者に渡したことがあれば、その後にやり取りも並べる", () => {
    for (const ending of [
      "RESOLVED",
      "ANSWERED",
      "DECLINED",
      "WITHDRAWN",
    ] as const) {
      const view = makeFiledView({ ...closedState(ending) });
      expect(kinds(pageSections(view)).slice(-2)).toEqual([
        "closing",
        "followup",
      ]);
    }
  });

  it("担当者に渡していない終わり方（AI の答えで解決、取り下げ）には、やり取りを並べない", () => {
    const solved = makeStructuredView({
      ...closedState("RESOLVED"),
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(solved)).slice(-2)).toEqual([
      "result",
      "closing",
    ]);
    const withdrawn = makeView({ ...closedState("WITHDRAWN") });
    expect(kinds(pageSections(withdrawn))).toEqual(["closing"]);
  });

  it("おかしいかもしれない件は、調べた結果を添えて渡したことを結果の上に出す", () => {
    const wrong = makeStructuredView({
      ...HANDLING_STATE,
      handedOverAt: HANDED_OVER_AT,
      type: LOOKS_WRONG,
      answer: groundedAnswer,
    });
    expect(pageSections(wrong).slice(-3)).toEqual([
      { kind: "filedNotice", text: "lookWrong" },
      { kind: "result" },
      { kind: "followup" },
    ]);

    const spec = makeStructuredView({
      ...HANDLING_STATE,
      handedOverAt: HANDED_OVER_AT,
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(spec))).not.toContain("filedNotice");
  });

  it("「やってほしい」は、自分でできる手順が見つからず自動で渡した場合だけ案内を出す", () => {
    const doForMe = {
      ...HANDLING_STATE,
      handedOverAt: HANDED_OVER_AT,
      type: { id: "DO_FOR_ME", label: "やってほしい", by: "ASKER" },
    } as const;
    const notFound = makeStructuredView({
      ...doForMe,
      answer: { ...groundedAnswer, grounded: false, askerExplanation: null },
    });
    expect(pageSections(notFound)).toContainEqual({
      kind: "filedNotice",
      text: "doForMe",
    });
    // 本人が「担当者に頼む」を選んだ場合（根拠があり、問いに答えている）は、案内を重ねない
    const asked = makeStructuredView({ ...doForMe, answer: groundedAnswer });
    expect(kinds(pageSections(asked))).not.toContain("filedNotice");
  });

  it("答えを待たずに渡した問い合わせでは、おかしいかもしれない件でも案内を重ねない", () => {
    const view = makeStructuredView({
      ...HANDLING_STATE,
      handedOverAt: HANDED_OVER_AT,
      type: LOOKS_WRONG,
      sentImmediately: true,
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(view))).not.toContain("filedNotice");
  });

  it("種類が決まらないまま渡した問い合わせ（type が null）でも組み立てられる", () => {
    const view = makeFiledView({
      intake: { entries: [], next: null, complete: true },
    });
    expect(kinds(pageSections(view))).toEqual(["followup"]);
  });
});

describe("sectionKey", () => {
  it("部分ごとに異なる識別子になり、同じ内容の言い直しは同じ識別子になる", () => {
    const keys = pageSections(makeStructuredView()).map(sectionKey);
    expect(new Set(keys).size).toBe(keys.length);
    expect(sectionKey({ kind: "question", questionId: "system" })).not.toBe(
      sectionKey({ kind: "question", questionId: "type" }),
    );
    // 作成前の1問目と、作成後に API が返す種類の質問は、同じ部分として扱う
    expect(pageSections(null)[0]).toEqual({
      kind: "question",
      questionId: TYPE_QUESTION.questionId,
    });
    expect(
      sectionKey({ kind: "restatement", confirmed: false, token: "2:a" }),
    ).not.toBe(
      sectionKey({ kind: "restatement", confirmed: false, token: "1:a" }),
    );
  });
});

describe("firstQuestionOf", () => {
  it("設定の種類から、名前と説明つきの選択肢を持つ1問目を作る", () => {
    const question = firstQuestionOf(TYPE_OPTIONS, "何をしたいですか");
    expect(question).toMatchObject({
      questionId: "type",
      kind: "TYPE",
      text: "何をしたいですか",
      allowsText: false,
      canSkip: false,
    });
    expect(question.options).toHaveLength(5);
    expect(question.options[0]).toEqual({
      id: "HOW_TO",
      label: "使い方を知りたい",
      hint: "操作の手順",
    });
  });
});

describe("showSendNowFooter", () => {
  it("API の canSendNow が真で、入口の質問に答えている間でないときだけ出す", () => {
    expect(showSendNowFooter(null)).toBe(false);
    expect(showSendNowFooter(makeStructuredView(INVESTIGATION_STATE))).toBe(
      false,
    );
    expect(
      showSendNowFooter(
        makeStructuredView({ ...INVESTIGATION_STATE, canSendNow: true }),
      ),
    ).toBe(true);
    expect(
      showSendNowFooter(
        makeStructuredView({ ...RESULT_STATE, canSendNow: true }),
      ),
    ).toBe(true);
    // 入口の質問の間は、canSendNow が真でもエラーの表示の中に出す
    expect(showSendNowFooter(makeView({ canSendNow: true }))).toBe(false);
    // 言い直しを確かめている画面にも出さない（作り直しを使い切った場合は、言い直しの部品の中に出す）
    expect(
      showSendNowFooter(
        makeStructuredView({ revisionsLeft: 0, canSendNow: true }),
      ),
    ).toBe(false);
    expect(showSendNowFooter(makeFiledView())).toBe(false);
  });
});

describe("composeStalled", () => {
  it("入口を終えたのに入口の質問のまま止まっているときだけ真になる", () => {
    expect(composeStalled(null)).toBe(false);
    expect(composeStalled(makeView())).toBe(false);
    expect(
      composeStalled(
        makeView({
          canSendNow: true,
          intake: { entries: [makeTypeEntry()], next: null, complete: true },
        }),
      ),
    ).toBe(true);
    expect(composeStalled(makeStructuredView())).toBe(false);
  });
});

describe("resultFromOperator", () => {
  it("担当者に渡したことがあれば、結果は担当者が伝えたもの。なければ AI の答え", () => {
    expect(resultFromOperator(makeStructuredView(RESULT_STATE))).toBe(false);
    expect(
      resultFromOperator(
        makeStructuredView({ ...RESULT_STATE, handedOverAt: HANDED_OVER_AT }),
      ),
    ).toBe(true);
  });
});

describe("canAppend と canWithdraw", () => {
  it("追記できるのは、担当者に渡したことがあり、取り下げていない件。終わっていても開き直せる", () => {
    expect(canAppend(makeFiledView())).toBe(true);
    expect(canAppend(makeFiledView(ASKER_REPLY_STATE))).toBe(true);
    for (const ending of ["RESOLVED", "ANSWERED", "DECLINED"] as const) {
      expect(canAppend(makeFiledView(closedState(ending)))).toBe(true);
    }
    expect(canAppend(makeFiledView(closedState("WITHDRAWN")))).toBe(false);
    // 担当者に渡したことがなければ追記できない
    expect(canAppend(makeStructuredView(RESULT_STATE))).toBe(false);
    expect(canAppend(makeView(closedState("RESOLVED")))).toBe(false);
    expect(canAppend(makeView(closedState("WITHDRAWN")))).toBe(false);
  });

  it("担当者に渡したことがなくても、返事がないまま「回答済み」で終わった件には、続きを送る欄を出す", () => {
    const answered = makeStructuredView({
      ...closedState("ANSWERED"),
      handedOverAt: null,
      answer: groundedAnswer,
    });
    expect(canAppend(answered)).toBe(true);
    expect(kinds(pageSections(answered)).slice(-3)).toEqual([
      "result",
      "closing",
      "followup",
    ]);
  });

  it("取り下げられるのは、終わっていない件だけ", () => {
    expect(canWithdraw(null)).toBe(false);
    expect(canWithdraw(makeView())).toBe(true);
    expect(canWithdraw(makeStructuredView())).toBe(true);
    expect(canWithdraw(makeFiledView())).toBe(true);
    for (const ending of [
      "RESOLVED",
      "ANSWERED",
      "DECLINED",
      "WITHDRAWN",
    ] as const) {
      expect(canWithdraw(makeFiledView(closedState(ending)))).toBe(false);
    }
  });
});

describe("readTextParam", () => {
  it("?text= を取り出し、無ければ空、長すぎれば4000文字で切る", () => {
    expect(readTextParam("?text=%E6%8E%B2%E8%BC%89")).toBe("掲載");
    expect(readTextParam("")).toBe("");
    expect(readTextParam("?other=1")).toBe("");
    expect(readTextParam(`?text=${"a".repeat(5000)}`)).toHaveLength(4000);
  });
});
