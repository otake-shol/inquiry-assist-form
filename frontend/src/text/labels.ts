import type {
  InquiryCategory,
  InquiryOutcome,
  SourceType,
  Urgency,
} from "../api/types.ts";

/** 種別の表示順と日本語名。 */
export const CATEGORY_LABELS: Record<InquiryCategory, string> = {
  SPEC_QUESTION: "仕様の確認・相談",
  SUSPECTED_BUG: "不具合の疑い",
  WORK_REQUEST: "作業依頼",
  INCIDENT: "障害",
};

/** 種別の並び順。 */
export const CATEGORY_ORDER: InquiryCategory[] = [
  "SPEC_QUESTION",
  "SUSPECTED_BUG",
  "WORK_REQUEST",
  "INCIDENT",
];

/** 「〜として扱います」の文言。 */
export const CATEGORY_TREATMENT: Record<InquiryCategory, string> = {
  SPEC_QUESTION: "仕様の確認・相談として扱います",
  SUSPECTED_BUG: "不具合の疑いとして扱います",
  WORK_REQUEST: "作業依頼として扱います",
  INCIDENT: "障害として扱います",
};

/** 緊急度の日本語名。 */
export const URGENCY_LABELS: Record<Urgency, string> = {
  LOW: "低（急ぎではない）",
  NORMAL: "通常",
  HIGH: "高（急ぎ）",
};

/** 出典の種類の日本語名。 */
export const SOURCE_TYPE_LABELS: Record<SourceType, string> = {
  CODE: "コード",
  FAQ: "FAQ",
  DOCUMENT: "ドキュメント",
  GLOSSARY: "用語対応表",
};

/** 結果ごとの完了画面の見出しと本文。 */
export const OUTCOME_TEXT: Record<
  InquiryOutcome,
  { title: string; body: string }
> = {
  IN_PROGRESS: { title: "対応中です", body: "" },
  SELF_RESOLVED: {
    title: "解決として記録しました",
    body: "お役に立てて何よりです。担当者への連絡は行っていません。",
  },
  FILED: {
    title: "担当者に送りました",
    body: "担当者が内容を確認します。回答までしばらくお待ちください。",
  },
  INCIDENT_REDIRECTED: {
    title: "障害として案内します",
    body: "次の案内に従ってください。",
  },
  ABANDONED: { title: "この問い合わせは終了しました", body: "" },
};

/** 「今すぐ担当者に送る」で送ったときの完了文言。 */
export const SENT_IMMEDIATELY_TEXT = {
  title: "担当者に送りました",
  body: "いまの内容のまま担当者に送りました。担当者が内容を確認します。",
};

/** AI 起因のエラー（AI_REFUSED / AI_UNAVAILABLE）の平易な説明。 */
export const AI_ERROR_TEXT: Record<string, string> = {
  AI_REFUSED:
    "AIがこの内容には回答できませんでした。担当者に直接送ると、人が確認します。",
  AI_UNAVAILABLE:
    "いまAIを利用できません。時間をおいて試すか、担当者に直接送ってください。",
};

/** 上記以外のエラーの汎用文言。 */
export const GENERIC_ERROR_TEXT =
  "うまく処理できませんでした。時間をおいてもう一度お試しください。";

/** 問い合わせの作成前に AI エラーが起きたときの案内（まだ送る先の問い合わせが無い）。 */
export const AI_ERROR_BEFORE_CREATE_HINT =
  "内容を少し変えるか、時間をおいてもう一度送信してください。";

/** 進行表示の段階名。 */
export const PROGRESS_STEPS = [
  "入力",
  "追加質問",
  "内容の確認",
  "0次回答",
  "完了",
] as const;
