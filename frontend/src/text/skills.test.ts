import { describe, expect, it } from "vitest";
import { skillMixOf, skillMixText } from "./skills.ts";

describe("skillMixOf", () => {
  it("skills が無い・全モックなら一部モックではない", () => {
    expect(skillMixOf(null).partial).toBe(false);
    expect(skillMixOf(undefined).partial).toBe(false);
    expect(
      skillMixOf({ reception: "mock", investigator: "mock" }).partial,
    ).toBe(false);
  });

  it("実際のモデルがあれば、表示順で日本語名に分ける", () => {
    const mix = skillMixOf({
      explainer: "ollama",
      investigator: "mock",
      reception: "ollama",
      interviewer: "ollama",
      restater: "ollama",
    });
    expect(mix).toEqual({
      partial: true,
      real: ["受付", "聞き手", "言い直し", "説明"],
      mock: ["調査"],
    });
    expect(skillMixText(mix)).toBe(
      "受付・聞き手・言い直し・説明は手元のモデル、調査はモックで動いています",
    );
  });
});
