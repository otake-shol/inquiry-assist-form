import type { MessagesOverrides } from "./messages.ts";

/**
 * 文言の上書き。各チームが、骨組みの辞書（`ja.ts`、`en.ts`）を書き換えずに、この1ファイルだけで
 * 画面の文言を変えるための置き場。言語ごとに、辞書と同じ入れ子の形で、変えたい鍵だけを書く。
 * 書いていない鍵は、辞書のまま。関数の文言は、同じ引数の関数で上書きする。配列（スキルの呼び名の一覧
 * `skills.labels` など）は、要素ごとではなく、配列ごと置き換わる。
 * 型で、辞書にない鍵や、型の違う値は書けない。
 *
 * 例: 製品の名前を変える（本人向けの画面の名前と、担当者の画面の題名）。
 *
 * ```ts
 * export const overrides: MessagesOverrides = {
 *   ja: {
 *     text: { appTitle: "社内ヘルプ" },
 *     op: { title: "社内ヘルプ受付", tabTitle: "社内ヘルプ受付" },
 *   },
 *   en: {
 *     text: { appTitle: "Internal Help" },
 *     op: { title: "Internal Help inbox", tabTitle: "Internal Help inbox" },
 *   },
 * };
 * ```
 *
 * 例: 関数の文言（件数つきの文）を上書きする。引数は、辞書の関数と同じ。
 *
 * ```ts
 * ja: { op: { messageCount: (count) => `やり取り ${count}通` } }
 * ```
 */
export const overrides: MessagesOverrides = {
  ja: {},
  en: {},
};
