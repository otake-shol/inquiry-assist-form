import type { InquiryCategory, InquiryView, SourceType } from "../api/types.ts";

/**
 * 画面に出す文言を集約する。言葉づかいの仕様は docs/ux-principles.md。
 * 「0次回答」「種別」「判定」「整形」「起票」「出典」は画面に出さない。
 */

/** 受け取りの1行に使う、内容の呼び名。 */
export const CATEGORY_LABELS: Record<InquiryCategory, string> = {
  SPEC_QUESTION: "仕様の確認・相談",
  SUSPECTED_BUG: "不具合かもしれない件",
  WORK_REQUEST: "作業の依頼",
  INCIDENT: "障害かもしれない件",
};

/** 変更の選択肢の並び順。 */
export const CATEGORY_ORDER: InquiryCategory[] = [
  "SPEC_QUESTION",
  "SUSPECTED_BUG",
  "WORK_REQUEST",
  "INCIDENT",
];

/**
 * 受け取りの1行の文言。
 * @param category 受け取った内容の分類（判定できなかった場合は null）
 */
export function receiptText(category: InquiryCategory | null): string {
  return category === null
    ? "内容を受け取りました"
    : `${CATEGORY_LABELS[category]}として受け取りました`;
}

/**
 * 追加質問の最初に伝える数。
 * @param count 質問の数
 */
export function questionsIntroText(count: number): string {
  return `${count}つだけ教えてください`;
}

/** 根拠にした資料の種類の呼び名。 */
export const SOURCE_TYPE_LABELS: Record<SourceType, string> = {
  CODE: "コード",
  FAQ: "FAQ",
  DOCUMENT: "ドキュメント",
  GLOSSARY: "用語対応表",
};

/** 画面の固定文言。 */
export const TEXT = {
  appTitle: "問い合わせアシスト",
  inputLabel:
    "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。",
  send: "送信する",
  change: "変更",
  changeLegend: "どの内容として受け取りますか",
  unknown: "わからない",
  next: "次へ",
  rewindHint: "（押すと答え直せます）",
  resendAnswers: "もう一度送る",
  working: "少しお待ちください。",
  restatementLead: "つまり、こういうことですね。",
  restatementOk: "合っています",
  restatementNo: "少し違います",
  noteLabel: "どこが違いますか？",
  noteSubmit: "作り直してもらう",
  cancel: "やめる",
  rewrite: "自分で書き直す",
  rewriteLabel: "言い直しを書き直してください",
  rewriteSubmit: "この内容で確定する",
  noRevisionsLeft:
    "うまく汲み取れませんでした。書いていただいた内容のまま担当者に送ることもできます。",
  sendAsIs: "このまま担当者に送る",
  backToQuestions: "前の質問に戻る",
  researching: "調べています",
  resultTitle: "調べた結果",
  notFound: "手元の資料からは答えを見つけられませんでした。",
  citationsTitle: (n: number) => `根拠にした資料（${n}件）`,
  memoTitle: "担当者向けの詳しい内容",
  memoFindings: "分かったこと",
  memoNotFound: "調べたが見つからなかったこと",
  memoNextChecks: "次に確認すること",
  resolved: "解決しました",
  notResolved: "まだ解決していません（担当者に送る）",
  sendToStaff: "担当者に送る",
  bugFiled: "いまの動きを調べました。この内容を添えて担当者に送りました。",
  retryResearch: "もう一度調べる",
  sendNow: "今すぐ担当者に送る",
  sendNowHint: "待たずに人に見てもらいたいときはこちら。",
  restart: "新しい問い合わせを書く",
} as const;

/** AI を使えないときのエラー文。 */
export const AI_ERROR_TEXT: Record<string, string> = {
  AI_REFUSED: "この内容は自動では調べられませんでした。担当者に直接送れます。",
  AI_UNAVAILABLE: "いま自動の調査を使えません。担当者に直接送れます。",
};

/** 上記以外のエラーの汎用文言。 */
export const GENERIC_ERROR_TEXT =
  "うまく処理できませんでした。時間をおいてもう一度お試しください。";

/** 問い合わせがまだ保存されていないときの案内。 */
export const AI_ERROR_BEFORE_CREATE_HINT =
  "内容を少し変えるか、時間をおいてもう一度送信してください。";

/** 終わりの文言。 */
export interface ClosingText {
  lead: string;
  /** 障害の案内文（API の incidentGuidance）。目立たせて表示する。 */
  incident: string | null;
  /** 送った後の案内（API の filedGuidance）。 */
  guidance: string | null;
}

/**
 * 結果ごとの終わりの文言を決める。
 * @param view 終了した問い合わせ
 * @param filedGuidance 担当者に送った後の案内文（API の設定）
 */
export function closingText(
  view: InquiryView,
  filedGuidance: string | null,
): ClosingText {
  switch (view.outcome) {
    case "SELF_RESOLVED":
      return {
        lead: "解決してよかったです。この内容は記録しておきます。",
        incident: null,
        guidance: null,
      };
    case "FILED":
      return {
        lead: view.sentImmediately
          ? "担当者に送りました。書いていただいた内容をそのまま添えています。"
          : view.answer !== null
            ? "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。"
            : "担当者に送りました。ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
        incident: null,
        guidance: filedGuidance,
      };
    case "INCIDENT_REDIRECTED":
      return {
        lead: "障害の連絡は専用の窓口で受け付けています。",
        incident: view.incidentGuidance,
        guidance: null,
      };
    default:
      return {
        lead: "この問い合わせは終了しました。",
        incident: null,
        guidance: null,
      };
  }
}
