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

  const asking = view.awaiting === "INTAKE_ANSWER";
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
    const confirmed = view.awaiting !== "QUESTION_CONFIRMATION";
    sections.push({
      kind: "restatement",
      confirmed,
      token: confirmed
        ? "done"
        : `${view.revisionsLeft}:${view.structured.summary}`,
    });
  }

  if (view.awaiting === "INVESTIGATION") {
    sections.push({ kind: "researching" });
  }

  // 説明が失敗して調査の途中のままの間は、answer があっても結果として描かない
  if (view.answer !== null && view.awaiting !== "INVESTIGATION") {
    const notice = filedNoticeOf(view);
    if (notice !== null) sections.push({ kind: "filedNotice", text: notice });
    sections.push({ kind: "result" });
  }

  // 終わりの文言を出すのは、終わり方があるときだけ。担当者に渡しても、問い合わせは終わらない
  if (view.ending !== null) sections.push({ kind: "closing" });

  // 担当者に渡したことのある問い合わせは、「いまの状況」とやり取り、追記の入力欄を置く
  if (view.handedOverAt !== null) sections.push({ kind: "followup" });
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
    view.handedOverAt === null ||
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
 * 入口の質問の間と、言い直しを確かめている画面には出さない（作り直しを使い切った場合は、
 * 言い直しの部品の中の「このまま担当者に送る」だけを出す。AI の失敗で進めなくなった場合は、
 * エラーの表示の中に「担当者に送る」を出す）。
 * @param view 現在の問い合わせ
 */
export function showSendNowFooter(view: InquiryView | null): boolean {
  return (
    view !== null &&
    view.canSendNow &&
    view.awaiting !== "INTAKE_ANSWER" &&
    view.awaiting !== "QUESTION_CONFIRMATION"
  );
}

/**
 * 入口の質問を終えたのに、問いを組み立てられずに止まっているか（言い直しの失敗）。
 * このとき `canSendNow` は真で、「もう一度試す」と「担当者に送る」を出す。
 * @param view 現在の問い合わせ
 */
export function composeStalled(view: InquiryView | null): boolean {
  return (
    view !== null && view.awaiting === "INTAKE_ANSWER" && view.intake.complete
  );
}

/**
 * 結果を確かめている件の結果が、担当者が伝えたものか。AI の答えの後に担当者に渡した件が、
 * 結果を確かめる番に戻るのは、担当者が結果を伝えたときだけ（担当者に渡したことがなければ AI の答え）。
 * @param view 現在の問い合わせ
 */
export function resultFromOperator(view: InquiryView): boolean {
  return view.handedOverAt !== null;
}

/**
 * 追記の入力欄を出せるか。担当者に渡したことがあり、取り下げていない件。
 * 終わっている件（解決済み、回答済み、見送り）でも、追記で開き直せる。
 * @param view 現在の問い合わせ
 */
export function canAppend(view: InquiryView): boolean {
  return view.handedOverAt !== null && view.ending !== "WITHDRAWN";
}

/**
 * 取り下げるリンクを出せるか。終わっていない件だけ。
 * @param view 現在の問い合わせ（未作成なら null）
 */
export function canWithdraw(view: InquiryView | null): boolean {
  return view !== null && view.ending === null && view.phase !== "CLOSED";
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
