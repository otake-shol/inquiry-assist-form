import type { InquiryView, IntakeQuestion, TypeOption } from "../api/types.ts";
import { TEXT } from "../text/labels.ts";

/** ページに並べる部分。上から順に並び、済んだやり取りは要約として残る。 */
export type Section =
  | { kind: "utterance" }
  | { kind: "entry"; index: number; interactive: boolean }
  | { kind: "question"; questionId: string }
  | { kind: "restatement"; confirmed: boolean; token: string }
  | { kind: "researching" }
  | { kind: "filedNotice"; text: "lookWrong" | "doForMe" }
  | { kind: "result" }
  | { kind: "closing" }
  | { kind: "followup" };

/** 問い合わせの作成前に画面が描く、1問目の質問の id（API の種類の質問と同じ）。 */
export const FIRST_QUESTION_ID = "type";

/**
 * 問い合わせの作成前に出す1問目を、`GET /api/config` の種類から作る。
 * 作成後の質問は API が返す `intake.next` を描くだけで、画面は順番を決めない。
 * @param types 設定の種類（聞く順）
 */
export function firstQuestionOf(types: TypeOption[]): IntakeQuestion {
  return {
    questionId: FIRST_QUESTION_ID,
    kind: "TYPE",
    text: TEXT.firstQuestion,
    options: types.map((type) => ({
      id: type.id,
      label: type.label,
      hint: type.hint,
    })),
    allowsText: false,
    canSkip: false,
  };
}

/**
 * InquiryView から、ページに何を並べるかを決める純粋関数。
 * 入口の質問の間は、本人の文章、答え終えた質問、次の質問（`intake.next`）の順に並べる。
 * @param view 現在の問い合わせ（未作成なら null）
 */
export function pageSections(view: InquiryView | null): Section[] {
  if (view === null) {
    return [{ kind: "question", questionId: FIRST_QUESTION_ID }];
  }

  const sections: Section[] = [];
  if (view.utterance !== null) sections.push({ kind: "utterance" });

  const asking = view.step === "INTAKE";
  view.intake.entries.forEach((_, index) => {
    sections.push({ kind: "entry", index, interactive: asking });
  });
  if (asking && view.intake.next !== null) {
    sections.push({
      kind: "question",
      questionId: view.intake.next.questionId,
    });
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
    const notice = filedNoticeOf(view);
    if (notice !== null) sections.push({ kind: "filedNotice", text: notice });
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
 * 調べた結果の上に、調べた内容を添えて担当者に送ったことを示す案内の種類。
 * 「おかしいかもしれない」は調べたうえで必ず送る。「やってほしい」は、自分でできる手順が
 * 見つからず自動で送った場合だけ（本人が「担当者に頼む」を選んだ場合は終わりの文言が伝える）。
 * 答えを待たずに送った場合は、調べた内容を添えていないので出さない。
 * @param view 結果のある問い合わせ
 */
function filedNoticeOf(view: InquiryView): "lookWrong" | "doForMe" | null {
  if (
    view.step !== "CLOSED" ||
    view.outcome !== "FILED" ||
    view.sentImmediately ||
    view.answer === null
  ) {
    return null;
  }
  if (view.type?.id === "LOOKS_WRONG") return "lookWrong";
  if (
    view.type?.id === "DO_FOR_ME" &&
    !(view.answer.grounded && (view.answer.answered ?? view.answer.grounded))
  ) {
    return "doForMe";
  }
  return null;
}

/**
 * 部分の識別子。React の key とスクロール先の特定に使う。
 * @param section ページの部分
 */
export function sectionKey(section: Section): string {
  switch (section.kind) {
    case "entry":
      return `entry-${section.index}`;
    case "question":
      return `question-${section.questionId}`;
    case "restatement":
      return `restatement-${section.token}`;
    default:
      return section.kind;
  }
}

/**
 * ページの下部に「答えを待たずに担当者に送る」を出すか。API の `canSendNow` が真のときだけ。
 * 入口の質問の間は出さない（AI の失敗で進めなくなった場合は、エラーの表示の中に「担当者に送る」を出す）。
 * @param view 現在の問い合わせ
 */
export function showSendNowFooter(view: InquiryView | null): boolean {
  // 言い直しの確認の画面では、作り直しを使い切ったときに部品の中へ
  // 「このまま担当者に送る」を出す。下部に重ねて置かない
  return (
    view !== null &&
    view.canSendNow &&
    view.step !== "INTAKE" &&
    view.step !== "STRUCTURED"
  );
}

/**
 * 入口の質問を終えたのに、問いを組み立てられずに止まっているか（言い直しの失敗）。
 * このとき `canSendNow` は真で、「もう一度試す」と「担当者に送る」を出す。
 * @param view 現在の問い合わせ
 */
export function composeStalled(view: InquiryView | null): boolean {
  return view !== null && view.step === "INTAKE" && view.intake.complete;
}

/** 「文章で書く」の入力欄の最大文字数（契約書: 1〜4000文字）。 */
export const MAX_UTTERANCE_LENGTH = 4000;

/**
 * URL の `?text=` を入力欄の初期値として取り出す。
 * @param search `location.search` の値
 */
export function readTextParam(search: string): string {
  const text = new URLSearchParams(search).get("text") ?? "";
  return text.slice(0, MAX_UTTERANCE_LENGTH);
}
