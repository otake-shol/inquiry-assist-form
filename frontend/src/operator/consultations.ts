import type { Consultation, OperatorDetail } from "../api/types.ts";

/** 相談先の名前の最大数（契約書: 1〜5人）。 */
export const MAX_CONSULTANTS = 5;

/** 相談先の名前の最大文字数（契約書: 1〜100文字）。 */
export const MAX_NAME_LENGTH = 100;

/**
 * 決定を待っている（`OPEN` の）相談。同時に1つだけなので、最後の1件を返す。
 * @param detail 担当者向けの詳細
 * @returns なければ null
 */
export function openConsultationOf(
  detail: Pick<OperatorDetail, "consultations">,
): Consultation | null {
  const open = (detail.consultations ?? []).filter(
    (consultation) => consultation.status === "OPEN",
  );
  return open.length === 0 ? null : open[open.length - 1];
}

/** 状況の初期値の長さの上限（本人が書いた文章の先頭だけを使うとき。契約書: 200文字）。 */
const SITUATION_HEAD_LENGTH = 200;

/**
 * 状況の入力欄の初期値。言い直しの文章。なければ本人が書いた文章の先頭200文字。それもなければ相談の文章。
 * @param detail 担当者向けの詳細
 * @param consultation 決定を待っている相談
 */
export function initialSituationOf(
  detail: Pick<OperatorDetail, "structured" | "utterance">,
  consultation: Pick<Consultation, "question"> | null,
): string {
  if (detail.structured !== null) return detail.structured.summary;
  if (detail.utterance !== null) {
    return detail.utterance.slice(0, SITUATION_HEAD_LENGTH);
  }
  return consultation === null ? "" : consultation.question;
}
