import type { InquiryView, StructuredInquiry } from "../api/types.ts";

/** 画面の種類。 */
export type Screen =
  "INPUT" | "TRIAGE" | "STRUCTURE" | "ANSWERING" | "ANSWERED" | "CLOSED";

/**
 * InquiryView から表示すべき画面を決める。問い合わせが無ければ入力画面。
 * @param view 現在の問い合わせ（未作成なら null）
 */
export function screenOf(view: InquiryView | null): Screen {
  if (view === null) return "INPUT";
  switch (view.step) {
    case "TRIAGED":
      return "TRIAGE";
    case "STRUCTURED":
      return "STRUCTURE";
    case "ANSWERING":
      return "ANSWERING";
    case "ANSWERED":
      return "ANSWERED";
    case "CLOSED":
      return "CLOSED";
  }
}

/**
 * 画面に対応する進行表示（PROGRESS_STEPS）の位置を返す。
 * @param screen 現在の画面
 */
export function progressIndexOf(screen: Screen): number {
  switch (screen) {
    case "INPUT":
      return 0;
    case "TRIAGE":
      return 1;
    case "STRUCTURE":
      return 2;
    case "ANSWERING":
    case "ANSWERED":
      return 3;
    case "CLOSED":
      return 4;
  }
}

/**
 * 「今すぐ担当者に送る」を表示するか。問い合わせの作成後から CLOSED になるまで。
 * @param view 現在の問い合わせ
 */
export function canSendNow(view: InquiryView | null): boolean {
  return view !== null && view.step !== "CLOSED";
}

/**
 * 空白だけの入力を null にして API に送る形へ整える。
 * @param value 入力欄の値
 */
export function blankToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/** 整形結果の編集用の値（全項目が文字列）。 */
export interface StructuredDraft {
  targetSystem: string;
  goal: string;
  whatHappened: string;
  expectedResult: string;
  urgency: StructuredInquiry["urgency"];
}

/**
 * API の整形結果を編集用の値に変換する。
 * @param structured 整形結果
 */
export function toStructuredDraft(
  structured: StructuredInquiry,
): StructuredDraft {
  return {
    targetSystem: structured.targetSystem ?? "",
    goal: structured.goal,
    whatHappened: structured.whatHappened ?? "",
    expectedResult: structured.expectedResult ?? "",
    urgency: structured.urgency,
  };
}

/**
 * 編集用の値を API に送る整形結果に変換する。空欄は null、やりたいことは前後の空白を除く。
 * @param draft 編集中の値
 */
export function fromStructuredDraft(draft: StructuredDraft): StructuredInquiry {
  return {
    targetSystem: blankToNull(draft.targetSystem),
    goal: draft.goal.trim(),
    whatHappened: blankToNull(draft.whatHappened),
    expectedResult: blankToNull(draft.expectedResult),
    urgency: draft.urgency,
  };
}
