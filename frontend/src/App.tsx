import { useCallback, useEffect, useReducer } from "react";
import * as api from "./api/client.ts";
import {
  subscribeAnswerStream,
  type SubscribeAnswerStream,
} from "./api/stream.ts";
import type { InquiryView } from "./api/types.ts";
import { AnsweredStep, AnsweringStep } from "./components/AnswerSteps.tsx";
import { ClosedStep } from "./components/ClosedStep.tsx";
import { ErrorNotice } from "./components/ErrorNotice.tsx";
import { InputStep } from "./components/InputStep.tsx";
import { MockBar } from "./components/MockBar.tsx";
import { Stepper } from "./components/Stepper.tsx";
import { StructureStep } from "./components/StructureStep.tsx";
import { TriageStep } from "./components/TriageStep.tsx";
import { flowReducer, initialState } from "./flow/reducer.ts";
import { canSendNow, progressIndexOf, screenOf } from "./flow/screen.ts";

/** App の props。 */
interface AppProps {
  /** 0次回答ストリームの購読関数。テストでは偽物を渡す。 */
  subscribe?: SubscribeAnswerStream;
}

/**
 * 問い合わせフォーム全体。状態は reducer に集約し、表示する画面は InquiryView から決める。
 * @param props 購読関数の差し替え口
 */
export default function App({ subscribe = subscribeAnswerStream }: AppProps) {
  const [state, dispatch] = useReducer(flowReducer, initialState);
  const { view, busy, error, progress, draft, mock, streamAttempt } = state;
  const screen = screenOf(view);
  const viewId = view?.id;

  useEffect(() => {
    // 取得に失敗したら非モック扱いで続行する
    api
      .getConfig()
      .then((config) => dispatch({ type: "configLoaded", mock: config.mock }))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (screen !== "ANSWERING" || viewId === undefined) return;
    let unsubscribe: (() => void) | null = null;
    // StrictMode の開発時二重実行で接続が二重にならないよう、購読開始を1タスク遅らせる
    const timer = setTimeout(() => {
      unsubscribe = subscribe(viewId, {
        onProgress: (message) => dispatch({ type: "streamProgress", message }),
        onDone: (done) => dispatch({ type: "viewReceived", view: done }),
        onFailed: (failure) =>
          dispatch({ type: "streamFailed", error: failure }),
      });
    }, 0);
    return () => {
      clearTimeout(timer);
      unsubscribe?.();
    };
  }, [screen, viewId, streamAttempt, subscribe]);

  /**
   * API 呼び出しを実行し、結果の InquiryView かエラーを状態に反映する。通信中は busy にする。
   * @param operation 実行する API 呼び出し
   */
  const run = useCallback(async (operation: () => Promise<InquiryView>) => {
    dispatch({ type: "requestStarted" });
    try {
      dispatch({ type: "viewReceived", view: await operation() });
    } catch (e) {
      const failure =
        e instanceof api.ApiError
          ? { code: e.code, message: e.message, inquiryId: e.inquiryId }
          : { code: "UNKNOWN", message: String(e) };
      dispatch({ type: "requestFailed", error: failure });
    }
  }, []);

  // 保存済みの問い合わせ（作成後、または判定失敗で保存された分）があれば担当者へ送れる
  const sendNowId =
    view !== null && canSendNow(view)
      ? view.id
      : (state.savedInquiryId ?? undefined);
  const streamError = error?.source === "stream" ? error : null;

  return (
    <div className="app">
      <header>
        <h1>問い合わせアシスト</h1>
      </header>
      {mock && (
        <MockBar
          canPick={screen === "INPUT" && !busy}
          onPick={(text) => dispatch({ type: "draftChanged", draft: text })}
        />
      )}
      <Stepper current={progressIndexOf(screen)} />
      <main>
        {error !== null && (
          <ErrorNotice
            error={error}
            hasInquiry={sendNowId !== undefined}
            busy={busy}
            onRetry={
              screen === "ANSWERING" && streamError !== null
                ? () => dispatch({ type: "streamRetried" })
                : undefined
            }
          />
        )}
        {screen === "INPUT" && (
          <InputStep
            draft={draft}
            busy={busy}
            onChange={(value) =>
              dispatch({ type: "draftChanged", draft: value })
            }
            onSubmit={() => void run(() => api.createInquiry(draft.trim()))}
          />
        )}
        {view !== null && screen === "TRIAGE" && (
          <TriageStep
            view={view}
            busy={busy}
            onChangeCategory={(category) =>
              void run(() => api.changeCategory(view.id, category))
            }
            onSubmit={(answers) =>
              void run(() => api.submitAnswers(view.id, answers))
            }
          />
        )}
        {view !== null &&
          view.structured !== null &&
          screen === "STRUCTURE" && (
            <StructureStep
              structured={view.structured}
              busy={busy}
              onConfirm={(structured) =>
                void run(() => api.confirmStructured(view.id, structured))
              }
            />
          )}
        {screen === "ANSWERING" && (
          <AnsweringStep progress={progress} stopped={streamError !== null} />
        )}
        {view !== null && view.answer !== null && screen === "ANSWERED" && (
          <AnsweredStep
            answer={view.answer}
            busy={busy}
            onResolve={(resolved) =>
              void run(() => api.resolve(view.id, resolved))
            }
          />
        )}
        {view !== null && screen === "CLOSED" && (
          <ClosedStep
            view={view}
            onRestart={() => dispatch({ type: "reset" })}
          />
        )}
      </main>
      {sendNowId !== undefined && (
        <footer className="send-now">
          <p>待たずに人に見てもらいたいときはこちら。</p>
          <button
            type="button"
            className="secondary"
            disabled={busy}
            onClick={() => void run(() => api.sendNow(sendNowId))}
          >
            今すぐ担当者に送る
          </button>
        </footer>
      )}
    </div>
  );
}
