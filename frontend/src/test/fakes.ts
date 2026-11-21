import { vi } from "vitest";
import type { AnswerStreamHandlers } from "../api/stream.ts";
import type { InquiryView, ZeroAnswer } from "../api/types.ts";

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
