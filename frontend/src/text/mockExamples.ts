import type { InquiryCategory } from "../api/types.ts";
import { CATEGORY_LABELS } from "./labels.ts";

/** モックで試せる例文。 */
export interface MockExample {
  label: string;
  text: string;
}

/**
 * 種別ごとの例文（契約書「モックのシナリオ」のキーワードに合わせている）。
 * 仕様確認の例文にはどの種別のキーワードも含めない。
 */
export const CATEGORY_EXAMPLES: Record<InquiryCategory, string> = {
  SPEC_QUESTION: "求人の掲載期限は、何日まで延長できますか？",
  SUSPECTED_BUG: "応募者一覧の件数が実際と合わず、不具合かもしれません。",
  WORK_REQUEST: "掲載期限を一括で延長してほしいです。",
  INCIDENT: "求人管理画面が落ちていて、応募者を確認できません。",
};

/** タグの例文に使うベース（正常系になる仕様確認の例文）。 */
const TAG_BASE = CATEGORY_EXAMPLES.SPEC_QUESTION;

/** 振る舞いを切り替えるタグ。 */
export const SCENARIO_TAGS = [
  "[質問なし]",
  "[根拠なし]",
  "[幽霊出典]",
  "[遅延]",
  "[エラー]",
  "[拒否]",
  "[判定エラー]",
] as const;

/** 種別の例文ボタン用の一覧。 */
export const CATEGORY_EXAMPLE_LIST: MockExample[] = (
  Object.keys(CATEGORY_EXAMPLES) as InquiryCategory[]
).map((category) => ({
  label: CATEGORY_LABELS[category],
  text: CATEGORY_EXAMPLES[category],
}));

/** タグの例文ボタン用の一覧。 */
export const TAG_EXAMPLE_LIST: MockExample[] = SCENARIO_TAGS.map((tag) => ({
  label: tag,
  text: `${TAG_BASE} ${tag}`,
}));
