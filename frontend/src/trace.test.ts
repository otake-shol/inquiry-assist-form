import { describe, expect, it } from "vitest";
import { makeTraceEntry } from "./test/fakes.ts";
import {
  formatDuration,
  implementationLabel,
  summarizeTrace,
} from "./trace.ts";

describe("summarizeTrace", () => {
  it("記録がなくても、受付 → 聞き手 → 言い直し → 調査 → 照合（コード） → 説明の順に6段を返し、どれも動いていない", () => {
    for (const empty of [[], null, undefined]) {
      const stages = summarizeTrace(empty);
      expect(stages.map((s) => s.label)).toEqual([
        "受付",
        "聞き手",
        "言い直し",
        "調査",
        "照合（コード）",
        "説明",
      ]);
      expect(stages.every((s) => s.calls === 0)).toBe(true);
    }
  });

  it("記録の並びに関係なく流れの順に段を並べ、種類を分ける", () => {
    const stages = summarizeTrace([
      makeTraceEntry({ skill: "explainer" }),
      makeTraceEntry({ skill: "reception" }),
    ]);
    expect(stages.map((s) => [s.key, s.kind, s.calls])).toEqual([
      ["reception", "skill", 1],
      ["interviewer", "skill", 0],
      ["restater", "skill", 0],
      ["investigator", "skill", 0],
      ["verifier", "code", 0],
      ["explainer", "skill", 1],
    ]);
  });

  it("同じスキルが複数回動いたら、回数・トークン・時間を合計する", () => {
    const stages = summarizeTrace([
      makeTraceEntry({
        skill: "restater",
        inputTokens: 100,
        outputTokens: 10,
        durationMillis: 500,
      }),
      makeTraceEntry({
        skill: "restater",
        inputTokens: 200,
        outputTokens: 30,
        durationMillis: 700,
      }),
    ]);
    const restater = stages.find((s) => s.key === "restater")!;
    expect(restater).toMatchObject({
      calls: 2,
      inputTokens: 300,
      outputTokens: 40,
      durationMillis: 1200,
      failedCalls: 0,
    });
    expect(restater.implementations).toEqual([
      { provider: "ollama", model: "qwen3.6:35b" },
    ]);
  });

  it("時間の記録がない呼び出しは null、実装が混ざれば両方を残す", () => {
    const stages = summarizeTrace([
      makeTraceEntry({ skill: "reception", durationMillis: null }),
      makeTraceEntry({
        skill: "interviewer",
        provider: "mock",
        model: null,
        durationMillis: 40,
      }),
      makeTraceEntry({ skill: "interviewer", durationMillis: null }),
    ]);
    expect(stages[0].durationMillis).toBeNull();
    expect(stages[1].durationMillis).toBe(40);
    expect(stages[1].implementations.map((i) => i.provider)).toEqual([
      "mock",
      "ollama",
    ]);
  });

  it("失敗した回数を数え、失敗した調査だけでは照合が動いたことにしない", () => {
    const failed = summarizeTrace([
      makeTraceEntry({ skill: "investigator", failed: true }),
    ]);
    expect(failed.find((s) => s.key === "investigator")!.failedCalls).toBe(1);
    expect(failed.find((s) => s.key === "verifier")!.calls).toBe(0);

    const ok = summarizeTrace([
      makeTraceEntry({ skill: "investigator", failed: true }),
      makeTraceEntry({ skill: "investigator" }),
    ]);
    expect(ok.find((s) => s.key === "verifier")!.calls).toBe(1);
  });

  it("一覧にないスキルは末尾に足す", () => {
    const stages = summarizeTrace([makeTraceEntry({ skill: "planner" })]);
    expect(stages[stages.length - 1]).toMatchObject({
      key: "planner",
      calls: 1,
    });
  });
});

describe("表示用の整形", () => {
  it("所要時間は1秒未満をミリ秒、以上を秒で示し、記録がなければそう示す", () => {
    expect(formatDuration(420)).toBe("420ミリ秒");
    expect(formatDuration(2921)).toBe("2.9秒");
    expect(formatDuration(null)).toBe("記録なし");
  });

  it("実装はモック、手元のモデルとモデルの名前で示す", () => {
    expect(implementationLabel("mock", null)).toBe("モック");
    expect(implementationLabel("ollama", "qwen3.6:35b")).toBe(
      "手元のモデル（qwen3.6:35b）",
    );
    expect(implementationLabel("ollama", null)).toBe("手元のモデル");
  });
});
