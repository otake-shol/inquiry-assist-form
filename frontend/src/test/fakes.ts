import { vi } from "vitest";
import type {
  AnswerStreamHandlers,
  OperatorStreamHandlers,
} from "../api/stream.ts";
import type {
  AppConfig,
  InquiryListItem,
  InquiryMessage,
  InquiryView,
  IntakeEntry,
  IntakeQuestion,
  OperatorDetail,
  OperatorItem,
  TraceEntry,
  TypeOption,
  ZeroAnswer,
} from "../api/types.ts";

/** 設定の種類（聞く順）。 */
export const TYPE_OPTIONS: TypeOption[] = [
  { id: "HOW_TO", label: "使い方を知りたい", hint: "操作の手順" },
  { id: "HOW_IT_WORKS", label: "仕組みを知りたい", hint: "いまの動きの理由" },
  { id: "LOOKS_WRONG", label: "おかしいかもしれない", hint: "想定と違う動き" },
  { id: "DO_FOR_ME", label: "やってほしい", hint: "作業の依頼" },
  { id: "CHANGE_REQUEST", label: "変えてほしい", hint: "機能や画面の要望" },
];

/** 設定の緊急度の選択肢。 */
export const URGENCY_OPTIONS = [
  { id: "NORMAL", label: "通常" },
  { id: "HURRY", label: "急ぎ" },
  { id: "BLOCKED", label: "業務が止まっている" },
];

/** `GET /api/config` の応答を作る。省いた項目は、種類と緊急度つきの既定。 */
export function makeConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  return {
    mock: false,
    types: TYPE_OPTIONS,
    urgencies: URGENCY_OPTIONS,
    ...overrides,
  };
}

/** テスト用に入口の質問を作る。省いた項目は、選択肢つきの「どのシステムですか」。 */
export function makeQuestion(
  overrides: Partial<IntakeQuestion> = {},
): IntakeQuestion {
  return {
    questionId: "system",
    kind: "SYSTEM",
    text: "どのシステムですか",
    options: [
      { id: "system-a", label: "システムA" },
      { id: "system-b", label: "システムB" },
    ],
    allowsText: false,
    canSkip: false,
    ...overrides,
  };
}

/** 入口の1問目（種類）。選択肢に説明（hint）は付かない。 */
export const TYPE_QUESTION: IntakeQuestion = makeQuestion({
  questionId: "type",
  kind: "TYPE",
  text: "何をしたいですか",
  options: TYPE_OPTIONS.map(({ id, label }) => ({ id, label })),
});

/** 答え終えた種類の質問（本人が選んだ）。 */
export function makeTypeEntry(
  optionId = "HOW_IT_WORKS",
  label = "仕組みを知りたい",
): IntakeEntry {
  return {
    questionId: "type",
    kind: "TYPE",
    text: "何をしたいですか",
    answer: { kind: "OPTION", optionId, label },
  };
}

/** テスト用に問い合わせの表現を作る。省いた項目は、入口の1問目を聞いている初期状態。 */
export function makeView(overrides: Partial<InquiryView> = {}): InquiryView {
  return {
    id: "inq-1",
    step: "INTAKE",
    outcome: "IN_PROGRESS",
    urgency: "NORMAL",
    type: null,
    system: null,
    function: null,
    utterance: null,
    intake: { entries: [], next: TYPE_QUESTION, complete: false },
    revisionsLeft: 2,
    structured: null,
    answer: null,
    canSendNow: false,
    sentImmediately: false,
    mock: true,
    ...overrides,
  };
}

/** テスト用にやり取りの1通を作る。省略した項目は担当者の返信。 */
export function makeMessage(
  overrides: Partial<InquiryMessage> = {},
): InquiryMessage {
  return {
    id: "msg-1",
    from: "OPERATOR",
    authorName: "担当者（開発用）",
    body: "確認します。少しお待ちください。",
    createdAt: "2026-11-21T06:00:00Z",
    ...overrides,
  };
}

/** テスト用に、送った問い合わせの一覧の1件を作る。省略した項目は担当者の確認待ち。 */
export function makeListItem(
  overrides: Partial<InquiryListItem> = {},
): InquiryListItem {
  return {
    id: "inq-1",
    createdAt: "2026-11-21T05:00:00Z",
    summary: "求人の掲載期限を、何日まで延ばせるかを知りたい。",
    step: "CLOSED",
    outcome: "FILED",
    handlingStatus: "OPEN",
    unreadReply: false,
    messageCount: 0,
    ...overrides,
  };
}

/** テスト用にスキルの実行記録の1件を作る。省略した項目は手元のモデルの調査。 */
export function makeTraceEntry(
  overrides: Partial<TraceEntry> = {},
): TraceEntry {
  return {
    skill: "investigator",
    provider: "ollama",
    model: "qwen3.6:35b",
    promptVersion: "v1",
    inputTokens: 2663,
    outputTokens: 197,
    durationMillis: 2921,
    failed: false,
    ...overrides,
  };
}

/** 担当者に送られて終わった問い合わせを作る。 */
export function makeFiledView(
  overrides: Partial<InquiryView> = {},
): InquiryView {
  return makeView({
    step: "CLOSED",
    outcome: "FILED",
    sentImmediately: true,
    handlingStatus: "OPEN",
    messages: [],
    ...overrides,
  });
}

/** 入口を終えて言い直しが出ている STRUCTURED 状態を作る。種類は本人が選んだ「仕組みを知りたい」。 */
export function makeStructuredView(
  overrides: Partial<InquiryView> = {},
): InquiryView {
  return makeView({
    step: "STRUCTURED",
    type: { id: "HOW_IT_WORKS", label: "仕組みを知りたい", by: "ASKER" },
    intake: { entries: [makeTypeEntry()], next: null, complete: true },
    structured: {
      summary: "求人の掲載期限を、何日まで延ばせるかを知りたい。",
      targetSystem: "求人管理画面",
      goal: "掲載期限を延ばせる日数を知りたい",
      whatHappened: null,
      expectedResult: null,
    },
    ...overrides,
  });
}

/** 根拠ありの0次回答。 */
export const groundedAnswer: ZeroAnswer = {
  grounded: true,
  askerExplanation: "掲載期限は最大90日まで延長できます。",
  memo: {
    findings: ["延長上限は90日と定義されている"],
    notFound: ["延長回数の制限"],
    nextChecks: ["契約プランごとの上限を確認する"],
  },
  citations: [
    {
      sourceType: "CODE",
      repository: "job-board",
      path: "src/main/java/JobPostingService.java",
      startLine: 10,
      endLine: 14,
      quote: "MAX_EXTENSION_DAYS = 90",
      revision: "mock",
    },
  ],
};

/** 根拠なしの0次回答（照合できた出典が0件）。 */
export const ungroundedAnswer: ZeroAnswer = {
  grounded: false,
  askerExplanation: null,
  memo: {
    findings: [],
    notFound: ["該当する仕様の記述"],
    nextChecks: ["担当チームに確認する"],
  },
  citations: [],
};

/** 記録される API 呼び出し。 */
export interface RecordedCall {
  method: string;
  path: string;
  body: unknown;
}

/** ルートの応答。HTTP ステータスを指定しなければ 200。 */
export type RouteResult = { status?: number; body: unknown };

/**
 * fetch を偽物に差し替える。`"METHOD /path"` ごとに応答を登録する。
 * 未登録の呼び出しは 500 を返し、calls に残るのでテストで気づける。
 * @param routes 応答の登録表（値は固定の応答か、リクエスト本文から作る関数）
 * @returns 呼び出し履歴
 */
export function installFakeFetch(
  routes: Record<string, RouteResult | ((body: unknown) => RouteResult)>,
): RecordedCall[] {
  const calls: RecordedCall[] = [];
  const allRoutes = {
    "GET /api/config": { body: makeConfig() },
    ...routes,
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      const path = String(input);
      const body =
        typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
      calls.push({ method, path, body });
      const route = (allRoutes as typeof routes)[`${method} ${path}`];
      if (route === undefined) {
        return new Response(
          JSON.stringify({ code: "UNEXPECTED", message: "未登録" }),
          { status: 500 },
        );
      }
      const result = typeof route === "function" ? route(body) : route;
      return new Response(JSON.stringify(result.body), {
        status: result.status ?? 200,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  return calls;
}

/** 偽物の進捗ストリーム。購読の履歴を持ち、テストからイベントを流せる。 */
export class FakeStream {
  readonly subscriptions: {
    id: string;
    handlers: AnswerStreamHandlers;
    closed: boolean;
  }[] = [];

  /** App に渡す購読関数。 */
  readonly subscribe = (id: string, handlers: AnswerStreamHandlers) => {
    const subscription = { id, handlers, closed: false };
    this.subscriptions.push(subscription);
    return () => {
      subscription.closed = true;
    };
  };

  /** 最後の購読。 */
  get latest() {
    return this.subscriptions[this.subscriptions.length - 1];
  }
}

/** テスト用にオペレータ一覧の1件を作る。 */
export function makeItem(overrides: Partial<OperatorItem> = {}): OperatorItem {
  return {
    id: "op-1",
    filedAt: "2026-11-21T05:00:00Z",
    type: { id: "LOOKS_WRONG", label: "おかしいかもしれない" },
    system: { id: "job-admin", label: "求人管理画面" },
    function: null,
    urgency: "NORMAL",
    summary: "求人が一覧に出なくなった理由を知りたい。",
    sentImmediately: false,
    read: false,
    ...overrides,
  };
}

/** テスト用にオペレータ向けの全内容を作る。 */
export function makeDetail(
  overrides: Partial<OperatorDetail> = {},
): OperatorDetail {
  return {
    id: "op-1",
    filedAt: "2026-11-21T05:00:00Z",
    askerId: "local-user",
    utterance: "昨日まで見えていた求人が今日は見えません",
    urgency: "NORMAL",
    type: { id: "LOOKS_WRONG", label: "おかしいかもしれない", by: "ASKER" },
    system: { id: "job-admin", label: "求人管理画面", by: "ASKER" },
    function: null,
    intake: [
      makeTypeEntry("LOOKS_WRONG", "おかしいかもしれない"),
      {
        questionId: "since",
        kind: "DETAIL",
        text: "いつから起きていますか",
        answer: { kind: "OPTION", optionId: "today", label: "今日から" },
      },
      {
        questionId: "screen",
        kind: "DETAIL",
        text: "どの画面での話ですか",
        answer: { kind: "UNKNOWN" },
      },
    ],
    structured: {
      summary: "求人が一覧に出なくなった理由を知りたい。",
      targetSystem: "求人管理画面",
      goal: "理由を知りたい",
      whatHappened: null,
      expectedResult: null,
    },
    revisionNotes: ["消えたのは一昨日から"],
    answer: {
      grounded: true,
      askerExplanation: "掲載期限を過ぎると一覧から外れます。",
      memo: {
        findings: ["期限切れで非表示になる"],
        notFound: ["再掲載の手順"],
        nextChecks: ["掲載期限の設定を確認する"],
      },
      citations: [
        {
          sourceType: "CODE",
          repository: "job-board",
          path: "src/JobPostingService.java",
          startLine: 10,
          endLine: 14,
          quote: "if (expired) hide()",
          revision: "abc123",
          verified: true,
        },
        {
          sourceType: "FAQ",
          repository: "faq",
          path: "faq/ghost.md",
          startLine: 1,
          endLine: 2,
          quote: "存在しない記述",
          revision: "abc123",
          verified: false,
        },
      ],
    },
    sentImmediately: false,
    read: false,
    slackStatus: "POSTED",
    ...overrides,
  };
}

/** 偽物のオペレータ通知ストリーム。 */
export class FakeOperatorStream {
  readonly subscriptions: {
    handlers: OperatorStreamHandlers;
    closed: boolean;
  }[] = [];

  /** OperatorApp に渡す購読関数。 */
  readonly subscribe = (handlers: OperatorStreamHandlers) => {
    const subscription = { handlers, closed: false };
    this.subscriptions.push(subscription);
    return () => {
      subscription.closed = true;
    };
  };

  /** 最後の購読。 */
  get latest() {
    return this.subscriptions[this.subscriptions.length - 1];
  }
}
