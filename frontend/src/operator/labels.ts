import type {
  Awaiting,
  DeclineReasonKind,
  Ending,
  HistoryActor,
  IntakeAnswer,
  OperatorDetail,
  Phase,
  PlacementChange,
  PlacementNames,
  StateFields,
  SystemOption,
  Turn,
  Urgency,
} from "../api/types.ts";

/**
 * オペレータ画面の文言。担当者は開発チームなので、内部の言葉（出典・技術メモ）を使ってよい。
 */

/**
 * 種類の呼び名。API が返す表示名をそのまま使い、決まっていなければ「種類未定」と示す。
 * @param type 種類（決まっていなければ null）
 */
export function opTypeLabel(type: { label: string } | null): string {
  return type === null ? OP_TEXT.typeUnknown : type.label;
}

/** 緊急度の呼び名（設定から名前を取れないときの代わり）。 */
const URGENCY_LABELS: Record<Urgency, string> = {
  NORMAL: "通常",
  HURRY: "急ぎ",
  BLOCKED: "業務が止まっている",
};

/**
 * 緊急度の呼び名。一覧と詳細には緊急度の id だけが入るので、`GET /api/config` の名前があればそれを使う。
 * @param urgency 緊急度
 * @param urgencies 設定の緊急度の選択肢（取得前や取得に失敗したときは省く）
 */
export function opUrgencyLabel(
  urgency: Urgency,
  urgencies: SystemOption[] = [],
): string {
  return (
    urgencies.find((u) => u.id === urgency)?.label ?? URGENCY_LABELS[urgency]
  );
}

/**
 * 入口の答えの表示用の文字列。
 * @param answer 入口の質問への答え
 */
export function opAnswerLabel(answer: IntakeAnswer): string {
  switch (answer.kind) {
    case "OPTION":
      return answer.label;
    case "INFERRED":
      return `${answer.label}${OP_TEXT.inferredNote}`;
    case "TEXT":
      return answer.text;
    case "UNKNOWN":
      return OP_TEXT.unknownAnswer;
    case "SKIPPED":
      return OP_TEXT.skippedAnswer;
  }
}

/** 区間の呼び名。 */
export const PHASE_LABELS: Record<Phase, string> = {
  INTAKE: "受付",
  KNOW: "分かる",
  DECIDE: "決まる",
  ACT: "行われる",
  CLOSED: "終わり",
};

/** 担当者が切り替えられる区間（左から）。 */
export const SWITCHABLE_PHASES: Phase[] = ["KNOW", "DECIDE", "ACT"];

/** 誰の番かの呼び名。 */
export const TURN_LABELS: Record<Turn, string> = {
  ASKER: "本人の番",
  AI: "AIの番",
  OPERATOR: "担当者の番",
};

/** 待っていることの呼び名。 */
export const AWAITING_LABELS: Record<Awaiting, string> = {
  INTAKE_ANSWER: "入口の質問に答えている",
  QUESTION_CONFIRMATION: "問いを確かめている",
  INVESTIGATION: "AIが調べている",
  RESULT_CONFIRMATION: "結果の確かめ待ち",
  HANDLING: "担当者が受けている",
  ASKER_REPLY: "返事待ち",
};

/** 終わり方の呼び名。 */
export const ENDING_LABELS: Record<Ending, string> = {
  RESOLVED: "解決済み",
  ANSWERED: "回答済み",
  DECLINED: "見送り",
  WITHDRAWN: "取り下げ",
};

/** 見送りの理由の種類の呼び名（選択肢の並び順）。 */
export const DECLINE_REASON_LABELS: Record<DeclineReasonKind, string> = {
  NOT_REPRODUCIBLE: "再現しない",
  KNOWN_LIMITATION: "既知の制限",
  OUT_OF_SCOPE: "対象外",
  DUPLICATE: "重複",
  OTHER: "その他",
};

/** 状態の履歴で、変化を起こした人の呼び名。 */
export const ACTOR_LABELS: Record<HistoryActor, string> = {
  ASKER: "本人",
  AI: "AI",
  OPERATOR: "担当者",
};

/** 状態の履歴の `event` の呼び名。 */
const EVENT_LABELS: Record<string, string> = {
  CREATED: "問い合わせができた",
  QUESTION_COMPOSED: "問いを組み立てた",
  REOPENED_INTAKE: "入口の質問へ戻った",
  QUESTION_CONFIRMED: "問いを確かめた",
  ANSWERED_BY_AI: "AI が答えた",
  HANDED_OVER: "担当者に渡した",
  ASKED_BACK: "本人に聞き返した",
  REPLIED: "聞き返しに答えた",
  RESULT_DELIVERED: "結果を伝えた",
  RESOLVED: "解決を伝えた",
  DECLINED: "見送った",
  WITHDRAWN: "取り下げた",
  REOPENED: "追記で開き直した",
  PHASE_CHANGED: "区間を変えた",
  PLACEMENT_CHANGED: "置き直した",
};

/**
 * 状態の履歴の `event` の呼び名。知らない値は、そのまま返す。
 * @param event `event` の値
 */
export function eventLabel(event: string): string {
  return EVENT_LABELS[event] ?? event;
}

/**
 * 置き場所の名前を「・」でつなぐ。決まっていない項目（null）は省く。
 * @param names 種類、システム、機能の名前
 */
function placementNamesText(names: PlacementNames): string {
  return [names.type, names.system, names.function]
    .filter((name): name is string => name !== null)
    .join("・");
}

/**
 * 置き直しの履歴に添える、変える前と後。例: 「おかしいかもしれない・システムA → やってほしい・システムA・機能A-1」。
 * @param change 変える前と後の置き場所
 */
export function placementChangeText(change: PlacementChange): string {
  const before = placementNamesText(change.before);
  const after = placementNamesText(change.after);
  return `${before === "" ? OP_TEXT.placementUnset : before} → ${after === "" ? OP_TEXT.placementUnset : after}`;
}

/**
 * 状態を1行で表す。終わっていれば「終わり（終わり方）」、そうでなければ「誰の番（待っていること）」。
 * @param state 状態の4項目
 */
export function stateLine(
  state: Pick<StateFields, "turn" | "awaiting" | "ending">,
): string {
  if (state.ending !== null) {
    return `${PHASE_LABELS.CLOSED}（${ENDING_LABELS[state.ending]}）`;
  }
  if (state.turn === null) return PHASE_LABELS.CLOSED;
  if (state.awaiting === null) return TURN_LABELS[state.turn];
  return `${TURN_LABELS[state.turn]}（${AWAITING_LABELS[state.awaiting]}）`;
}

/**
 * 担当者の番でないときに、フォームの代わりに示す、誰の番で何を待っているか。
 * @param detail 詳細
 */
export function waitingText(
  detail: Pick<OperatorDetail, "ending" | "awaiting" | "turn">,
): string {
  if (detail.ending !== null) {
    return `この問い合わせは終わっています（${ENDING_LABELS[detail.ending]}）。本人が追記すると、担当者の番で開き直します。`;
  }
  switch (detail.awaiting) {
    case "ASKER_REPLY":
      return "本人の返事を待っています。";
    case "RESULT_CONFIRMATION":
      return "本人が結果を確かめるのを待っています。";
    default:
      return detail.turn === "AI"
        ? "AI の処理を待っています。"
        : "本人の操作を待っています。";
  }
}

/** 画面の固定文言。 */
export const OP_TEXT = {
  title: "問い合わせ受付",
  tabTitle: "問い合わせ受付",
  unreadOnly: "未読だけ",
  listLabel: "受付一覧",
  detailLabel: "問い合わせの詳細",
  close: "閉じる",
  loading: "読み込んでいます…",
  messageCount: (count: number) => `やり取り ${count}件`,
  arrived: "新しい問い合わせが届きました",
  dismiss: "通知を閉じる",
  unread: "未読",
  sentImmediately: "今すぐ送る",
  typeUnknown: "種類未定",
  phaseLabel: "区間",
  stateLabel: "いまの状態",
  systemUnknown: "システム未定",
  placedByAi: "（AIが文章から置きました）",
  placedByOperator: "（担当者が置き直しました）",
  slackOk: "Slack に投稿済み",
  slackFailed: "Slack への投稿に失敗しました",
  inquiryNotFound: "この問い合わせは見つかりませんでした",
  utterance: "元の発話",
  restatement: "言い直し",
  intake: "入口の質問と答え",
  unknownAnswer: "わからない",
  skippedAnswer: "書かずに進みました",
  inferredNote: "（文章から読み取り）",
  unconfirmed: "根拠を確かめられなかったこと",
  unconfirmedNote: "調査で挙がりましたが、資料で裏づけを取れていません。",
  revisionNotes: "作り直しで伝えられた一言",
  result: "調べた結果",
  noResult: "調べた結果はありません（調査の前に送られました）",
  offTopic: "問いに答えていないと判定（関連する内容のみ）",
  notGrounded: "一次情報から答えを特定できませんでした",
  findings: "分かったこと",
  notFound: "調べたが見つからなかったこと",
  nextChecks: "次に確認すること",
  citations: "出典",
  verified: "照合済み",
  unverified: "照合できず",
  loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
  phaseSwitchLabel: "区間を切り替える",
  phaseSwitchHint: "区間を切り替えられるのは、担当者の番のときだけです。",
  placementSummary: "置き直す",
  placementType: "種類",
  placementSystem: "システム",
  placementFunction: "機能",
  placementUnset: "未定",
  placementSubmit: "置き直す",
  sendLabel: "本人に送る",
  sendAsk: "聞き返す",
  sendResult: "結果を伝える",
  sendAskHint: "聞き返す: 本人の返事を待ちます",
  sendResultHint:
    "結果を伝える: 本人が解決したかを確かめます（問い合わせを終えるのは本人です）",
  declineSummary: "見送る",
  declineKind: "理由の種類",
  declineReason: "理由",
  declineSubmit: "見送りとして終える",
  historySummary: "状態の履歴",
  historyEmpty: "履歴はまだありません。",
  dropSend: "「本人に送る」の入力欄を開きました",
  dropDecline: "「見送る」の入力欄を開きました",
  exchange: "やり取り",
  noMessages: "やり取りはまだありません。",
  actionFailed:
    "うまく処理できませんでした。時間をおいてもう一度お試しください。",
} as const;

/** 担当者のボードの列の見出し。 */
export const OPERATOR_COLUMN_LABELS = {
  OPERATOR: "担当者の番",
  ASKER: "本人の番",
  CLOSED: "終わり",
} as const;

/** 読み上げ用に、カードを呼ぶときの言い直しの長さの上限。 */
const MOVED_HEAD_LENGTH = 20;

/**
 * 問い合わせが別の列に移ったことを伝える文。カードは言い直しの先頭で呼ぶ。
 * @param summary 言い直しの文章
 * @param column 移った先の列の見出し
 */
export function movedText(summary: string, column: string): string {
  const head =
    summary.length > MOVED_HEAD_LENGTH
      ? `${summary.slice(0, MOVED_HEAD_LENGTH)}…`
      : summary;
  return `「${head}」を${column}に移しました`;
}

/**
 * 送られた時刻を「月/日 時:分」で表す。
 * @param iso ISO 8601 の日時
 */
export function formatFiledAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("ja-JP", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
