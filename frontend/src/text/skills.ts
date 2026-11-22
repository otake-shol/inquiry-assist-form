import type { Messages } from "../i18n/messages.ts";

/** スキルごとの実装の構成。 */
export interface SkillMix {
  /** 一部が実際のモデルか（全スキルがモック、または構成が不明なら false） */
  partial: boolean;
  /** 実際のモデルで動いているスキルの呼び名 */
  real: string[];
  /** モックで動いているスキルの呼び名 */
  mock: string[];
  /** モックで動いているスキルの識別子 */
  mockIds: string[];
}

/**
 * `GET /api/config` の `skills` から、どのスキルがモックかを整理する。
 * `skills` が無い（古い API）ときは全スキルがモックとみなす。
 * @param skills スキルの識別子から実装の名前（`mock` など）への対応
 * @param labels 辞書の、スキルの識別子と呼び名（表示順。`messages.skills.labels`）
 */
export function skillMixOf(
  skills: Record<string, string> | null | undefined,
  labels: Messages["skills"]["labels"],
): SkillMix {
  if (skills === null || skills === undefined) {
    return { partial: false, real: [], mock: [], mockIds: [] };
  }
  const real: string[] = [];
  const mock: string[] = [];
  const mockIds: string[] = [];
  for (const [id, label] of labels) {
    const impl = skills[id];
    if (impl === undefined) continue;
    if (impl === "mock") {
      mock.push(label);
      mockIds.push(id);
    } else {
      real.push(label);
    }
  }
  return { partial: real.length > 0, real, mock, mockIds };
}

/**
 * 構成の内訳を1〜2行の文にする。例: 「受付・聞き手は実際のモデル、調査はモックで動いています」。
 * @param mix スキルごとの実装の構成
 * @param skills 辞書の、スキルの説明（`messages.skills`）
 */
export function skillMixText(
  mix: SkillMix,
  skills: Messages["skills"],
): string {
  if (mix.mock.length === 0) return skills.allReal;
  return skills.mixText(mix.real, mix.mock);
}
