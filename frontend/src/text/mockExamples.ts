import type { Messages } from "../i18n/messages.ts";

/** モックで試せる例文。 */
export interface MockExample {
  label: string;
  text: string;
}

/**
 * 振る舞いを切り替えるタグ（契約書「モックのシナリオ」の表の順）。タグは識別子なので、言語で変えない。
 */
export const SCENARIO_TAGS = [
  "[判定エラー]",
  "[質問なし]",
  "[質問エラー]",
  "[言い直しエラー]",
  "[根拠なし]",
  "[幽霊出典]",
  "[一部幽霊]",
  "[遅延]",
  "[エラー]",
  "[拒否]",
  "[説明エラー]",
  "[的外れ]",
  "[手順なし]",
  "[相談]",
  "[手順]",
  "[根拠なしの案]",
  "[案エラー]",
  "[下書きエラー]",
] as const;

/**
 * 種類の例文ボタン用の一覧。例文は、契約書「モックのシナリオ」の受付が、その種類に置く語を使う
 * （日本語の例文は日本語の語、英語の例文は英語の語。優先順位が上の語を含まない）。
 * @param mock 辞書の、モックの案内枠の文言（`messages.mock`）
 */
export function typeExampleList(mock: Messages["mock"]): MockExample[] {
  return Object.values(mock.typeExamples);
}

/**
 * タグの例文ボタン用の一覧。正常系になる「仕組みを知りたい」の例文に、タグを付ける。
 * @param mock 辞書の、モックの案内枠の文言（`messages.mock`）
 */
export function tagExampleList(mock: Messages["mock"]): MockExample[] {
  const base = mock.typeExamples.HOW_IT_WORKS.text;
  return SCENARIO_TAGS.map((tag) => ({ label: tag, text: `${base} ${tag}` }));
}
