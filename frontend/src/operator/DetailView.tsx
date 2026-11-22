import { useEffect, useRef, useState } from "react";
import type { PlacementInput, ReplyKind } from "../api/client.ts";
import type {
  ConfigSystem,
  DeclineReasonKind,
  DraftResult,
  OperatorDetail,
  Translation,
  Phase,
  SystemOption,
  TypeOption,
} from "../api/types.ts";
import { TranslationNote } from "../components/Exchange.tsx";
import { TracePanel } from "../components/TracePanel.tsx";
import { useMessages } from "../i18n/context.ts";
import { languageName } from "../i18n/messages.ts";
import {
  ConsultForm,
  ConsultationWaiting,
  DecisionsSection,
} from "./Consultation.tsx";
import { openConsultationOf } from "./consultations.ts";
import {
  DeclineForm,
  HistoryList,
  PhaseSwitch,
  PlacementForm,
  SendToAsker,
} from "./Controls.tsx";
import { formatMonthDay, stateLine, waitingText } from "./labels.ts";
import {
  ExchangeSection,
  MaterialSections,
  PlacementBadges,
} from "./Material.tsx";
import type { DropIntent } from "./state.ts";

/** カードをドラッグで落としたときの、入力欄へのフォーカスの要求。 */
export interface FocusRequest {
  /** 問い合わせの id */
  id: string;
  /** 「本人に送る」「相談する」「見送る」の入力欄 */
  target: DropIntent;
  /** 同じ要求を続けて受けても、もう一度フォーカスを移せるようにする通し番号 */
  token: number;
}

/** DetailView の props。 */
interface DetailViewProps {
  detail: OperatorDetail;
  /** 設定の緊急度の選択肢（緊急度の名前に使う。取得前は空） */
  urgencies: SystemOption[];
  /** 設定の種類（置き直しの選択肢。取得前は空） */
  types: TypeOption[];
  /** 設定のシステム（置き直しの選択肢。取得前は空） */
  systems: ConfigSystem[];
  /** スキルの実行記録を最下部に出すか（`GET /api/config` の `trace`） */
  trace: boolean;
  /** 操作を送っている最中か */
  busy: boolean;
  /** 直近の操作が失敗したか */
  failed: boolean;
  /** ドラッグで落とされたときの、入力欄へのフォーカスの要求 */
  focusRequest: FocusRequest | null;
  /** フォーカスを移し終えた（要求を消す） */
  onFocusHandled: () => void;
  /** 区間を切り替える。成功したら true */
  onPhase: (phase: Phase) => Promise<boolean>;
  /** 置き直す。成功したら true */
  onPlacement: (placement: PlacementInput) => Promise<boolean>;
  /** 本人に聞き返す、または結果を伝える。成功したら true */
  onReply: (kind: ReplyKind, body: string) => Promise<boolean>;
  /** AI の案の文章の言語（既定の言語。`GET /api/config` の `languages` の先頭） */
  adviceLanguage?: string;
  /** 担当者に添える訳（出している間だけ。無ければ null） */
  translation: Translation | null;
  /** 訳を取りにいっている最中か */
  translating: boolean;
  /** 訳を見る／隠す */
  onToggleTranslation: () => void;
  /** 返信の下書きを作らせる。失敗したら null */
  onDraft: (kind: ReplyKind, note: string) => Promise<DraftResult | null>;
  /** AI に調べさせる（調べ直す）。成功したら true */
  onInvestigate: () => Promise<boolean>;
  /** 見送りとして終える。成功したら true */
  onDecline: (kind: DeclineReasonKind, reason: string) => Promise<boolean>;
  /** 仕様の持ち主に相談する。成功したら true */
  onConsult: (to: string[], question: string) => Promise<boolean>;
  /** 相談を取り下げる。成功したら true */
  onCancelConsultation: () => Promise<boolean>;
}

/**
 * 1件の問い合わせの全内容。元の発話から調べた結果、出典の照合結果まで。
 * 上部にいまの状態と区間の切り替え、下部にやり取りと、本人に送る・見送る操作、状態の履歴、
 * 最下部にスキルの実行記録を置く。担当者の番でないときは、操作の代わりに待っていることを示す。
 * @param props 問い合わせの全内容と、操作の受け口
 */
export function DetailView({
  detail,
  urgencies,
  types,
  systems,
  trace,
  busy,
  failed,
  focusRequest,
  onFocusHandled,
  onPhase,
  onPlacement,
  onReply,
  adviceLanguage,
  translation,
  translating,
  onToggleTranslation,
  onDraft,
  onInvestigate,
  onDecline,
  onConsult,
  onCancelConsultation,
}: DetailViewProps) {
  const dict = useMessages();
  const OP_TEXT = dict.op;
  const { structured } = detail;
  /** 訳があれば、`key` の文章のすぐ下に添える（対応する文章が画面にない `key` は、呼ばれないので無視される） */
  const noteOf = (key: string) =>
    translation === null ? null : (
      <TranslationNote
        text={translation.items.find((item) => item.key === key)?.translated}
        language={translation.to}
      />
    );
  const ended = detail.ending !== null;
  const operatorsTurn = !ended && detail.turn === "OPERATOR";
  // 調べ直せるのは、終わっていない、言い直しがある、いま調べていない件
  const canInvestigate =
    !ended && structured !== null && detail.investigation.status !== "RUNNING";

  // ドラッグで落とされたら、対応する入力欄にフォーカスを移す（見送るは、折りたたみを開いてから）
  const sendRef = useRef<HTMLTextAreaElement | null>(null);
  const declineRef = useRef<HTMLTextAreaElement | null>(null);
  const consultRef = useRef<HTMLTextAreaElement | null>(null);
  const [declineOpen, setDeclineOpen] = useState(false);
  const [consultOpen, setConsultOpen] = useState(false);
  const target =
    focusRequest !== null && focusRequest.id === detail.id
      ? focusRequest.target
      : null;
  const token = focusRequest?.token;
  // 「見送る」「相談する」への要求が来たら、描画のうちに折りたたみを開く（要求ごとに1回）
  const [openedFor, setOpenedFor] = useState<number | undefined>(undefined);
  if (
    (target === "decline" || target === "consult") &&
    operatorsTurn &&
    token !== openedFor
  ) {
    setOpenedFor(token);
    if (target === "decline") setDeclineOpen(true);
    else setConsultOpen(true);
  }
  useEffect(() => {
    if (target === null) return;
    if (!operatorsTurn) {
      onFocusHandled();
      return;
    }
    if (target === "send") sendRef.current?.focus();
    else if (target === "consult") consultRef.current?.focus();
    else declineRef.current?.focus();
    onFocusHandled();
  }, [target, token, operatorsTurn, onFocusHandled]);
  return (
    <article className="op-article">
      <header className="op-article-head">
        <PlacementBadges detail={detail} urgencies={urgencies} />
        <p className="op-state" aria-live="polite">
          <span className="op-state-label">{OP_TEXT.stateLabel}</span>
          <strong>{stateLine(detail, dict)}</strong>
        </p>
        {operatorsTurn && detail.handoverReason !== null && (
          <p className="op-handover-reason">
            {OP_TEXT.handoverReasonLabel(
              OP_TEXT.handoverReasons[detail.handoverReason],
            )}
          </p>
        )}
        {detail.noReply !== null && (
          <p className="op-no-reply">
            {detail.noReply.remindedAt !== null &&
            detail.noReply.closesAt !== null
              ? (detail.noReply.endsAs === "WITHDRAWN"
                  ? OP_TEXT.noReplyRemindedWithdrawn
                  : OP_TEXT.noReplyReminded)(
                  formatMonthDay(detail.noReply.remindedAt, dict.meta.locale),
                  formatMonthDay(detail.noReply.closesAt, dict.meta.locale),
                )
              : OP_TEXT.noReplyPlanned(
                  formatMonthDay(detail.noReply.remindAt, dict.meta.locale),
                )}
          </p>
        )}
        {detail.language !== dict.meta.code && (
          <p className="op-asker-language">
            {OP_TEXT.askerLanguage(languageName(detail.language))}{" "}
            <button
              type="button"
              className="small"
              disabled={translating}
              onClick={onToggleTranslation}
            >
              {translation === null
                ? OP_TEXT.translationShow
                : OP_TEXT.translationHide}
            </button>
          </p>
        )}
        <PhaseSwitch
          phase={detail.phase}
          enabled={operatorsTurn}
          busy={busy}
          onChange={(phase) => void onPhase(phase)}
        />
        {!ended && (
          <PlacementForm
            key={`${detail.type?.id}:${detail.system?.id}:${detail.function?.id}`}
            detail={detail}
            types={types}
            systems={systems}
            busy={busy}
            onSubmit={onPlacement}
          />
        )}
      </header>

      <MaterialSections
        detail={detail}
        adviceLanguage={adviceLanguage}
        noteOf={noteOf}
        resultAction={
          canInvestigate ? (
            <div className="op-investigate">
              <button
                type="button"
                disabled={busy}
                onClick={() => void onInvestigate()}
              >
                {detail.investigation.status === "NOT_RUN"
                  ? OP_TEXT.investigationStart
                  : OP_TEXT.investigationRetry}
              </button>
              <p className="op-muted">{OP_TEXT.investigationHint}</p>
            </div>
          ) : null
        }
      />

      <DecisionsSection decisions={detail.decisions ?? []} />

      <ExchangeSection detail={detail} translation={translation}>
        {operatorsTurn ? (
          <>
            <SendToAsker
              busy={busy}
              inputRef={sendRef}
              onSend={onReply}
              onDraft={onDraft}
            />
            <ConsultForm
              open={consultOpen}
              onToggle={setConsultOpen}
              busy={busy}
              defaultConsultants={detail.defaultConsultants ?? []}
              questionRef={consultRef}
              onSubmit={onConsult}
            />
            <DeclineForm
              open={declineOpen}
              onToggle={setDeclineOpen}
              busy={busy}
              reasonRef={declineRef}
              onSubmit={onDecline}
            />
          </>
        ) : detail.ending === null && detail.awaiting === "CONSULTATION" ? (
          <ConsultationWaiting
            consultation={openConsultationOf(detail)}
            busy={busy}
            onCancel={onCancelConsultation}
          />
        ) : (
          <p className="op-waiting">{waitingText(detail, OP_TEXT)}</p>
        )}
        {failed && (
          <p className="op-muted" role="alert">
            {OP_TEXT.actionFailed}
          </p>
        )}
      </ExchangeSection>

      <HistoryList history={detail.history} />

      {trace && <TracePanel entries={detail.trace} operator />}
    </article>
  );
}
