import type {
  HandlingStatus,
  IntakeAnswer,
  SystemOption,
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

/** 担当者の画面での、対応状況の呼び名。 */
export const HANDLING_LABELS: Record<HandlingStatus, string> = {
  OPEN: "未対応",
  IN_PROGRESS: "対応中",
  DONE: "完了",
};

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
  systemUnknown: "システム未定",
  placedByAi: "（AIが文章から置きました）",
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
  statusSwitchLabel: "対応状況を切り替える",
  exchange: "やり取り",
  noMessages: "やり取りはまだありません。",
  replyLabel: "本人に返信する",
  replySubmit: "返信を送る",
  moveFailed:
    "対応状況を変えられませんでした。元の列に戻しました。時間をおいてもう一度お試しください。",
  actionFailed:
    "うまく処理できませんでした。時間をおいてもう一度お試しください。",
} as const;

/** 読み上げ用に、カードを呼ぶときの言い直しの長さの上限。 */
const MOVED_HEAD_LENGTH = 20;

/**
 * 対応状況が変わったことを伝える文。カードは言い直しの先頭で呼ぶ。
 * @param summary 言い直しの文章
 * @param status 変わった後の対応状況
 */
export function movedText(summary: string, status: HandlingStatus): string {
  const head =
    summary.length > MOVED_HEAD_LENGTH
      ? `${summary.slice(0, MOVED_HEAD_LENGTH)}…`
      : summary;
  return `「${head}」を${HANDLING_LABELS[status]}に移しました`;
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
