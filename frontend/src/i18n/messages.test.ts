import { describe, expect, it } from "vitest";
import { en } from "./en.ts";
import { ja } from "./ja.ts";
import { MESSAGES, languageName, messagesOf } from "./messages.ts";

/**
 * 辞書の葉（文字列、関数、配列の要素）の道筋を、すべて集める。
 * @param value 辞書（またはその一部）
 * @param path ここまでの道筋
 */
function leaves(value: unknown, path = ""): [string, unknown][] {
  if (
    typeof value === "function" ||
    typeof value !== "object" ||
    value === null
  ) {
    return [[path, value]];
  }
  return Object.entries(value).flatMap(([key, child]) =>
    leaves(child, path === "" ? key : `${path}.${key}`),
  );
}

describe("辞書", () => {
  it("2つの辞書のキーが、配列の長さも含めて揃っている", () => {
    const keys = (dict: unknown) => leaves(dict).map(([path]) => path);
    expect(keys(en)).toEqual(keys(ja));
  });

  it("同じキーは、同じ種類（文字列、関数、null）の値を持つ", () => {
    const kinds = (dict: unknown) =>
      leaves(dict).map(([path, value]) => [
        path,
        value === null ? "null" : typeof value,
      ]);
    expect(kinds(en)).toEqual(kinds(ja));
  });

  it("英語の辞書には、日本語の文字が残っていない（モックのタグは辞書の外にある）", () => {
    const japanese = /[぀-ヿ㐀-鿿＀-￯]/;
    const found: string[] = [];
    for (const [path, value] of leaves(en)) {
      if (typeof value === "string" && japanese.test(value)) {
        found.push(`${path}: ${value}`);
      }
      // 文言を返す関数は、日本語の文字を含まない文を返す
      if (typeof value === "function") {
        const call = value as (...args: unknown[]) => unknown;
        let text: unknown;
        try {
          text = call("a", "b", "c");
        } catch {
          // 配列を受け取る関数（スキルの内訳）
          text = call(["a"], ["b"]);
        }
        if (japanese.test(String(text))) found.push(`${path}(): ${text}`);
      }
    }
    expect(found).toEqual([]);
  });

  it("英語の文言に、感嘆符と絵文字を使わない", () => {
    const flagged = leaves(en)
      .filter(([, v]) => typeof v === "string")
      .filter(([, v]) => /[!！]|\p{Extended_Pictographic}/u.test(v as string));
    expect(flagged).toEqual([]);
  });

  it("日本語の辞書にも、感嘆符と絵文字を使わない", () => {
    const flagged = leaves(ja)
      .filter(([, v]) => typeof v === "string")
      .filter(([, v]) => /[!！]|\p{Extended_Pictographic}/u.test(v as string));
    expect(flagged).toEqual([]);
  });

  it("言語の名前は、その言語自身の名前で、持たない言語は識別子のまま返す", () => {
    expect(languageName("ja")).toBe("日本語");
    expect(languageName("en")).toBe("English");
    expect(languageName("fr")).toBe("fr");
    expect(messagesOf("en")).toBe(MESSAGES.en);
    expect(messagesOf("fr")).toBe(MESSAGES.ja);
  });
});

describe("英語の言葉の対応表（docs/ux-principles.md）", () => {
  it("本人の画面の主な言葉が、表に揃っている", () => {
    expect(en.text.appTitle).toBe("Inquiry Assist");
    expect(en.user.myInquiries).toBe("My inquiries");
    expect(en.asker.columns).toEqual({
      YOURS: "Your turn",
      THEIRS: "Their turn",
      CLOSED: "Closed",
    });
    expect(en.asker.endings).toEqual({
      RESOLVED: "Resolved",
      ANSWERED: "Answered",
      DECLINED: "Declined",
      WITHDRAWN: "Withdrawn",
    });
    expect(en.text.writeLink).toBe("Write it in your own words");
    expect(en.text.unknown).toBe("Not sure");
    expect(en.text.restatementLead).toBe("Here is what I understood.");
    expect(en.text.restatementOk).toBe("That's right");
    expect(en.text.restatementNo).toBe("Not quite");
    expect(en.text.resultTitle).toBe("What I found");
    expect(en.text.memoTitle).toBe("Details for the support team");
    expect(en.user.progressQuestion).toBe(
      "Can you now move forward with what you wanted to do?",
    );
    expect(en.text.resolved).toBe("This solved it");
    expect(en.user.stillUnresolved).toBe("Not solved yet");
    expect(en.user.withdrawLink).toBe("Withdraw this inquiry");
    expect(en.asker.awaiting.HANDLING).toBe(
      "The support team is looking into it",
    );
  });

  it("仕様の持ち主への相談の言葉が、表に揃っている", () => {
    expect(en.asker.awaiting.CONSULTATION).toBe(
      "The support team is checking with the person who decides how this should work",
    );
    expect(ja.asker.awaiting.CONSULTATION).toBe(
      "担当者が、仕様を決める人に確認しています",
    );
    expect(en.op.columns.CONSULTING).toBe("In consultation");
    expect(en.op.actors.OWNER).toBe("Spec owner");
    expect(en.op.events.CONSULTED).toBe("Consulted the spec owner");
    expect(en.op.events.DECIDED).toBe("Spec owner decided");
    expect(en.op.events.CONSULTATION_CANCELED).toBe("Consultation withdrawn");
    expect(en.op.handoverReasons.DECIDED).toBe("The spec owner decided");
    expect(en.op.consultSubmit).toBe("Consult the spec owner");
    expect(en.op.consultCancel).toBe("Withdraw the consultation");
    expect(en.op.decisionTitle).toBe("Spec owner's decision");
    expect(en.op.decisionKinds).toEqual({
      AS_INTENDED: "As intended",
      DEFECT: "Defect",
      ACCEPT: "Accept",
      REJECT: "Do not accept",
      ADOPT: "Adopt",
      DEFER: "Not now",
      OTHER: "Other",
    });
    expect(ja.op.decisionKinds).toEqual({
      AS_INTENDED: "意図どおり",
      DEFECT: "不具合",
      ACCEPT: "受ける",
      REJECT: "受けない",
      ADOPT: "採用する",
      DEFER: "見送る",
      OTHER: "その他",
    });
    expect(en.owner.listTitle).toBe("Consultations waiting for a decision");
    expect(en.owner.refresh).toBe("Refresh");
    expect(en.owner.submit).toBe("Record the decision");
    expect(en.owner.recorded).toBe(
      "The decision was recorded. It is staff's turn again.",
    );
    expect(ja.owner.recorded).toBe(
      "決定を記録しました。担当者の番に戻りました。",
    );
  });

  it("先例の言葉が、表に揃っている", () => {
    expect(en.op.precedentsTitle).toBe("Precedents for the same place");
    expect(en.op.precedentsEmpty).toBe(
      "There are no precedents for the same place yet.",
    );
    expect(en.op.precedentsNav).toBe("Precedents");
    expect(en.op.adviceBasedOnPrecedents).toBe("Based on precedents");
    expect(en.precedents.title).toBe("Precedents");
    expect(en.precedents.empty).toBe("There are no precedents yet.");
    expect(en.precedents.openOriginal).toBe("Open the original inquiry");
    expect(ja.precedents.empty).toBe("先例は、まだありません。");
  });

  it("担当者の画面の主な言葉が、表に揃っている", () => {
    expect(en.op.columns).toEqual({
      OPERATOR: "Staff's turn",
      CONSULTING: "In consultation",
      ASKER: "Requester's turn",
      CLOSED: "Closed",
    });
    expect(en.op.phases).toEqual({
      INTAKE: "Intake",
      KNOW: "Understand",
      DECIDE: "Decide",
      ACT: "Carry out",
      CLOSED: "Closed",
    });
    expect(en.op.sendAsk).toBe("Ask the requester");
    expect(en.op.sendResult).toBe("Send the result");
    expect(en.op.declineSummary).toBe("Decline");
    expect(en.op.declineSubmit).toBe("Close as declined");
    expect(en.op.placementSummary).toBe("Reclassify");
    expect(en.op.historySummary).toBe("Status history");
  });

  it("本人の画面の英語に、内部の言葉を使わない", () => {
    const internal = /\b(phase|turn|ticket|triage|skill)\b/i;
    // 列の見出し（Your turn など）は、対応表で決まった言葉
    const { columns: _columns, ...asker } = en.asker;
    void _columns;
    const userSide = [en.text, en.user, asker, en.errors];
    const found = leaves(userSide)
      .filter(([, v]) => typeof v === "string" && internal.test(v as string))
      .map(([path]) => path);
    expect(found).toEqual([]);
  });

  it("見出しとボタンは、文の先頭だけを大文字にする（主な項目）", () => {
    for (const label of [
      en.text.writeLink,
      en.text.restatementNo,
      en.text.resultTitle,
      en.user.withdrawLink,
      en.op.sendAsk,
      en.op.declineSubmit,
      en.op.historySummary,
    ]) {
      const words = label.split(" ").slice(1);
      expect(words.filter((w) => /^[A-Z][a-z]/.test(w) && w !== "I")).toEqual(
        [],
      );
    }
  });
});
