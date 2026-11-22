import { describe, expect, it } from "vitest";
import {
  TYPE_OPTIONS,
  TYPE_QUESTION,
  groundedAnswer,
  makeFiledView,
  makeQuestion,
  makeStructuredView,
  makeTypeEntry,
  makeView,
} from "../test/fakes.ts";
import {
  composeStalled,
  firstQuestionOf,
  pageSections,
  readTextParam,
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

describe("pageSections", () => {
  it("問い合わせが無ければ、作成前の1問目だけ", () => {
    expect(pageSections(null)).toEqual([
      { kind: "question", questionId: "type" },
    ]);
  });

  it("入口の質問の間は、本人の文章、答え終えた質問（押せる）、次の質問の順に並べる", () => {
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

  it("言い直しは確定前と確定後で別の部分になり、確定後は答えた行が押せなくなる", () => {
    const open = pageSections(makeStructuredView());
    expect(open.at(-1)).toMatchObject({
      kind: "restatement",
      confirmed: false,
    });
    expect(open.filter((s) => s.kind === "entry")).toEqual([
      { kind: "entry", index: 0, interactive: false },
    ]);
    const confirmed = pageSections(makeStructuredView({ step: "ANSWERING" }));
    expect(kinds(confirmed).slice(-2)).toEqual(["restatement", "researching"]);
    expect(confirmed.at(-2)).toMatchObject({ confirmed: true });
  });

  it("結果の後は、送った件だけ案内を結果の上に出し、終了で終わりを足す", () => {
    const answered = makeStructuredView({
      step: "ANSWERED",
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(answered)).slice(-2)).toEqual([
      "restatement",
      "result",
    ]);

    const wrong = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      type: { id: "LOOKS_WRONG", label: "おかしいかもしれない", by: "ASKER" },
      answer: groundedAnswer,
    });
    expect(pageSections(wrong).slice(-3)).toEqual([
      { kind: "filedNotice", text: "lookWrong" },
      { kind: "result" },
      { kind: "closing" },
    ]);

    const spec = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(spec))).not.toContain("filedNotice");
  });

  it("「やってほしい」は、自分でできる手順が見つからず自動で送った場合だけ案内を出す", () => {
    const doForMe = {
      step: "CLOSED",
      outcome: "FILED",
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
    // 本人が「担当者に頼む」を選んだ場合（根拠があり、問いに答えている）は、終わりの文言が伝える
    const asked = makeStructuredView({ ...doForMe, answer: groundedAnswer });
    expect(kinds(pageSections(asked))).not.toContain("filedNotice");
  });

  it("答えを待たずに送った問い合わせでは、おかしいかもしれない件でも送った旨の案内を重ねない", () => {
    const view = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      type: { id: "LOOKS_WRONG", label: "おかしいかもしれない", by: "ASKER" },
      sentImmediately: true,
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(view))).not.toContain("filedNotice");
  });

  it("種類が決まらないまま終わった問い合わせ（type が null）でも組み立てられる", () => {
    const view = makeView({
      step: "CLOSED",
      outcome: "FILED",
      intake: { entries: [], next: null, complete: true },
    });
    expect(kinds(pageSections(view))).toEqual(["closing"]);
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
    const question = firstQuestionOf(TYPE_OPTIONS);
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
  it("API の canSendNow が真で、入口の質問の間でないときだけ出す", () => {
    expect(showSendNowFooter(null)).toBe(false);
    expect(showSendNowFooter(makeStructuredView({ step: "ANSWERING" }))).toBe(
      false,
    );
    expect(
      showSendNowFooter(
        makeStructuredView({ step: "ANSWERING", canSendNow: true }),
      ),
    ).toBe(true);
    expect(
      showSendNowFooter(
        makeStructuredView({ step: "ANSWERED", canSendNow: true }),
      ),
    ).toBe(true);
    // 入口の質問の間は、canSendNow が真でもエラーの表示の中に出す
    expect(showSendNowFooter(makeView({ canSendNow: true }))).toBe(false);
    // 言い直しの確認の画面では、作り直しを使い切ったときに部品の中へ出すので、下部には重ねない
    expect(
      showSendNowFooter(
        makeStructuredView({ canSendNow: true, revisionsLeft: 0 }),
      ),
    ).toBe(false);
    expect(showSendNowFooter(makeFiledView())).toBe(false);
  });
});

describe("composeStalled", () => {
  it("入口を終えたのに INTAKE のまま止まっているときだけ真になる", () => {
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

describe("readTextParam", () => {
  it("?text= を取り出し、無ければ空、長すぎれば4000文字で切る", () => {
    expect(readTextParam("?text=%E6%8E%B2%E8%BC%89")).toBe("掲載");
    expect(readTextParam("")).toBe("");
    expect(readTextParam("?other=1")).toBe("");
    expect(readTextParam(`?text=${"a".repeat(5000)}`)).toHaveLength(4000);
  });
});

describe("pageSections の説明失敗", () => {
  it("ANSWERING の間は answer があっても結果を並べず、調べている表示を保つ", () => {
    const view = makeStructuredView({
      step: "ANSWERING",
      answer: groundedAnswer,
    });
    const list = kinds(pageSections(view));
    expect(list).toContain("researching");
    expect(list).not.toContain("result");
  });
});

describe("pageSections のやり取り", () => {
  it("担当者に送って終わった問い合わせは、終わりの後に「いまの状況」とやり取りを並べる", () => {
    const list = kinds(pageSections(makeFiledView()));
    expect(list.slice(-2)).toEqual(["closing", "followup"]);
  });

  it("担当者に送っていない終わり方と、対応状況が無い古い応答には並べない", () => {
    expect(
      kinds(
        pageSections(makeView({ step: "CLOSED", outcome: "SELF_RESOLVED" })),
      ),
    ).not.toContain("followup");
    expect(
      kinds(
        pageSections(
          makeFiledView({ handlingStatus: undefined, messages: undefined }),
        ),
      ),
    ).not.toContain("followup");
  });

  it("まだ終わっていない問い合わせには並べない", () => {
    expect(
      kinds(pageSections(makeFiledView({ step: "ANSWERED" }))),
    ).not.toContain("followup");
  });
});
