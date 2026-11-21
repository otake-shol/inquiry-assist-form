import { describe, expect, it } from "vitest";
import {
  groundedAnswer,
  makeFiledView,
  makeStructuredView,
  makeView,
} from "../test/fakes.ts";
import { canSendNow, pageSections, readTextParam, sectionKey } from "./page.ts";

const questions = [
  { id: "q1", text: "どの画面", options: ["一覧"] },
  { id: "q2", text: "いつ", options: [] },
];

/** 部分の種類を並べて返す。 */
const kinds = (sections: ReturnType<typeof pageSections>) =>
  sections.map((s) => s.kind);

describe("pageSections", () => {
  it("問い合わせが無ければ入力欄だけ", () => {
    expect(pageSections(null, 0)).toEqual([{ kind: "input" }]);
  });

  it("追加質問は最初に数を伝え、答えた分を要約にして次の1問だけ出す", () => {
    const view = makeView({ questions });
    expect(kinds(pageSections(view, 0))).toEqual([
      "utterance",
      "receipt",
      "questionsIntro",
      "question",
    ]);
    const sections = pageSections(view, 1);
    expect(kinds(sections)).toEqual([
      "utterance",
      "receipt",
      "questionsIntro",
      "answered",
      "question",
    ]);
    expect(sections[3]).toEqual({
      kind: "answered",
      index: 0,
      interactive: true,
    });
    expect(sections[4]).toEqual({ kind: "question", index: 1 });
  });

  it("全問に答えたら次の質問は出さず、質問が0個なら受け取りだけ", () => {
    expect(kinds(pageSections(makeView({ questions }), 2))).not.toContain(
      "question",
    );
    expect(kinds(pageSections(makeView(), 0))).toEqual([
      "utterance",
      "receipt",
    ]);
  });

  it("言い直しは確定前と確定後で別の部分になり、確定後は答えた行が押せなくなる", () => {
    const open = pageSections(makeStructuredView({ questions }), 2);
    expect(open.at(-1)).toMatchObject({
      kind: "restatement",
      confirmed: false,
    });
    expect(open.filter((s) => s.kind === "answered")).toEqual([
      { kind: "answered", index: 0, interactive: false },
      { kind: "answered", index: 1, interactive: false },
    ]);
    const confirmed = pageSections(
      makeStructuredView({ step: "ANSWERING", questions }),
      2,
    );
    expect(kinds(confirmed).slice(-2)).toEqual(["restatement", "researching"]);
    expect(confirmed.at(-2)).toMatchObject({ confirmed: true });
  });

  it("結果の後は、送った不具合の件だけ案内を結果の上に出し、終了で終わりを足す", () => {
    const answered = makeStructuredView({
      step: "ANSWERED",
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(answered, 0)).slice(-2)).toEqual([
      "restatement",
      "result",
    ]);

    const bug = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      category: "SUSPECTED_BUG",
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(bug, 0)).slice(-3)).toEqual([
      "filedNotice",
      "result",
      "closing",
    ]);

    const spec = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      category: "SPEC_QUESTION",
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(spec, 0))).not.toContain("filedNotice");
  });

  it("今すぐ送るで終わった問い合わせでは、不具合でも送った旨の案内を重ねない", () => {
    const view = makeStructuredView({
      step: "CLOSED",
      outcome: "FILED",
      category: "SUSPECTED_BUG",
      sentImmediately: true,
      answer: groundedAnswer,
    });
    expect(kinds(pageSections(view, 0))).not.toContain("filedNotice");
  });

  it("判定できなかった問い合わせ（category が null）でも組み立てられる", () => {
    const view = makeView({
      category: null,
      categoryByAi: null,
      step: "CLOSED",
      outcome: "FILED",
    });
    expect(kinds(pageSections(view, 0))).toEqual([
      "utterance",
      "receipt",
      "closing",
    ]);
  });
});

describe("sectionKey", () => {
  it("部分ごとに異なる識別子になり、同じ内容の言い直しは同じ識別子になる", () => {
    const keys = pageSections(makeStructuredView({ questions }), 2).map(
      sectionKey,
    );
    expect(new Set(keys).size).toBe(keys.length);
    expect(sectionKey({ kind: "question", index: 1 })).not.toBe(
      sectionKey({ kind: "question", index: 0 }),
    );
    expect(
      sectionKey({ kind: "restatement", confirmed: false, token: "2:a" }),
    ).not.toBe(
      sectionKey({ kind: "restatement", confirmed: false, token: "1:a" }),
    );
  });
});

describe("canSendNow", () => {
  it("作成前と CLOSED では出さず、それ以外の段階では出す", () => {
    expect(canSendNow(null)).toBe(false);
    expect(canSendNow(makeView({ step: "CLOSED" }))).toBe(false);
    for (const step of [
      "TRIAGED",
      "STRUCTURED",
      "ANSWERING",
      "ANSWERED",
    ] as const) {
      expect(canSendNow(makeView({ step }))).toBe(true);
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

describe("pageSections の説明失敗", () => {
  it("ANSWERING の間は answer があっても結果を並べず、調べている表示を保つ", () => {
    const view = makeStructuredView({
      step: "ANSWERING",
      answer: groundedAnswer,
    });
    const list = kinds(pageSections(view, 0));
    expect(list).toContain("researching");
    expect(list).not.toContain("result");
  });
});

describe("pageSections のやり取り", () => {
  it("担当者に送って終わった問い合わせは、終わりの後に「いまの状況」とやり取りを並べる", () => {
    const list = kinds(pageSections(makeFiledView(), 0));
    expect(list.slice(-2)).toEqual(["closing", "followup"]);
  });

  it("担当者に送っていない終わり方と、対応状況が無い古い応答には並べない", () => {
    expect(
      kinds(
        pageSections(makeView({ step: "CLOSED", outcome: "SELF_RESOLVED" }), 0),
      ),
    ).not.toContain("followup");
    expect(
      kinds(
        pageSections(
          makeFiledView({ handlingStatus: undefined, messages: undefined }),
          0,
        ),
      ),
    ).not.toContain("followup");
  });

  it("まだ終わっていない問い合わせには並べない", () => {
    expect(
      kinds(pageSections(makeFiledView({ step: "ANSWERED" }), 0)),
    ).not.toContain("followup");
  });
});
