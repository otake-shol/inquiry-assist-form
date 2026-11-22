import type { InquiryView } from "../api/types.ts";
import type { Messages } from "../i18n/messages.ts";

/**
 * 画面に出す文言は、言語ごとの辞書（`i18n/`）に集める。言葉づかいの仕様は docs/ux-principles.md。
 * このファイルには、辞書から文言を選ぶ純粋関数を置く。
 */

/** 担当者に渡した直後の案内。 */
export interface HandoverText {
  lead: string;
  /** 送った後の案内（API の filedGuidance）。 */
  guidance: string | null;
}

/**
 * 担当者に渡したことを伝える案内を決める。渡し方（業務が止まっている、答えを待たずに送った、
 * 調べた結果を添えた、入力を添えた、AI が引き継いだ）で、添えた内容の言い方が変わる。
 * @param view 担当者に渡したことのある問い合わせ
 * @param filedGuidance 担当者に送った後の案内文（API の設定）
 * @param handover 辞書の、渡した直後の案内（`messages.asker.handover`）
 */
export function handoverText(
  view: InquiryView,
  filedGuidance: string | null,
  handover: Messages["asker"]["handover"],
): HandoverText {
  // AI が引き継いだ件は、引き継いだことと理由を別の文で伝える（Exchange の handoverNote）。
  // 同じことを2度言わないよう、ここでは添えた内容だけを伝える
  const byAi =
    view.handoverReason === "NO_ANSWER" || view.handoverReason === "TIMEOUT";
  const lead = byAi
    ? view.answer !== null
      ? handover.attachedWithAnswer
      : handover.attachedPlain
    : view.urgency === "BLOCKED" && view.sentImmediately
      ? handover.blocked
      : view.sentImmediately
        ? handover.immediate
        : view.answer !== null
          ? handover.withAnswer
          : handover.plain;
  return { lead, guidance: filedGuidance };
}
