import type { InquiryTypeId } from "../api/types.ts";

/** モックで試せる例文。 */
export interface MockExample {
  label: string;
  text: string;
}

/**
 * 種類ごとの例文。契約書「モックのシナリオ」の語で、受付が狙いの種類を置くように書いている。
 * 優先順位（変えてほしい、してほしい、不具合、使い方の順）が上の語を含まないようにしてある。
 * 「仕組みを知りたい」の例文には、どの種類の語も含めない。
 */
export const TYPE_EXAMPLES: Record<InquiryTypeId, MockExample> = {
  HOW_TO: {
    label: "使い方を知りたい",
    text: "求人の掲載期限を延ばす手順を知りたいです。",
  },
  HOW_IT_WORKS: {
    label: "仕組みを知りたい",
    text: "求人の掲載期限は、何日まで延長できますか？",
  },
  LOOKS_WRONG: {
    label: "おかしいかもしれない",
    text: "応募者一覧の件数が実際と合わず、不具合かもしれません。",
  },
  DO_FOR_ME: {
    label: "やってほしい",
    text: "掲載期限を一括で延長してほしいです。",
  },
  CHANGE_REQUEST: {
    label: "変えてほしい",
    text: "応募者一覧の並び順を、新しい順に変えてほしいです。",
  },
};

/** タグの例文に使うベース（正常系になる「仕組みを知りたい」の例文）。 */
const TAG_BASE = TYPE_EXAMPLES.HOW_IT_WORKS.text;

/** 振る舞いを切り替えるタグ（契約書「モックのシナリオ」の表の順）。 */
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
] as const;

/** 種類の例文ボタン用の一覧。 */
export const TYPE_EXAMPLE_LIST: MockExample[] = Object.values(TYPE_EXAMPLES);

/** タグの例文ボタン用の一覧。 */
export const TAG_EXAMPLE_LIST: MockExample[] = SCENARIO_TAGS.map((tag) => ({
  label: tag,
  text: `${TAG_BASE} ${tag}`,
}));
