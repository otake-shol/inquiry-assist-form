import type {
  IntakeAnswer,
  OperatorDetail,
  Phase,
  PlacementChange,
  PlacementNames,
  StateFields,
  SystemOption,
  Urgency,
} from "../api/types.ts";
import type { Messages } from "../i18n/messages.ts";

/**
 * オペレータ画面の文言は、言語ごとの辞書（`i18n/` の `op`）にある。担当者は開発チームなので、
 * 内部の言葉（出典・技術メモ）を使ってよい。このファイルには、辞書から文言を選ぶ純粋関数を置く。
 */

/** 担当者が切り替えられる区間（左から）。 */
export const SWITCHABLE_PHASES: Phase[] = ["KNOW", "DECIDE", "ACT"];

/**
 * 種類の呼び名。API が返す表示名をそのまま使い、決まっていなければ「種類未定」と示す。
 * @param type 種類（決まっていなければ null）
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function opTypeLabel(
  type: { label: string } | null,
  op: Messages["op"],
): string {
  return type === null ? op.typeUnknown : type.label;
}

/**
 * 緊急度の呼び名。一覧と詳細には緊急度の id だけが入るので、`GET /api/config` の名前があればそれを使う。
 * @param urgency 緊急度
 * @param urgencies 設定の緊急度の選択肢（取得前や取得に失敗したときは空）
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function opUrgencyLabel(
  urgency: Urgency,
  urgencies: SystemOption[],
  op: Messages["op"],
): string {
  return (
    urgencies.find((u) => u.id === urgency)?.label ?? op.urgencies[urgency]
  );
}

/**
 * 入口の答えの表示用の文字列。
 * @param answer 入口の質問への答え
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function opAnswerLabel(
  answer: IntakeAnswer,
  op: Messages["op"],
): string {
  switch (answer.kind) {
    case "OPTION":
      return answer.label;
    case "INFERRED":
      return `${answer.label}${op.inferredNote}`;
    case "TEXT":
      return answer.text;
    case "UNKNOWN":
      return op.unknownAnswer;
    case "SKIPPED":
      return op.skippedAnswer;
  }
}

/**
 * 状態の履歴の `event` の呼び名。知らない値は、そのまま返す。
 * @param event `event` の値
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function eventLabel(event: string, op: Messages["op"]): string {
  return op.events[event] ?? event;
}

/**
 * 置き場所の名前を区切りでつなぐ。決まっていない項目（null）は省く。
 * @param names 種類、システム、機能の名前
 * @param joiner 区切り（辞書の `meta.joiner`）
 */
function placementNamesText(names: PlacementNames, joiner: string): string {
  return [names.type, names.system, names.function]
    .filter((name): name is string => name !== null)
    .join(joiner);
}

/**
 * 置き直しの履歴に添える、変える前と後。例: 「おかしいかもしれない・システムA → やってほしい・システムA・機能A-1」。
 * @param change 変える前と後の置き場所
 * @param messages 辞書（`messages`）
 */
export function placementChangeText(
  change: PlacementChange,
  messages: Messages,
): string {
  const before = placementNamesText(change.before, messages.meta.joiner);
  const after = placementNamesText(change.after, messages.meta.joiner);
  const unset = messages.op.placementUnset;
  return `${before === "" ? unset : before} → ${after === "" ? unset : after}`;
}

/**
 * 状態を1行で表す。終わっていれば「終わり（終わり方）」、そうでなければ「誰の番（待っていること）」。
 * @param state 状態の4項目
 * @param messages 辞書（`messages`）
 */
export function stateLine(
  state: Pick<StateFields, "turn" | "awaiting" | "ending">,
  messages: Messages,
): string {
  const { op, meta } = messages;
  if (state.ending !== null) {
    return meta.paren(op.phases.CLOSED, op.endings[state.ending]);
  }
  if (state.turn === null) return op.phases.CLOSED;
  if (state.awaiting === null) return op.turns[state.turn];
  return meta.paren(op.turns[state.turn], op.awaiting[state.awaiting]);
}

/**
 * 担当者の番でないときに、フォームの代わりに示す、誰の番で何を待っているか。
 * @param detail 詳細
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function waitingText(
  detail: Pick<OperatorDetail, "ending" | "awaiting" | "turn">,
  op: Messages["op"],
): string {
  if (detail.ending !== null)
    return op.waitingClosed(op.endings[detail.ending]);
  switch (detail.awaiting) {
    case "ASKER_REPLY":
      return op.waitingAskerReply;
    case "RESULT_CONFIRMATION":
      return op.waitingResult;
    case "CONSULTATION":
      return op.consultWaitingPlain;
    default:
      return detail.turn === "AI" ? op.waitingAi : op.waitingAsker;
  }
}

/** 読み上げ用に、カードを呼ぶときの言い直しの長さの上限。 */
const MOVED_HEAD_LENGTH = 20;

/**
 * 問い合わせが別の列に移ったことを伝える文。カードは言い直しの先頭で呼ぶ。
 * @param summary 言い直しの文章
 * @param column 移った先の列の見出し
 * @param op 辞書の、担当者の画面の文言（`messages.op`）
 */
export function movedText(
  summary: string,
  column: string,
  op: Messages["op"],
): string {
  const head =
    summary.length > MOVED_HEAD_LENGTH
      ? `${summary.slice(0, MOVED_HEAD_LENGTH)}…`
      : summary;
  return op.moved(head, column);
}

/**
 * 日付を、月と日だけで表す（見る人の言語の書式。例: 「12月1日」「December 1」）。
 * @param iso ISO 8601 の日時
 * @param locale 日付の書き方に使うロケール（辞書の `meta.locale`）
 */
export function formatMonthDay(iso: string, locale: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat(locale, {
    month: "long",
    day: "numeric",
  }).format(date);
}

/**
 * 送られた時刻を「月/日 時:分」で表す（24時間。書き方は言語に合わせる）。
 * @param iso ISO 8601 の日時
 * @param locale 日時の書き方に使うロケール（辞書の `meta.locale`）
 */
export function formatFiledAt(iso: string, locale: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(locale, {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
}
