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

/** 対象システムの選択肢。 */
export interface SystemOption {
  id: string;
  label: string;
}

/** 追加質問。`options` が空なら自由記述。 */
export interface Question {
  id: string;
  text: string;
  options: string[];
}

/** 整形後の問い。 */
export interface StructuredInquiry {
  /** 本人に見せる言い直しの文章。画面はこの文章だけを見せる。 */
  summary: string;
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
  /** 調査が挙げたが、根拠の実在を確かめられなかった事実。古い応答では無い */
  unconfirmed?: string[];
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
  /** 言い直しを作り直せる残り回数 */
  /** 対象システム（選択または推測。決まっていなければ null） */
  system: SystemOption | null;
  /** system がAIの推測なら true */
  systemGuessed: boolean;
  revisionsLeft: number;
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
  /** 担当者に送った後の案内文 */
  filedGuidance?: string;
  /** 対象システムの選択肢（空または省略なら選択を出さない） */
  systems?: SystemOption[];
}

/** エラー本文。通信失敗など HTTP 外の失敗は code が `NETWORK`。 */
export interface ErrorBody {
  code: string;
  message: string;
  /** 種別の判定でAIが失敗したとき、保存された問い合わせの id */
  inquiryId?: string;
}

/** オペレータ向けの一覧の1件。`filed` イベントの data も同じ形。 */
export interface OperatorItem {
  id: string;
  filedAt: string;
  category: InquiryCategory | null;
  system: SystemOption | null;
  summary: string;
  sentImmediately: boolean;
  read: boolean;
}

/** `GET /api/operator/inquiries` の応答。 */
export interface OperatorList {
  unreadCount: number;
  items: OperatorItem[];
}

/** 追加質問への本人の対応。 */
export type FollowUpStatus = "ANSWERED" | "UNKNOWN" | "NOT_ANSWERED";

/** 追加質問と回答（オペレータ向け）。`status` が無い古い応答では従来の表示にする。 */
export interface FollowUp {
  text: string;
  answer: string | null;
  status?: FollowUpStatus;
}

/** 照合の結果つきの出典。 */
export interface OperatorCitation extends Citation {
  verified: boolean;
}

/** オペレータ向けの0次回答。出典は照合に落ちたものも含む。 */
export interface OperatorAnswer {
  grounded: boolean;
  askerExplanation: string | null;
  memo: AnswerMemo;
  citations: OperatorCitation[];
}

/** Slack への投稿の状態。 */
export type SlackStatus = "POSTED" | "SKIPPED" | "PENDING" | "FAILED";

/** 問い合わせの全内容（オペレータ向け）。 */
export interface OperatorDetail {
  id: string;
  filedAt: string;
  askerId: string;
  utterance: string;
  categoryByAi: InquiryCategory | null;
  category: InquiryCategory | null;
  system: SystemOption | null;
  systemGuessed: boolean;
  followUps: FollowUp[];
  structured: StructuredInquiry | null;
  revisionNotes: string[];
  answer: OperatorAnswer | null;
  sentImmediately: boolean;
  read: boolean;
  /** Slack への投稿の状態 */
  slackStatus: SlackStatus;
}
