import type { InquiryView } from "../api/types.ts";

/** ページに並べる部分。上から順に並び、済んだやり取りは要約として残る。 */
export type Section =
  | { kind: "input" }
  | { kind: "utterance" }
  | { kind: "receipt" }
  | { kind: "questionsIntro"; count: number }
  | { kind: "answered"; index: number; interactive: boolean }
  | { kind: "question"; index: number }
  | { kind: "restatement"; confirmed: boolean; token: string }
  | { kind: "researching" }
  | { kind: "filedNotice" }
  | { kind: "result" }
  | { kind: "closing" }
  | { kind: "followup" };

/**
 * InquiryView と回答済みの数から、ページに何を並べるかを決める純粋関数。
 * @param view 現在の問い合わせ（未作成なら null）
 * @param answerCount 追加質問に答えた数（画面側で持つ）
 */
export function pageSections(
  view: InquiryView | null,
  answerCount: number,
): Section[] {
  if (view === null) return [{ kind: "input" }];

  const sections: Section[] = [{ kind: "utterance" }, { kind: "receipt" }];
  const total = view.questions.length;
  const asking = view.step === "TRIAGED";

  if (asking && total > 0) {
    sections.push({ kind: "questionsIntro", count: total });
  }
  const answered = Math.min(answerCount, total);
  for (let index = 0; index < answered; index++) {
    sections.push({ kind: "answered", index, interactive: asking });
  }
  if (asking && answered < total) {
    sections.push({ kind: "question", index: answered });
  }

  if (view.structured !== null) {
    const confirmed = view.step !== "STRUCTURED";
    sections.push({
      kind: "restatement",
      confirmed,
      token: confirmed
        ? "done"
        : `${view.revisionsLeft}:${view.structured.summary}`,
    });
  }

  if (view.step === "ANSWERING") sections.push({ kind: "researching" });

  // 説明が失敗して ANSWERING のままの間は、answer があっても結果として描かない
  if (view.answer !== null && view.step !== "ANSWERING") {
    const bugFiled =
      view.step === "CLOSED" &&
      view.outcome === "FILED" &&
      view.category === "SUSPECTED_BUG" &&
      !view.sentImmediately;
    if (bugFiled) sections.push({ kind: "filedNotice" });
    sections.push({ kind: "result" });
  }

  if (view.step === "CLOSED") sections.push({ kind: "closing" });

  // 担当者に送った問い合わせは、終わりの後に「いまの状況」とやり取りを置く。
  // 対応状況が無い古い応答では、やり取りがないものとして何も足さない。
  if (
    view.step === "CLOSED" &&
    view.outcome === "FILED" &&
    view.handlingStatus != null
  ) {
    sections.push({ kind: "followup" });
  }
  return sections;
}

/**
 * 部分の識別子。React の key とスクロール先の特定に使う。
 * @param section ページの部分
 */
export function sectionKey(section: Section): string {
  switch (section.kind) {
    case "answered":
    case "question":
      return `${section.kind}-${section.index}`;
    case "restatement":
      return `restatement-${section.token}`;
    default:
      return section.kind;
  }
}

/**
 * 「今すぐ担当者に送る」を出せる問い合わせか。作成後から CLOSED になるまで。
 * @param view 現在の問い合わせ
 */
export function canSendNow(view: InquiryView | null): boolean {
  return view !== null && view.step !== "CLOSED";
}

/** 入力欄の最大文字数（契約書: 1〜4000文字）。 */
export const MAX_UTTERANCE_LENGTH = 4000;

/**
 * URL の `?text=` を入力欄の初期値として取り出す。
 * @param search `location.search` の値
 */
export function readTextParam(search: string): string {
  const text = new URLSearchParams(search).get("text") ?? "";
  return text.slice(0, MAX_UTTERANCE_LENGTH);
}
