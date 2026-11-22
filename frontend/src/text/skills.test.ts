import { describe, expect, it } from "vitest";
import { en } from "../i18n/en.ts";
import { ja } from "../i18n/ja.ts";
import { skillMixOf, skillMixText } from "./skills.ts";

describe("skillMixOf", () => {
  it("skills が無い・全モックなら一部モックではない", () => {
    expect(skillMixOf(null, ja.skills.labels).partial).toBe(false);
    expect(skillMixOf(undefined, ja.skills.labels).partial).toBe(false);
    expect(
      skillMixOf({ reception: "mock", investigator: "mock" }, ja.skills.labels)
        .partial,
    ).toBe(false);
  });

  const skills = {
    explainer: "ollama",
    investigator: "mock",
    reception: "ollama",
    interviewer: "ollama",
    restater: "ollama",
  };

  it("実際のモデルがあれば、表示順で日本語名に分ける", () => {
    const mix = skillMixOf(skills, ja.skills.labels);
    expect(mix).toEqual({
      partial: true,
      real: ["受付", "聞き手", "言い直し", "説明"],
      mock: ["調査"],
      mockIds: ["investigator"],
    });
    expect(skillMixText(mix, ja.skills)).toBe(
      "受付・聞き手・言い直し・説明は手元のモデル、調査はモックで動いています",
    );
  });

  it("英語では、英語の呼び名で分け、識別子は言語によらない", () => {
    const mix = skillMixOf(skills, en.skills.labels);
    expect(mix.mock).toEqual(["Investigator"]);
    expect(mix.mockIds).toEqual(["investigator"]);
    expect(skillMixText(mix, en.skills)).toBe(
      "Reception, Interviewer, Restater, Explainer run on the local model, Investigator run as mocks",
    );
  });

  it("スキルが7つ（助言、下書きを含む）になっても、内訳を日英で組み立てられる", () => {
    const seven = {
      reception: "mock",
      interviewer: "mock",
      restater: "mock",
      investigator: "mock",
      explainer: "mock",
      advisor: "mock",
      drafter: "mock",
    };
    expect(skillMixOf(seven, ja.skills.labels).partial).toBe(false);
    const mix = skillMixOf(
      { ...seven, advisor: "ollama", drafter: "ollama" },
      ja.skills.labels,
    );
    expect(mix.real).toEqual(["助言", "下書き"]);
    expect(mix.mock).toHaveLength(5);
    expect(skillMixText(mix, ja.skills)).toBe(
      "助言・下書きは手元のモデル、受付・聞き手・言い直し・調査・説明はモックで動いています",
    );
    const english = skillMixOf(
      { ...seven, advisor: "ollama" },
      en.skills.labels,
    );
    expect(english.real).toEqual(["Advisor"]);
  });
});
