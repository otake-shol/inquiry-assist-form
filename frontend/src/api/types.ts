/**
 * フォームAPIの契約（docs/api-contract.md）に対応する型。
 * 契約が変わったらここと backend を同時に直す。
 */

/** 問い合わせの種類。 */
export type InquiryTypeId =
  "HOW_TO" | "HOW_IT_WORKS" | "LOOKS_WRONG" | "DO_FOR_ME" | "CHANGE_REQUEST";

/**
 * 区間。いま埋めようとしている不足の種類（受付、分かる、決まる、行われる、終わり）。
 * 担当者の画面では「分かる」「決まる」「行われる」と呼ぶ。
 */
export type Phase = "INTAKE" | "KNOW" | "DECIDE" | "ACT" | "CLOSED";

/** 誰の番か。次に動く人（`OWNER` は仕様の持ち主）。終わった件は null。 */
export type Turn = "ASKER" | "AI" | "OPERATOR" | "OWNER";

/**
 * 待っていること。画面は、これを見て描くものを決める。終わった件は null。
 * 入口の質問に答えている、問いを確かめている、AI が調べている、結果を確かめている、
 * 担当者が受けている、聞き返しへの返事を待っている、仕様の持ち主の決定を待っている。
 */
export type Awaiting =
  | "INTAKE_ANSWER"
  | "QUESTION_CONFIRMATION"
  | "INVESTIGATION"
  | "RESULT_CONFIRMATION"
  | "HANDLING"
  | "ASKER_REPLY"
  | "CONSULTATION";

/** 終わり方。解決済み、回答済み、見送り、取り下げ。終わっていない件は null。 */
export type Ending = "RESOLVED" | "ANSWERED" | "DECLINED" | "WITHDRAWN";

/** 見送りの理由の種類。 */
export type DeclineReasonKind =
  | "NOT_REPRODUCIBLE"
  | "KNOWN_LIMITATION"
  | "OUT_OF_SCOPE"
  | "DUPLICATE"
  | "OTHER";

/** 見送りで終わった件が持つ、見送りの理由。 */
export interface Declined {
  reasonKind: DeclineReasonKind;
  /** 担当者が書いた理由 */
  reason: string;
}

/**
 * 担当者に渡した理由（直近に渡したときの理由）。本人が答えを待たずに送った、解決していないと答えた、
 * 業務が止まっている、種類の経路、AI が答えられなかった、調査の時間切れ、終わった件への追記、
 * 仕様の持ち主が決めた（相談に答えて、担当者の番に戻った）。
 */
export type HandoverReason =
  | "SENT_NOW"
  | "UNRESOLVED"
  | "BLOCKED"
  | "ROUTE"
  | "NO_ANSWER"
  | "TIMEOUT"
  | "REOPENED"
  | "DECIDED";

/** AI の調査の状況。 */
export type InvestigationStatus = "NOT_RUN" | "RUNNING" | "DONE" | "FAILED";

/** AI の案の状況。 */
export type AdviceStatus = "NOT_RUN" | "RUNNING" | "DONE" | "FAILED";

/** 判断の案。`basedOn` は、案が基づく事実の文（`answer.memo.findings` と同じもの）。 */
export interface AdviceJudgment {
  text: string;
  /** 仕様の持ち主への相談が要りそうか */
  needsConsultation: boolean;
  consultationReason: string | null;
  basedOn: string[];
}

/** 作業の手順の案の、1つの手順。 */
export interface AdviceStep {
  /** 対象 */
  target: string;
  /** 操作やコマンド */
  action: string;
  /** 結果の確かめ方 */
  check: string;
}

/** 作業の手順の案。実行するのは担当者。 */
export interface AdviceProcedure {
  /** 行う順 */
  steps: AdviceStep[];
  /** 元に戻す方法 */
  rollback: string | null;
  basedOn: string[];
}

/** 担当者向けの、AI の案（`GET /api/operator/inquiries/{id}` の `advice`）。 */
export interface Advice {
  status: AdviceStatus;
  judgment: AdviceJudgment | null;
  procedure: AdviceProcedure | null;
}

/** 訳の1件。`key` は、画面のどの文章の訳かを示す（`utterance`、`structured.summary` など）。 */
export interface TranslationItem {
  key: string;
  /** 原文（本人の言語で書かれたもの） */
  original: string;
  /** 訳（訳す先の言語） */
  translated: string;
}

/**
 * 担当者に添える訳（`POST /api/operator/inquiries/{id}/translation` の応答）。
 * `key` は `utterance`、`structured.summary`、`answer.askerExplanation`、`message:{id}`（本人の発言）。
 */
export interface Translation {
  from: string;
  to: string;
  items: TranslationItem[];
}

/** 返信の下書き（`POST /api/operator/inquiries/{id}/draft` の応答）。 */
export interface DraftResult {
  /** 下書きの文章 */
  body: string;
  /** 下書きを書いた言語（本人の言語） */
  language: string;
}

/** 担当者向けの、調査の状況（`GET /api/operator/inquiries/{id}` の `investigation`）。 */
export interface Investigation {
  status: InvestigationStatus;
  /** 結果を誰に見せたか。本人が待っている間に出た答えは `ASKER`、裏での調査の結果は `OPERATOR`。`DONE` のときだけ非 null */
  audience: "ASKER" | "OPERATOR" | null;
  /** 直近の調査を始めた時刻 */
  startedAt: string | null;
  /** 直近の調査が終わった（または失敗した）時刻 */
  finishedAt: string | null;
  /** 調査のもとにした言い直しを、本人が確かめていないか（業務が止まっている件） */
  unconfirmedQuestion: boolean;
}

/** 問い合わせを終わらせた人。時間は、返事がないまま期間が過ぎたこと。 */
export type ClosedBy = "ASKER" | "OPERATOR" | "TIME";

/** 状態の4項目。本人向けの表現にも、担当者向けの一覧と詳細にも入る。 */
export interface StateFields {
  phase: Phase;
  turn: Turn | null;
  awaiting: Awaiting | null;
  ending: Ending | null;
}

/** 緊急度。通常、急ぎ、業務が止まっている。種類とは別に持つ。 */
export type Urgency = "NORMAL" | "HURRY" | "BLOCKED";

/** 置き場所（種類、システム、機能）を決めた人。本人が選んだか、AI が文章から置いたか、担当者が置き直したか。 */
export type PlacedBy = "ASKER" | "AI" | "OPERATOR";

/** 決まった置き場所の1つ。`label` は API が返す表示名。 */
export interface Placement<Id extends string = string> {
  id: Id;
  label: string;
  by: PlacedBy;
}

/** 出典の種類。 */
export type SourceType = "CODE" | "FAQ" | "DOCUMENT" | "GLOSSARY";

/** 名前だけを持つ選択肢（一覧の1件の種類、システム、機能など）。 */
export interface SystemOption {
  id: string;
  label: string;
}

/** 設定のシステム。機能の設定がなければ `functions` は空。 */
export interface ConfigSystem extends SystemOption {
  functions: SystemOption[];
}

/** 設定の種類。`hint` は選択肢に添える短い説明。 */
export interface TypeOption extends SystemOption {
  hint: string;
}

/** 入口の質問の種類。 */
export type IntakeQuestionKind =
  "TYPE" | "SYSTEM" | "FUNCTION" | "DETAIL" | "NOTE";

/** 入口の質問の選択肢。`hint` は種類の選択肢に添える短い説明（あれば）。 */
export interface IntakeOption {
  id: string;
  label: string;
  hint?: string;
}

/**
 * 次に答える入口の質問。`options` が空なら文章で答える。
 * `allowsText` が true なら選択肢のほかに文章でも答えられ、`canSkip` は補足だけが true になりうる。
 */
export interface IntakeQuestion {
  questionId: string;
  kind: IntakeQuestionKind;
  text: string;
  options: IntakeOption[];
  allowsText: boolean;
  canSkip: boolean;
}

/**
 * 入口の質問への答え。選んだ（`OPTION`）、文章で答えた（`TEXT`）、わからない（`UNKNOWN`）、
 * 補足を書かずに進んだ（`SKIPPED`）、文章から AI が置いた（`INFERRED`）のいずれか。
 */
export type IntakeAnswer =
  | { kind: "OPTION"; optionId: string; label: string }
  | { kind: "TEXT"; text: string }
  | { kind: "UNKNOWN" }
  | { kind: "SKIPPED" }
  | { kind: "INFERRED"; optionId: string; label: string };

/** 答え終えた入口の質問1件。 */
export interface IntakeEntry {
  questionId: string;
  kind: IntakeQuestionKind;
  text: string;
  answer: IntakeAnswer;
}

/** 入口の質問の進み具合。入口が終わっていれば `next` は null で `complete` が true。 */
export interface Intake {
  /** 答え終えた質問（聞いた順） */
  entries: IntakeEntry[];
  /** 次に答える質問 */
  next: IntakeQuestion | null;
  complete: boolean;
}

/** 整形後の問い。 */
export interface StructuredInquiry {
  /** 本人に見せる言い直しの文章。画面はこの文章だけを見せる。 */
  summary: string;
  targetSystem: string | null;
  goal: string;
  whatHappened: string | null;
  expectedResult: string | null;
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
  /**
   * 調査が挙げたが、根拠の実在を確かめられなかった事実。担当者にだけ見せる
   * （本人向けの応答では常に空。本人向けの画面では中身があっても表示しない）。
   */
  unconfirmed?: string[];
  notFound: string[];
  nextChecks: string[];
}

/** 本人が自分で行える手順の1つ。 */
export interface AnswerStep {
  /** 何をするか */
  action: string;
  /** 結果の確かめ方（なければ null） */
  check: string | null;
}

/** 0次回答。`grounded` が false のとき `askerExplanation` は null。 */
export interface ZeroAnswer {
  grounded: boolean;
  /** 確かめられた事実が本人の問いに答えているか。無い古い応答では `grounded` と同じ扱い */
  answered?: boolean;
  askerExplanation: string | null;
  /** 本人が自分で行える手順（行う順。なければ空配列）。古い API では欠ける */
  steps: AnswerStep[];
  memo: AnswerMemo;
  citations: Citation[];
}

/** やり取りの書き手。担当者の発言か、本人の追記。 */
export type MessageAuthor = "OPERATOR" | "ASKER";

/**
 * やり取りの種類。担当者の発言は、聞き返し（`ASK`）、結果（`RESULT`）、見送りの連絡（`DECLINE`）。
 * 本人の追記は `NOTE`。
 */
export type MessageKind = "ASK" | "RESULT" | "DECLINE" | "NOTE";

/** やり取りの1通。 */
export interface InquiryMessage {
  id: string;
  from: MessageAuthor;
  kind: MessageKind;
  /** 表示用の名前 */
  authorName: string;
  body: string;
  createdAt: string;
}

/** スキルの実行記録の1件（呼び出し1回分）。開発者向け。 */
export interface TraceEntry {
  /** スキルの識別子（`reception` など） */
  skill: string;
  /** 実装の名前（`mock` / `ollama` など） */
  provider: string;
  model?: string | null;
  promptVersion?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  /** 呼び出しにかかった時間。記録を加える前の問い合わせでは null */
  durationMillis?: number | null;
  failed: boolean;
}

/**
 * 返事がないときの予定。本人の返事を待っている間（結果の確かめ、聞き返しへの返事）だけ非 null（それ以外は null）。
 * `remindedAt` は実際に知らせた時刻（まだなら null）、`closesAt` は終わる予定の時刻（知らせた後だけ非 null）。
 */
export interface NoReply {
  /** 期間が過ぎたときの終わり方（結果の確かめは回答済み、聞き返しへの返事は取り下げ） */
  endsAs: "ANSWERED" | "WITHDRAWN";
  /** 知らせる予定の時刻 */
  remindAt: string;
  remindedAt: string | null;
  closesAt: string | null;
}

/** 問い合わせ1件の表現。 */
export interface InquiryView extends StateFields {
  id: string;
  /** 本人の言語（`ja`、`en`）。本人の文章と AI が書いたものの言語 */
  language: string;
  /** 見送りで終わった件だけが持つ */
  declined: Declined | null;
  /** 終わらせた人（終わっていない件は null） */
  closedBy: ClosedBy | null;
  /** 直近に担当者へ渡した理由（渡したことがなければ null） */
  handoverReason: HandoverReason | null;
  urgency: Urgency;
  /** 種類。決まっていなければ null */
  type: Placement<InquiryTypeId> | null;
  /** 対象システム。決まっていなければ null */
  system: Placement | null;
  /** 機能。決まっていなければ null */
  function: Placement | null;
  /** 本人が書いた文章（複数ある場合は書いた順に空行でつないだもの）。書いていなければ null */
  utterance: string | null;
  intake: Intake;
  /** 言い直しを作り直せる残り回数 */
  revisionsLeft: number;
  structured: StructuredInquiry | null;
  answer: ZeroAnswer | null;
  /** いま「答えを待たずに担当者へ送る」を実行できるか */
  canSendNow: boolean;
  sentImmediately: boolean;
  mock: boolean;
  /** 担当者に初めて渡した時刻。渡していなければ null */
  handedOverAt: string | null;
  /** 担当者の新しい発言（返事がないことの知らせを含む）が、まだ読まれていないか */
  unreadReply: boolean;
  /** 返事がないときの予定（本人の返事を待っている間だけ非 null） */
  noReply: NoReply | null;
  /** 作成日時 */
  createdAt?: string;
  /** やり取り（古い順） */
  messages?: InquiryMessage[];
  /** スキルの実行記録。無効なら null */
  trace?: TraceEntry[] | null;
}

/** 入口の質問への答えのリクエスト（`questionId` は別に渡す）。 */
export type IntakeAnswerRequest =
  | { optionId: string }
  | { text: string }
  | { unknown: true }
  | { skipped: true };

/** `GET /api/config` の応答。 */
export interface AppConfig {
  mock: boolean;
  /** 担当者に送った後の案内文 */
  filedGuidance?: string;
  /** 種類の選択肢（聞く順。1問目の質問を作る前に画面が描く） */
  types?: TypeOption[];
  /** 緊急度の選択肢（通常、急ぎ、業務が止まっている） */
  urgencies?: SystemOption[];
  /** システムの一覧 */
  systems?: ConfigSystem[];
  /** 画面に出せる言語 */
  languages?: string[];
  /** スキルの識別子から、動いている実装（`mock` / `ollama`）への対応。古い API では無い */
  skills?: Record<string, string>;
  /** 利用者。認証を加えるまでは設定の固定値。古い API では無い */
  user?: { id: string; name: string };
  /** 担当者の表示名。古い API では無い */
  operatorName?: string;
  /** スキルの実行記録を見せるか。古い API では無く、false と同じ扱い */
  trace?: boolean;
}

/** エラー本文。通信失敗など HTTP 外の失敗は code が `NETWORK`。 */
export interface ErrorBody {
  code: string;
  message: string;
  /** 問いの組み立てに失敗したとき、答えを保存した問い合わせの id */
  inquiryId?: string;
}

/** オペレータ向けの一覧の1件。`filed` イベントの data も同じ形。 */
export interface OperatorItem extends StateFields {
  id: string;
  /** 本人の言語 */
  language: string;
  filedAt: string;
  type: SystemOption | null;
  system: SystemOption | null;
  function: SystemOption | null;
  urgency: Urgency;
  summary: string;
  sentImmediately: boolean;
  read: boolean;
  /** やり取りの数 */
  messageCount: number;
  /** 相談先の名前（相談中の件だけ。古い API では欠ける） */
  consultants?: string[];
}

/** `GET /api/operator/inquiries` の応答。 */
export interface OperatorList {
  unreadCount: number;
  items: OperatorItem[];
}

/** 本人向けの一覧の1件。 */
export interface InquiryListItem extends StateFields {
  id: string;
  /** 本人の言語 */
  language: string;
  urgency: Urgency;
  /** 担当者に初めて渡した時刻。渡していなければ null。結果の出どころ（AI か担当者）を見分けるのに使う */
  handedOverAt: string | null;
  createdAt: string;
  summary: string;
  unreadReply: boolean;
  messageCount: number;
}

/** `GET /api/inquiries` の応答。 */
export interface InquiryList {
  unreadReplyCount: number;
  items: InquiryListItem[];
}

/** 照合の結果つきの出典。 */
export interface OperatorCitation extends Citation {
  verified: boolean;
}

/** オペレータ向けの0次回答。出典は照合に落ちたものも含む。 */
export interface OperatorAnswer {
  grounded: boolean;
  /** 問いに答えているか。無い古い応答では `grounded` と同じ扱い */
  answered?: boolean;
  askerExplanation: string | null;
  /** 本人が自分で行える手順（本人に見せるもの。なければ空配列）。古い API では欠ける */
  steps: AnswerStep[];
  memo: AnswerMemo;
  citations: OperatorCitation[];
}

/** Slack への投稿の状態。 */
export type SlackStatus = "POSTED" | "SKIPPED" | "PENDING" | "FAILED";

/** 問い合わせの全内容（オペレータ向け）。 */
export interface OperatorDetail extends StateFields {
  id: string;
  /** 本人の言語 */
  language: string;
  /** 見送りで終わった件だけが持つ */
  declined: Declined | null;
  /** 終わらせた人（終わっていない件は null） */
  closedBy: ClosedBy | null;
  /** 直近に担当者へ渡した理由 */
  handoverReason: HandoverReason | null;
  /** AI の調査の状況 */
  investigation: Investigation;
  /** AI の案（判断の案、手順の案） */
  advice: Advice;
  /** 既定の相談先の名前（なければ空。古い API では欠ける） */
  defaultConsultants?: string[];
  /** 仕様の持ち主への相談（古い順。古い API では欠ける） */
  consultations?: Consultation[];
  /** 仕様の持ち主の決定（先例。古い順。古い API では欠ける） */
  decisions?: Precedent[];
  /** 返事がないときの予定（本人の返事を待っている間だけ非 null） */
  noReply: NoReply | null;
  filedAt: string;
  askerId: string;
  /** 投稿者の表示用の名前（古い応答では欠ける） */
  askerName?: string;
  /** 本人が書いた文章。書いていなければ null */
  utterance: string | null;
  urgency: Urgency;
  type: Placement<InquiryTypeId> | null;
  system: Placement | null;
  function: Placement | null;
  /** 本人が何を選び、何に「わからない」と答えたか（聞いた順） */
  intake: IntakeEntry[];
  structured: StructuredInquiry | null;
  revisionNotes: string[];
  answer: OperatorAnswer | null;
  sentImmediately: boolean;
  read: boolean;
  /** Slack への投稿の状態 */
  slackStatus: SlackStatus;
  /** やり取り（古い順） */
  messages?: InquiryMessage[];
  /** 状態の履歴（古い順） */
  history: HistoryEntry[];
  /** スキルの実行記録。無効なら null。無い応答では欠ける */
  trace?: TraceEntry[] | null;
}

/** 状態の履歴で、変化を起こした人。 */
export type HistoryActor = "ASKER" | "AI" | "OPERATOR" | "OWNER" | "TIME";

/** 置き場所の名前（既定の言語）。決まっていなければ null。 */
export interface PlacementNames {
  type: string | null;
  system: string | null;
  function: string | null;
}

/** 置き直しの、変える前と後。 */
export interface PlacementChange {
  before: PlacementNames;
  after: PlacementNames;
}

/** 状態の履歴の1件。状態は、変化の後のもの。 */
export interface HistoryEntry extends StateFields {
  at: string;
  actor: HistoryActor;
  /** 何が起きたか（`CREATED`、`ASKED_BACK` など。画面での呼び名は operator/labels.ts） */
  event: string;
  /** 置き直し（`PLACEMENT_CHANGED`）だけが持つ、変える前と後の置き場所。それ以外は null */
  detail?: PlacementChange | null;
}

/** 仕様の持ち主の決定の種類。 */
export type DecisionKind =
  "AS_INTENDED" | "DEFECT" | "ACCEPT" | "REJECT" | "ADOPT" | "DEFER" | "OTHER";

/** 決定の種類（選択肢に並べる順）。 */
export const DECISION_KINDS: DecisionKind[] = [
  "AS_INTENDED",
  "DEFECT",
  "ACCEPT",
  "REJECT",
  "ADOPT",
  "DEFER",
  "OTHER",
];

/** 相談の状況。決定を待っている、決まった、取り下げた。 */
export type ConsultationStatus = "OPEN" | "DECIDED" | "CANCELED";

/** 仕様の持ち主への相談の1件。 */
export interface Consultation {
  id: string;
  askedAt: string;
  /** 相談した担当者の名前 */
  askedBy: string;
  /** 相談先の名前 */
  to: string[];
  /** 相談した文章 */
  question: string;
  status: ConsultationStatus;
  closedAt: string | null;
  /** 決まったときの先例の id */
  precedentId: string | null;
}

/** 先例。仕様の持ち主が下した決定の記録。文章は書かれたときのままで、訳さない。 */
export interface Precedent {
  id: string;
  inquiryId: string;
  /** 決めた時点の置き場所（名前は見る人の言語） */
  type: SystemOption | null;
  system: SystemOption | null;
  function: SystemOption | null;
  /** どんな状況についての決定か */
  situation: string;
  decisionKind: DecisionKind;
  decision: string;
  reason: string;
  decidedBy: string;
  recordedBy: string;
  /** 決めた人が内容を確かめたか */
  confirmed: boolean;
  /** 文章が書かれた言語 */
  language: string;
  decidedAt: string;
}

/** 仕様の持ち主の画面の、決定を待っている相談の1件。 */
export interface OwnerConsultationItem {
  inquiryId: string;
  consultationId: string;
  askedAt: string;
  askedBy: string;
  to: string[];
  question: string;
  summary: string;
  type: SystemOption | null;
  system: SystemOption | null;
  function: SystemOption | null;
  urgency: Urgency;
  /** 本人の言語 */
  language: string;
}

/** `GET /api/owner/consultations` の応答。 */
export interface OwnerList {
  items: OwnerConsultationItem[];
}

/** `POST /api/owner/inquiries/{id}/decision` のリクエスト。 */
export interface DecisionRequest {
  decisionKind: DecisionKind;
  decision: string;
  reason: string;
  situation: string;
  decidedBy: string;
}
