/** スキルの識別子と日本語名（表示順）。 */
export const SKILL_LABELS: [id: string, label: string][] = [
  ["reception", "受付"],
  ["interviewer", "聞き手"],
  ["restater", "言い直し"],
  ["investigator", "調査"],
  ["explainer", "説明"],
];

/** スキルごとの実装の構成。 */
export interface SkillMix {
  /** 一部が実際のモデルか（全スキルがモック、または構成が不明なら false） */
  partial: boolean;
  /** 実際のモデルで動いているスキルの日本語名 */
  real: string[];
  /** モックで動いているスキルの日本語名 */
  mock: string[];
}

/**
 * `GET /api/config` の `skills` から、どのスキルがモックかを整理する。
 * `skills` が無い（古い API）ときは全スキルがモックとみなす。
 * @param skills スキルの識別子から実装の名前（`mock` / `ollama`）への対応
 */
export function skillMixOf(
  skills: Record<string, string> | null | undefined,
): SkillMix {
  if (skills === null || skills === undefined) {
    return { partial: false, real: [], mock: [] };
  }
  const real: string[] = [];
  const mock: string[] = [];
  for (const [id, label] of SKILL_LABELS) {
    const impl = skills[id];
    if (impl === undefined) continue;
    (impl === "mock" ? mock : real).push(label);
  }
  return { partial: real.length > 0, real, mock };
}

/**
 * 構成の内訳を1〜2行の文にする。例: 「受付・聞き手は手元のモデル、調査はモックで動いています」。
 * @param mix スキルごとの実装の構成
 */
export function skillMixText(mix: SkillMix): string {
  const real = `${mix.real.join("・")}は手元のモデル`;
  if (mix.mock.length === 0) {
    return "全スキルが手元のモデルで動いています（一次情報の検索がモックです）";
  }
  return `${real}、${mix.mock.join("・")}はモックで動いています`;
}
