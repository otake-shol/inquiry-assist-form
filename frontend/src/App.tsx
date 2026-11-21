import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";
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
import { FollowUp } from "./components/Exchange.tsx";
import { InquiryListPage } from "./components/InquiryListPage.tsx";
import { MockBar } from "./components/MockBar.tsx";
import { DevLink, UserHeader } from "./components/Navigation.tsx";
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
import { createInitialState, flowReducer, systemIdOf } from "./flow/reducer.ts";
import { useInquiryList } from "./flow/useInquiryList.ts";
import { navigateTo, usePathname } from "./location.ts";
import {
  NEW_INQUIRY_PATH,
  inquiryPathOf,
  parseRoute,
  type Route,
} from "./path.ts";
import {
  canSendNow,
  pageSections,
  readTextParam,
  sectionKey,
  type Section,
} from "./flow/page.ts";
import { TracePanel } from "./components/TracePanel.tsx";
import { USER_TEXT } from "./text/inquiries.ts";
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
 * 本人向けの画面全体。URL が `/` なら新しい問い合わせ、`/inquiries` なら送った問い合わせの一覧、
 * `/inquiries/{id}` ならその問い合わせを開く（途中なら続きから再開する）。
 * 問い合わせの画面は1枚のページが会話のように下へ伸び、済んだやり取りは要約として上に残る。
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
    skills,
    answers,
    categoryOpen,
    streamAttempt,
    filedGuidance,
    savedInquiryId,
    systems,
    systemChoice,
    userName,
    trace,
    resume,
  } = state;
  const viewId = view?.id;
  const step = view?.step;

  const pathname = usePathname();
  const route: Route = useMemo(() => parseRoute(pathname), [pathname]);
  const routeKey =
    route.kind === "inquiry" ? `inquiry:${route.id}` : route.kind;
  const inquiryList = useInquiryList(
    `${routeKey}|${viewId ?? ""}|${view?.messages?.length ?? 0}`,
  );

  useEffect(() => {
    // 取得に失敗したら非モック扱いで続行する
    api
      .getConfig()
      .then((config) =>
        dispatch({
          type: "configLoaded",
          mock: config.mock,
          skills: config.skills ?? null,
          filedGuidance: config.filedGuidance ?? null,
          systems: config.systems ?? [],
          userName: config.user?.name ?? null,
          trace: config.trace ?? false,
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
      const received = await operation();
      dispatch({ type: "viewReceived", view: received });
      return received;
    } catch (e) {
      const failure =
        e instanceof api.ApiError
          ? { code: e.code, message: e.message, inquiryId: e.inquiryId }
          : { code: "UNKNOWN", message: String(e) };
      dispatch({ type: "requestFailed", error: failure });
      return null;
    }
  }, []);

  // URL に合わせて問い合わせを開く。新しい問い合わせや一覧に移ったら状態を捨て、
  // `/inquiries/{id}` を開いたら（作成直後で、すでに持っている場合を除き）取得して再開する。
  const latest = useRef({ view, resume });
  useEffect(() => {
    latest.current = { view, resume };
  });
  useEffect(() => {
    const { view: current, resume: resuming } = latest.current;
    if (route.kind !== "inquiry") {
      if (current !== null || resuming !== "idle") dispatch({ type: "reset" });
      return;
    }
    const id = route.id;
    if (id === null) {
      dispatch({ type: "resumeNotFound" });
      return;
    }
    if (current?.id === id) return;
    let cancelled = false;
    dispatch({ type: "resumeStarted" });
    api
      .getInquiry(id)
      .then((resumed) => {
        if (!cancelled) dispatch({ type: "inquiryResumed", view: resumed });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // 形式の崩れた id には API が 400 VALIDATION を返す。無い問い合わせと同じく「見つからない」と示す
        if (
          e instanceof api.ApiError &&
          (e.code === "NOT_FOUND" || e.code === "VALIDATION")
        ) {
          dispatch({ type: "resumeNotFound" });
        } else {
          dispatch({
            type: "resumeFailed",
            error:
              e instanceof api.ApiError
                ? { code: e.code, message: e.message }
                : { code: "UNKNOWN", message: String(e) },
          });
        }
      });
    return () => {
      cancelled = true;
    };
    // 開くページが変わったときだけ動かす（view と resume は ref から読む）
  }, [route]);

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
    const key = `${view.id}:${view.category}:${view.system?.id ?? ""}`;
    if (autoSubmitted.current === key) return;
    autoSubmitted.current = key;
    void run(() => api.submitAnswers(view.id, []));
  }, [view, busy, error, run]);

  // 問い合わせを開いている間（読み込み中、見つからない）は、入力欄を出さない
  const sections =
    route.kind === "new" || route.kind === "operator" || view !== null
      ? pageSections(view, answers.length)
      : [];
  const lastKey =
    sections.length === 0 ? "" : sectionKey(sections[sections.length - 1]);
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

  /** やり取りの段があるか（ある場合、別件の入口は終わりの文言ではなく、やり取りの最後に出す） */
  const hasFollowUp = sections.some((section) => section.kind === "followup");

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
            systems={systems}
            systemChoice={systemChoice}
            onChange={(value) =>
              dispatch({ type: "draftChanged", draft: value })
            }
            onChooseSystem={(choice) =>
              dispatch({ type: "systemChosen", choice })
            }
            onSubmit={() =>
              void run(() =>
                api.createInquiry(draft.trim(), systemIdOf(systemChoice)),
              ).then((created) => {
                // 作成したら URL を `/inquiries/{id}` に変える（開き直しても続きから再開できる）
                if (created !== null) navigateTo(inquiryPathOf(created.id));
              })
            }
          />
        );
      case "utterance":
        return <UtteranceRow text={view!.utterance} />;
      case "receipt":
        return (
          <Receipt
            category={view!.category}
            system={view!.system}
            systemGuessed={view!.systemGuessed}
            systems={systems}
            canChange={view!.step === "TRIAGED" && view!.category !== null}
            open={categoryOpen}
            busy={busy}
            onToggle={() => dispatch({ type: "categoryToggled" })}
            onSelect={(category) =>
              category === view!.category
                ? dispatch({ type: "categoryToggled" })
                : void run(() => api.changeCategory(view!.id, category))
            }
            onSelectSystem={(systemId) =>
              systemId === (view!.system?.id ?? null) &&
              !(view!.systemGuessed && systemId !== null)
                ? dispatch({ type: "categoryToggled" })
                : void run(() => api.changeSystem(view!.id, systemId))
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
            onRestart={restart}
            showRestart={!hasFollowUp}
          />
        );
      case "followup":
        return (
          <FollowUp
            status={view!.handlingStatus}
            messages={view!.messages ?? []}
            busy={busy}
            onSend={async (body) =>
              (await run(() => api.postMessage(view!.id, body))) !== null
            }
            onRestart={restart}
          />
        );
    }
  };

  /** 新しい問い合わせを書く。状態を捨てて `/` に移る。 */
  const restart = () => {
    dispatch({ type: "reset" });
    navigateTo(NEW_INQUIRY_PATH);
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
    <div className={`app ${route.kind === "inquiries" ? "wide" : ""}`}>
      <UserHeader
        route={route}
        userName={userName}
        unreadReplyCount={inquiryList.list?.unreadReplyCount ?? 0}
      />
      {route.kind === "inquiries" ? (
        <main>
          <InquiryListPage
            list={inquiryList.list}
            failed={inquiryList.failed}
            onNew={restart}
          />
        </main>
      ) : (
        <>
          {mock && (
            <MockBar
              skills={skills}
              canPick={view === null && !busy}
              onPick={(text) => dispatch({ type: "draftChanged", draft: text })}
            />
          )}
          <main className="conversation">
            {resume === "loading" && (
              <p className="muted" role="status">
                {USER_TEXT.loading}
              </p>
            )}
            {resume === "notFound" && (
              <div className="system">
                <p role="alert">{USER_TEXT.notFound}</p>
                <div className="actions">
                  <button type="button" className="primary" onClick={restart}>
                    {TEXT.restart}
                  </button>
                </div>
              </div>
            )}
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
          {trace && view !== null && <TracePanel entries={view.trace} />}
        </>
      )}
      {(mock || trace) && (
        <DevLink to="/operator">{USER_TEXT.devToOperator}</DevLink>
      )}
    </div>
  );
}
