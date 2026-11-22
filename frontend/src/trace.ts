import type { TraceEntry } from "./api/types.ts";
import type { Messages } from "./i18n/messages.ts";

/** 段の種類。AI のスキルか、通常のコードか。 */
export type StageKind = "skill" | "code";

/** 実行記録の1段ぶんの集計。 */
export interface TraceStage {
  /** スキルの識別子。照合は `verifier` */
  key: string;
  /** 段の名前（「受付」など） */
  label: string;
  kind: StageKind;
  /** 動いた回数。0 なら、まだ動いていない */
  calls: number;
  /** 実装の組み合わせ（重複なし、動いた順）。コードの段は空 */
  implementations: { provider: string; model: string | null }[];
  /** 入力トークンの合計 */
  inputTokens: number;
  /** 出力トークンの合計 */
  outputTokens: number;
  /** 所要時間の合計（ミリ秒）。1つも記録がなければ null */
  durationMillis: number | null;
  /** 失敗した回数 */
  failedCalls: number;
}

/** 流れの順に並べた段の定義（名前は辞書）。照合（コード）は調査と説明の間に固定で置く。 */
const STAGES: { key: string; kind: StageKind }[] = [
  { key: "reception", kind: "skill" },
  { key: "interviewer", kind: "skill" },
  { key: "restater", kind: "skill" },
  { key: "investigator", kind: "skill" },
  { key: "verifier", kind: "code" },
  { key: "explainer", kind: "skill" },
];

/**
 * スキルの実行記録を、流れの順の段ごとに集計する純粋関数。
 * 受付 → 聞き手 → 言い直し → 調査 → 照合（コード） → 説明の6段を必ず返し、
 * 動いていない段は `calls` が 0 になる。同じスキルが複数回動いたときは回数と合計を持つ。
 * 照合は記録に現れないため、調査が1回でも成功していれば動いたものとして `calls` を 1 にする。
 * 一覧にないスキルの識別子は、末尾にスキルの段として足す。
 * @param entries 実行記録（無効または古い応答では null / undefined）
 * @param trace 辞書の、実行記録の文言（`messages.trace`。段の名前を持つ）
 */
export function summarizeTrace(
  entries: TraceEntry[] | null | undefined,
  trace: Messages["trace"],
): TraceStage[] {
  const list = entries ?? [];
  const known = new Set(STAGES.map((stage) => stage.key));
  const extra: string[] = [];
  for (const entry of list) {
    if (!known.has(entry.skill) && !extra.includes(entry.skill)) {
      extra.push(entry.skill);
    }
  }
  const defs = [
    ...STAGES,
    ...extra.map((key) => ({ key, kind: "skill" as StageKind })),
  ];

  const stages = defs.map<TraceStage>((def) => {
    const calls = list.filter((entry) => entry.skill === def.key);
    const implementations: TraceStage["implementations"] = [];
    let duration: number | null = null;
    for (const call of calls) {
      const model = call.model ?? null;
      if (
        !implementations.some(
          (i) => i.provider === call.provider && i.model === model,
        )
      ) {
        implementations.push({ provider: call.provider, model });
      }
      if (call.durationMillis != null) {
        duration = (duration ?? 0) + call.durationMillis;
      }
    }
    return {
      key: def.key,
      label: trace.stages[def.key] ?? def.key,
      kind: def.kind,
      calls: calls.length,
      implementations,
      inputTokens: calls.reduce((sum, c) => sum + (c.inputTokens ?? 0), 0),
      outputTokens: calls.reduce((sum, c) => sum + (c.outputTokens ?? 0), 0),
      durationMillis: duration,
      failedCalls: calls.filter((c) => c.failed).length,
    };
  });

  const investigator = stages.find((stage) => stage.key === "investigator");
  const verifier = stages.find((stage) => stage.key === "verifier");
  if (
    investigator !== undefined &&
    verifier !== undefined &&
    investigator.calls > investigator.failedCalls
  ) {
    verifier.calls = 1;
  }
  return stages;
}

/**
 * 所要時間の表示。1秒未満はミリ秒、それ以上は秒（小数1桁）。
 * @param millis ミリ秒（記録がなければ null）
 * @param trace 辞書の、実行記録の文言（`messages.trace`）
 */
export function formatDuration(
  millis: number | null,
  trace: Messages["trace"],
): string {
  if (millis === null) return trace.noRecord;
  return millis < 1000
    ? trace.millis(millis)
    : trace.seconds((millis / 1000).toFixed(1));
}

/**
 * 実装の表示。モックは「モック」、`ollama` は「手元のモデル」とモデルの名前、その他は名前のまま。
 * @param provider 実装の名前
 * @param model モデルの名前
 * @param messages 辞書（`messages`。実行記録の文言と、「A（B）」の形を持つ）
 */
export function implementationLabel(
  provider: string,
  model: string | null,
  messages: Messages,
): string {
  if (provider === "mock") return messages.trace.mock;
  const base = provider === "ollama" ? messages.trace.local : provider;
  return model === null ? base : messages.meta.paren(base, model);
}
