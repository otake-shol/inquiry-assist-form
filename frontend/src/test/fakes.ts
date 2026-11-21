import { vi } from "vitest";
import type {
  AnswerStreamHandlers,
  OperatorStreamHandlers,
} from "../api/stream.ts";
import type {
  InquiryView,
  OperatorDetail,
  OperatorItem,
  ZeroAnswer,
} from "../api/types.ts";

/** テスト用に問い合わせの表現を作る。省略した項目は仕様確認の初期状態。 */
export function makeView(overrides: Partial<InquiryView> = {}): InquiryView {
  return {
    id: "inq-1",
    step: "TRIAGED",
    outcome: "IN_PROGRESS",
    utterance: "求人の掲載期限は何日まで延長できますか？",
    categoryByAi: "SPEC_QUESTION",
    category: "SPEC_QUESTION",
    questions: [],
    system: null,
    systemGuessed: false,
    revisionsLeft: 2,
    structured: null,
    answer: null,
    incidentGuidance: null,
    sentImmediately: false,
    mock: true,
    ...overrides,
  };
}

/** 整形結果つきの STRUCTURED 状態を作る。 */
export function makeStructuredView(
  overrides: Partial<InquiryView> = {},
): InquiryView {
  return makeView({
    step: "STRUCTURED",
    structured: {
      summary: "求人の掲載期限を、何日まで延ばせるかを知りたい。",
      targetSystem: "求人管理画面",
      goal: "掲載期限を延ばせる日数を知りたい",
      whatHappened: null,
      expectedResult: null,
      urgency: "NORMAL",
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
    "GET /api/config": { body: { mock: false } },
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
    category: "SUSPECTED_BUG",
    system: { id: "job-admin", label: "求人管理画面" },
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
    categoryByAi: "SUSPECTED_BUG",
    category: "SUSPECTED_BUG",
    system: { id: "job-admin", label: "求人管理画面" },
    systemGuessed: false,
    followUps: [
      { text: "求人の状態はどれですか", answer: "掲載中" },
      { text: "どの画面での話ですか", answer: null },
    ],
    structured: {
      summary: "求人が一覧に出なくなった理由を知りたい。",
      targetSystem: "求人管理画面",
      goal: "理由を知りたい",
      whatHappened: null,
      expectedResult: null,
      urgency: "NORMAL",
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
