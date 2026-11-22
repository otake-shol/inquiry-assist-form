import type { Awaiting, Declined, Ending, MessageKind } from "../api/types.ts";

/**
 * 送った後の画面（ページの上部、一覧、やり取り）の文言。言葉づかいの仕様は docs/ux-principles.md。
 * 本人向けの画面なので、内部の言葉（「0次回答」「種別」「起票」「出典」「スキル」「モデル」）は使わない。
 */
export const USER_TEXT = {
  navLabel: "ページの移動",
  myInquiries: "送った問い合わせ",
  unreadReplies: (count: number) => `未読の返信 ${count}件`,
  loading: "読み込んでいます…",
  loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
  notFound: "この問い合わせは見つかりませんでした",
  listEmpty: "送った問い合わせはまだありません。",
  unreadReplyMark: "未読の返信",
  currentStatus: "いまの状況",
  exchange: "やり取り",
  noReplyYet: "担当者からの返信は、ここに表示されます。",
  appendLabel: "追加で伝える",
  appendSubmit: "送る",
  replyLabel: "担当者の質問に答える",
  reopenNote: "送ると、担当者の番で開き直します。",
  progressQuestion: "やりたかったことは、進められそうですか。",
  stillUnresolved: "まだ解決していません",
  unresolvedNoteLabel: "どこが解決していませんか（任意）",
  unresolvedSend: "担当者に送る",
  withdrawLink: "この問い合わせを取り下げる",
  withdrawConfirm: "取り下げますか。取り下げた後は、続きを送れません。",
  withdrawDo: "取り下げる",
  withdrawCancel: "やめる",
  restartOther: "別の問い合わせを書く",
  traceSummary: "裏側を見る（開発者向け）",
  devToOperator: "開発用: 担当者の画面へ",
  devToAsker: "開発用: 本人の画面へ",
} as const;

/** 本人のページの「いまの状況」とボードのカードに出す、待っていることの言葉。 */
const ASKER_AWAITING_LABELS: Record<Awaiting, string> = {
  INTAKE_ANSWER: "入力の途中です",
  QUESTION_CONFIRMATION: "問いの確認をお願いします",
  INVESTIGATION: "調べています",
  RESULT_CONFIRMATION: "結果を確かめてください",
  HANDLING: "担当者が確認しています",
  ASKER_REPLY: "担当者から質問が届いています",
};

/** 結果を確かめている件のうち、担当者が結果を伝えたものの言葉。 */
const OPERATOR_RESULT_LABEL = "担当者から結果が届いています";

/** 終わり方の、本人に見せる言葉。 */
export const ASKER_ENDING_LABELS: Record<Ending, string> = {
  RESOLVED: "解決済み",
  ANSWERED: "回答済み",
  DECLINED: "見送り",
  WITHDRAWN: "取り下げ",
};

/**
 * 本人のページの「いまの状況」とボードのカードに出す、本人に見せる言葉（docs/ux-principles.md の表）。
 * 終わっていれば終わり方、そうでなければ待っていること。結果を確かめている件は、AI の答えか
 * 担当者の結果かで言葉が分かれる。
 * @param state 待っていることと終わり方
 * @param resultFromOperator 結果を確かめている件の結果が、担当者が伝えたものか
 */
export function askerStatusLabel(
  state: { awaiting: Awaiting | null; ending: Ending | null },
  resultFromOperator: boolean,
): string {
  if (state.ending !== null) return ASKER_ENDING_LABELS[state.ending];
  if (state.awaiting === null) return ASKER_ENDING_LABELS.WITHDRAWN;
  if (state.awaiting === "RESULT_CONFIRMATION" && resultFromOperator) {
    return OPERATOR_RESULT_LABEL;
  }
  return ASKER_AWAITING_LABELS[state.awaiting];
}

/** 終わりの文言。 */
export interface EndingText {
  lead: string;
  /** 続けて示す、担当者が書いた理由（見送りだけ） */
  reason: string | null;
}

/**
 * 終わり方ごとの終わりの文言（docs/ux-principles.md の表）。
 * @param ending 終わり方
 * @param declined 見送りの理由（見送りで終わった件だけ）
 */
export function endingText(
  ending: Ending,
  declined: Declined | null,
): EndingText {
  switch (ending) {
    case "RESOLVED":
      return {
        lead: "解決してよかったです。この内容は記録しておきます。",
        reason: null,
      };
    case "ANSWERED":
      return {
        lead: "お返事がなかったため、いったん終了しました。解決していなければ、このページから続きを送れます。",
        reason: null,
      };
    case "DECLINED":
      return {
        lead: "担当者から、今回は対応を見送るとの連絡がありました。",
        reason: declined?.reason ?? null,
      };
    case "WITHDRAWN":
      return { lead: "この問い合わせは取り下げました。", reason: null };
  }
}

/** やり取りの発言の見出し（担当者の発言の種類）。本人の追記には見出しを付けない。 */
export const MESSAGE_KIND_LABELS: Record<MessageKind, string | null> = {
  ASK: "質問",
  RESULT: "結果",
  DECLINE: "見送りの連絡",
  NOTE: null,
};
