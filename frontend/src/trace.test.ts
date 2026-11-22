import { describe, expect, it } from "vitest";
import { en } from "./i18n/en.ts";
import { ja } from "./i18n/ja.ts";
import { makeTraceEntry } from "./test/fakes.ts";
import {
  formatDuration,
  implementationLabel,
  summarizeTrace,
} from "./trace.ts";

describe("summarizeTrace", () => {
  it("記録がなくても、受付 → 聞き手 → 言い直し → 調査 → 照合（コード） → 説明の順に6段を返し、どれも動いていない", () => {
    for (const empty of [[], null, undefined]) {
      const stages = summarizeTrace(empty, ja.trace);
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
    const stages = summarizeTrace(
      [
        makeTraceEntry({ skill: "explainer" }),
        makeTraceEntry({ skill: "reception" }),
      ],
      ja.trace,
    );
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
    const stages = summarizeTrace(
      [
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
      ],
      ja.trace,
    );
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
    const stages = summarizeTrace(
      [
        makeTraceEntry({ skill: "reception", durationMillis: null }),
        makeTraceEntry({
          skill: "interviewer",
          provider: "mock",
          model: null,
          durationMillis: 40,
        }),
        makeTraceEntry({ skill: "interviewer", durationMillis: null }),
      ],
      ja.trace,
    );
    expect(stages[0].durationMillis).toBeNull();
    expect(stages[1].durationMillis).toBe(40);
    expect(stages[1].implementations.map((i) => i.provider)).toEqual([
      "mock",
      "ollama",
    ]);
  });

  it("失敗した回数を数え、失敗した調査だけでは照合が動いたことにしない", () => {
    const failed = summarizeTrace(
      [makeTraceEntry({ skill: "investigator", failed: true })],
      ja.trace,
    );
    expect(failed.find((s) => s.key === "investigator")!.failedCalls).toBe(1);
    expect(failed.find((s) => s.key === "verifier")!.calls).toBe(0);

    const ok = summarizeTrace(
      [
        makeTraceEntry({ skill: "investigator", failed: true }),
        makeTraceEntry({ skill: "investigator" }),
      ],
      ja.trace,
    );
    expect(ok.find((s) => s.key === "verifier")!.calls).toBe(1);
  });

  it("一覧にないスキルは末尾に足す", () => {
    const stages = summarizeTrace(
      [makeTraceEntry({ skill: "planner" })],
      ja.trace,
    );
    expect(stages[stages.length - 1]).toMatchObject({
      key: "planner",
      calls: 1,
    });
  });
});

describe("表示用の整形", () => {
  it("所要時間は1秒未満をミリ秒、以上を秒で示し、記録がなければそう示す", () => {
    expect(formatDuration(420, ja.trace)).toBe("420ミリ秒");
    expect(formatDuration(2921, ja.trace)).toBe("2.9秒");
    expect(formatDuration(null, ja.trace)).toBe("記録なし");
  });

  it("実装はモック、手元のモデルとモデルの名前で示す", () => {
    expect(implementationLabel("mock", null, ja)).toBe("モック");
    expect(implementationLabel("ollama", "qwen3.6:35b", ja)).toBe(
      "手元のモデル（qwen3.6:35b）",
    );
    expect(implementationLabel("ollama", null, ja)).toBe("手元のモデル");
  });

  it("英語では、英語の文言と英語の括弧で示す", () => {
    expect(formatDuration(420, en.trace)).toBe("420 ms");
    expect(formatDuration(2921, en.trace)).toBe("2.9 s");
    expect(formatDuration(null, en.trace)).toBe("No record");
    expect(implementationLabel("ollama", "qwen3.6:35b", en)).toBe(
      "Local model (qwen3.6:35b)",
    );
    const stages = summarizeTrace([], en.trace);
    expect(stages.map((s) => s.label)).toEqual([
      "Reception",
      "Interviewer",
      "Restater",
      "Investigator",
      "Verification (code)",
      "Explainer",
    ]);
  });
});

describe("担当者の詳細の段（助言、案の確認（コード）、下書き、訳）", () => {
  it("担当者の詳細では、説明の後に 助言 → 案の確認（コード） → 下書き → 訳 を足して10段にする。本人のページは6段のまま", () => {
    const operator = summarizeTrace([], ja.trace, true);
    expect(operator.map((s) => [s.key, s.kind, s.label])).toEqual([
      ["reception", "skill", "受付"],
      ["interviewer", "skill", "聞き手"],
      ["restater", "skill", "言い直し"],
      ["investigator", "skill", "調査"],
      ["verifier", "code", "照合（コード）"],
      ["explainer", "skill", "説明"],
      ["advisor", "skill", "助言"],
      ["adviceVerifier", "code", "案の確認（コード）"],
      ["drafter", "skill", "下書き"],
      ["translator", "skill", "訳"],
    ]);
    expect(summarizeTrace([], ja.trace)).toHaveLength(6);
    expect(
      summarizeTrace([], en.trace, true)
        .slice(6)
        .map((s) => s.label),
    ).toEqual(["Advisor", "Suggestion check (code)", "Drafter", "Translator"]);
  });

  it("助言が1回でも成功していれば、案の確認（コード）を動いたものとして数える。失敗だけなら動かない", () => {
    const ok = summarizeTrace(
      [makeTraceEntry({ skill: "advisor" })],
      ja.trace,
      true,
    );
    expect(ok.find((s) => s.key === "adviceVerifier")!.calls).toBe(1);
    const failed = summarizeTrace(
      [makeTraceEntry({ skill: "advisor", failed: true })],
      ja.trace,
      true,
    );
    expect(failed.find((s) => s.key === "adviceVerifier")!.calls).toBe(0);
  });

  it("本人のページでは、助言、下書き、訳の記録があっても段に出さない", () => {
    const stages = summarizeTrace(
      [
        makeTraceEntry({ skill: "advisor" }),
        makeTraceEntry({ skill: "drafter" }),
        makeTraceEntry({ skill: "translator" }),
      ],
      ja.trace,
    );
    expect(stages.map((s) => s.key)).toEqual([
      "reception",
      "interviewer",
      "restater",
      "investigator",
      "verifier",
      "explainer",
    ]);
  });
});
