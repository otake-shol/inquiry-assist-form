/**
 * フォームAPIの契約（docs/api-contract.md）に対応する型。
 * 契約が変わったらここと backend を同時に直す。
 */

/** 問い合わせの種別。 */
export type InquiryCategory =
  "SPEC_QUESTION" | "SUSPECTED_BUG" | "WORK_REQUEST" | "INCIDENT";

/** 問い合わせ1件の進行位置。 */
export type InquiryStep =
  "TRIAGED" | "STRUCTURED" | "ANSWERING" | "ANSWERED" | "CLOSED";

/** 問い合わせの結果。 */
export type InquiryOutcome =
  | "IN_PROGRESS"
  | "SELF_RESOLVED"
  | "FILED"
  | "INCIDENT_REDIRECTED"
  | "ABANDONED";

/** 緊急度。 */
export type Urgency = "LOW" | "NORMAL" | "HIGH";

/** 出典の種類。 */
export type SourceType = "CODE" | "FAQ" | "DOCUMENT" | "GLOSSARY";

/** 追加質問。`options` が空なら自由記述。 */
export interface Question {
  id: string;
  text: string;
  options: string[];
}

/** 整形後の問い。 */
export interface StructuredInquiry {
  targetSystem: string | null;
  goal: string;
  whatHappened: string | null;
  expectedResult: string | null;
  urgency: Urgency;
}

/** 0次回答の出典。 */
export interface Citation {
  sourceType: SourceType;
  repository: string;
  path: string;
  startLine: number;
  endLine: number;
  quote: string;
  revision: string;
}

/** 担当者向けメモ。 */
export interface AnswerMemo {
  findings: string[];
  notFound: string[];
  nextChecks: string[];
}

/** 0次回答。`grounded` が false のとき `askerExplanation` は null。 */
export interface ZeroAnswer {
  grounded: boolean;
  askerExplanation: string | null;
  memo: AnswerMemo;
  citations: Citation[];
}

/** 問い合わせ1件の表現。 */
export interface InquiryView {
  id: string;
  step: InquiryStep;
  outcome: InquiryOutcome;
  utterance: string;
  /** 種別の判定でAIが失敗して保存された問い合わせでは null */
  categoryByAi: InquiryCategory | null;
  category: InquiryCategory | null;
  questions: Question[];
  structured: StructuredInquiry | null;
  answer: ZeroAnswer | null;
  incidentGuidance: string | null;
  sentImmediately: boolean;
  mock: boolean;
}

/** 追加質問への回答。「わからない」は null。 */
export interface AnswerInput {
  questionId: string;
  answer: string | null;
}

/** `GET /api/config` の応答。 */
export interface AppConfig {
  mock: boolean;
}

/** エラー本文。通信失敗など HTTP 外の失敗は code が `NETWORK`。 */
export interface ErrorBody {
  code: string;
  message: string;
  /** 種別の判定でAIが失敗したとき、保存された問い合わせの id */
  inquiryId?: string;
}
