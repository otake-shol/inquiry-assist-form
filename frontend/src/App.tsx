import { useCallback, useEffect, useReducer, useRef } from "react";
import * as api from "./api/client.ts";
import {
  subscribeAnswerStream,
  type SubscribeAnswerStream,
} from "./api/stream.ts";
import type { AnswerInput, InquiryView, Question } from "./api/types.ts";
import {
  AnsweredRow,
  InputBox,
  QuestionPrompt,
  QuestionsIntro,
  Receipt,
  UtteranceRow,
} from "./components/Conversation.tsx";
import { ErrorNotice } from "./components/ErrorNotice.tsx";
import { MockBar } from "./components/MockBar.tsx";
import {
  Closing,
  FiledNotice,
  Researching,
  Result,
} from "./components/Outcome.tsx";
import {
  ConfirmedRestatement,
  Restatement,
} from "./components/Restatement.tsx";
import { createInitialState, flowReducer } from "./flow/reducer.ts";
import {
  canSendNow,
  pageSections,
  readTextParam,
  sectionKey,
  type Section,
} from "./flow/page.ts";
import { TEXT } from "./text/labels.ts";

/** App の props。 */
interface AppProps {
  /** 0次回答ストリームの購読関数。テストでは偽物を渡す。 */
  subscribe?: SubscribeAnswerStream;
}

/**
 * 質問と回答を API の回答の形に並べる。
 * @param questions 追加質問
 * @param answers 回答（質問と同じ順）
 */
function toAnswerInputs(
  questions: Question[],
  answers: (string | null)[],
): AnswerInput[] {
  return questions.map((q, i) => ({ questionId: q.id, answer: answers[i] }));
}

/** 動きを減らす設定か。 */
function prefersReducedMotion(): boolean {
  return (
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false
  );
}

/**
 * 問い合わせフォーム全体。1枚のページが会話のように下へ伸び、済んだやり取りは要約として上に残る。
 * 何を並べるかは InquiryView と回答済みの数から `pageSections` が決める。
 * @param props 購読関数の差し替え口
 */
export default function App({ subscribe = subscribeAnswerStream }: AppProps) {
  const [state, dispatch] = useReducer(flowReducer, undefined, () =>
    createInitialState(readTextParam(window.location.search)),
  );
  const {
    view,
    busy,
    error,
    progress,
    draft,
    mock,
    answers,
    categoryOpen,
    streamAttempt,
    filedGuidance,
    savedInquiryId,
  } = state;
  const viewId = view?.id;
  const step = view?.step;

  useEffect(() => {
    // 取得に失敗したら非モック扱いで続行する
    api
      .getConfig()
      .then((config) =>
        dispatch({
          type: "configLoaded",
          mock: config.mock,
          filedGuidance: config.filedGuidance ?? null,
        }),
      )
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (step !== "ANSWERING" || viewId === undefined) return;
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
  }, [step, viewId, streamAttempt, subscribe]);

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

  // 質問が0個なら何も出さずに先へ進む。失敗したときは自動で繰り返さない。
  const autoSubmitted = useRef<string | null>(null);
  useEffect(() => {
    if (
      view === null ||
      view.step !== "TRIAGED" ||
      view.category === null ||
      view.questions.length > 0 ||
      busy ||
      error !== null
    ) {
      return;
    }
    const key = `${view.id}:${view.category}`;
    if (autoSubmitted.current === key) return;
    autoSubmitted.current = key;
    void run(() => api.submitAnswers(view.id, []));
  }, [view, busy, error, run]);

  const sections = pageSections(view, answers.length);
  const lastKey = sectionKey(sections[sections.length - 1]);
  const lastRef = useRef<HTMLDivElement | null>(null);

  // 新しい部分が現れたら、そこまで滑らかにスクロールし、フォーカスを移す（読み上げにも伝わる）
  useEffect(() => {
    const element = lastRef.current;
    if (element === null) return;
    element.scrollIntoView?.({
      behavior: prefersReducedMotion() ? "auto" : "smooth",
      block: "nearest",
    });
    element
      .querySelector<HTMLElement>("[data-autofocus]")
      ?.focus({ preventScroll: true });
  }, [lastKey]);

  const errorRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (error === null) return;
    errorRef.current?.scrollIntoView?.({
      behavior: prefersReducedMotion() ? "auto" : "smooth",
      block: "nearest",
    });
  }, [error]);

  const streamError = error?.source === "stream" ? error : null;
  const sendNowId =
    view !== null && canSendNow(view) ? view.id : savedInquiryId;
  const allAnswered =
    view !== null &&
    view.step === "TRIAGED" &&
    view.category !== null &&
    answers.length === view.questions.length;

  /**
   * 追加質問に答える。最後の質問に答えたら自動で送信する。
   * @param current 質問の持ち主の問い合わせ
   * @param index 答えた質問の位置
   * @param answer 回答（「わからない」は null）
   */
  const answerQuestion = (
    current: InquiryView,
    index: number,
    answer: string | null,
  ) => {
    const next = [...answers.slice(0, index), answer];
    dispatch({ type: "answerGiven", index, answer });
    if (next.length === current.questions.length) {
      void run(() =>
        api.submitAnswers(current.id, toAnswerInputs(current.questions, next)),
      );
    }
  };

  /**
   * ページの1部分を描く。
   * @param section 描く部分
   */
  const renderSection = (section: Section) => {
    switch (section.kind) {
      case "input":
        return (
          <InputBox
            draft={draft}
            busy={busy}
            onChange={(value) =>
              dispatch({ type: "draftChanged", draft: value })
            }
            onSubmit={() => void run(() => api.createInquiry(draft.trim()))}
          />
        );
      case "utterance":
        return <UtteranceRow text={view!.utterance} />;
      case "receipt":
        return (
          <Receipt
            category={view!.category}
            canChange={view!.step === "TRIAGED" && view!.category !== null}
            open={categoryOpen}
            busy={busy}
            onToggle={() => dispatch({ type: "categoryToggled" })}
            onSelect={(category) =>
              category === view!.category
                ? dispatch({ type: "categoryToggled" })
                : void run(() => api.changeCategory(view!.id, category))
            }
          />
        );
      case "questionsIntro":
        return <QuestionsIntro count={section.count} />;
      case "answered":
        return (
          <AnsweredRow
            question={view!.questions[section.index]}
            answer={answers[section.index]}
            interactive={section.interactive}
            busy={busy}
            onRewind={() =>
              dispatch({ type: "answersRewound", index: section.index })
            }
          />
        );
      case "question":
        return (
          <QuestionPrompt
            question={view!.questions[section.index]}
            busy={busy}
            onAnswer={(answer) => answerQuestion(view!, section.index, answer)}
          />
        );
      case "restatement":
        return section.confirmed ? (
          <ConfirmedRestatement summary={view!.structured!.summary} />
        ) : (
          <Restatement
            summary={view!.structured!.summary}
            revisionsLeft={view!.revisionsLeft}
            busy={busy}
            canGoBack={view!.questions.length > 0}
            onConfirm={() => void run(() => api.confirmStructured(view!.id))}
            onRevise={(note) =>
              void run(() => api.reviseStructured(view!.id, note))
            }
            onRewrite={(summary) =>
              void run(() => api.confirmStructured(view!.id, summary))
            }
            onGoBack={() => void run(() => api.reopenQuestions(view!.id))}
            onSendAsIs={() => void run(() => api.sendNow(view!.id))}
          />
        );
      case "researching":
        return (
          <Researching progress={progress} stopped={streamError !== null} />
        );
      case "filedNotice":
        return <FiledNotice />;
      case "result":
        return (
          <Result
            answer={view!.answer!}
            awaitingResolution={view!.step === "ANSWERED"}
            busy={busy}
            onResolve={(resolved) =>
              void run(() => api.resolve(view!.id, resolved))
            }
          />
        );
      case "closing":
        return (
          <Closing
            view={view!}
            filedGuidance={filedGuidance}
            onRestart={() => dispatch({ type: "reset" })}
          />
        );
    }
  };

  let retry: (() => void) | undefined;
  let retryLabel: string | undefined;
  if (step === "ANSWERING" && streamError !== null) {
    retry = () => dispatch({ type: "streamRetried" });
    retryLabel = TEXT.retryResearch;
  } else if (allAnswered && error !== null && error.source === "action") {
    retry = () =>
      void run(() =>
        api.submitAnswers(view!.id, toAnswerInputs(view!.questions, answers)),
      );
    retryLabel = TEXT.resendAnswers;
  }

  return (
    <div className="app">
      <header>
        <h1>{TEXT.appTitle}</h1>
      </header>
      {mock && (
        <MockBar
          canPick={view === null && !busy}
          onPick={(text) => dispatch({ type: "draftChanged", draft: text })}
        />
      )}
      <main className="conversation">
        {sections.map((section, index) => {
          const key = sectionKey(section);
          return (
            <div
              key={key}
              className="section"
              ref={index === sections.length - 1 ? lastRef : undefined}
            >
              {renderSection(section)}
            </div>
          );
        })}
        <div aria-live="polite" className="working">
          {busy ? TEXT.working : ""}
        </div>
        {error !== null && (
          <div ref={errorRef}>
            <ErrorNotice
              error={error}
              hasInquiry={sendNowId !== null}
              busy={busy}
              onRetry={retry}
              retryLabel={retryLabel}
            />
          </div>
        )}
      </main>
      {sendNowId !== null && (
        <footer className="send-now">
          <p>{TEXT.sendNowHint}</p>
          <button
            type="button"
            disabled={busy}
            onClick={() => void run(() => api.sendNow(sendNowId))}
          >
            {TEXT.sendNow}
          </button>
        </footer>
      )}
    </div>
  );
}
