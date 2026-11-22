import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";
import { ApiError } from "./api/client.ts";
import { useApi } from "./api/useApi.ts";
import {
  subscribeAnswerStream,
  type SubscribeAnswerStream,
} from "./api/stream.ts";
import type { InquiryView, IntakeAnswerRequest, Urgency } from "./api/types.ts";
import {
  EntryRow,
  QuestionPrompt,
  UrgencyChoice,
  UtteranceRow,
  WriteByText,
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
import { createInitialState, flowReducer } from "./flow/reducer.ts";
import { useInquiryList } from "./flow/useInquiryList.ts";
import { navigateTo, usePathname } from "./location.ts";
import {
  NEW_INQUIRY_PATH,
  inquiryPathOf,
  parseRoute,
  type Route,
} from "./path.ts";
import {
  canAppend,
  canWithdraw,
  composeStalled,
  firstQuestionOf,
  pageSections,
  readTextParam,
  resultFromOperator,
  sectionKey,
  showSendNowFooter,
  type Section,
} from "./flow/page.ts";
import { TracePanel } from "./components/TracePanel.tsx";
import { Withdraw } from "./components/Withdraw.tsx";
import { useI18n } from "./i18n/context.ts";
import { askerStatusLabel } from "./text/inquiries.ts";
import { handoverText } from "./text/labels.ts";

/** App の props。 */
interface AppProps {
  /** 0次回答ストリームの購読関数。テストでは偽物を渡す。 */
  subscribe?: SubscribeAnswerStream;
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
 * 何を並べるかは InquiryView から `pageSections` が決める。入口の質問は API が返す `intake.next` を描くだけで、
 * 問い合わせの作成は最初の答えを押した時点（または文章を送った時点）で行う。
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
    writing,
    draft,
    urgencyChoice,
    mock,
    skills,
    streamAttempt,
    filedGuidance,
    types,
    urgencies,
    userName,
    trace,
    resume,
  } = state;
  const viewId = view?.id;
  const awaiting = view?.awaiting;
  const api = useApi();
  const { language, messages, reportApiLanguages } = useI18n();
  const { text: TEXT, user: USER_TEXT } = messages;
  // 副作用が最新の値を読むための入れ物（言語の切り替えで副作用をやり直さないものを、ここから読む）
  const latest = useRef({ view, resume, api, language });

  const pathname = usePathname();
  const route: Route = useMemo(() => parseRoute(pathname), [pathname]);
  const routeKey =
    route.kind === "inquiry" ? `inquiry:${route.id}` : route.kind;
  const inquiryList = useInquiryList(
    `${routeKey}|${viewId ?? ""}|${view?.messages?.length ?? 0}`,
  );

  // タブの題名を、いまの言語に合わせる
  useEffect(() => {
    document.title = TEXT.appTitle;
  }, [TEXT.appTitle]);

  // 設定は、言語を切り替えると取り直す（種類やシステムの名前は API が返すため）
  useEffect(() => {
    // 取得に失敗したら非モック扱いで続行する
    api
      .getConfig()
      .then((config) => {
        reportApiLanguages(config.languages ?? null);
        dispatch({
          type: "configLoaded",
          mock: config.mock,
          skills: config.skills ?? null,
          filedGuidance: config.filedGuidance ?? null,
          types: config.types ?? [],
          urgencies: config.urgencies ?? [],
          userName: config.user?.name ?? null,
          trace: config.trace ?? false,
        });
      })
      .catch(() => {});
  }, [api, reportApiLanguages]);

  useEffect(() => {
    if (awaiting !== "INVESTIGATION" || viewId === undefined) return;
    let unsubscribe: (() => void) | null = null;
    // StrictMode の開発時二重実行で接続が二重にならないよう、購読開始を1タスク遅らせる
    const timer = setTimeout(() => {
      unsubscribe = subscribe(
        viewId,
        {
          onProgress: (message) =>
            dispatch({ type: "streamProgress", message }),
          onDone: (done) => dispatch({ type: "viewReceived", view: done }),
          onFailed: (failure) =>
            dispatch({ type: "streamFailed", error: failure }),
        },
        latest.current.language,
      );
    }, 0);
    return () => {
      clearTimeout(timer);
      unsubscribe?.();
    };
  }, [awaiting, viewId, streamAttempt, subscribe]);

  /**
   * API 呼び出しを実行し、結果の InquiryView かエラーを状態に反映する。通信中は busy にする。
   * 問いの組み立てに失敗したときは、エラーに答えを保存した問い合わせの id が付く（応答に問い合わせの
   * 表現は無い）。その id で問い合わせを取り直して、止まっている状態（`canSendNow` など）を画面に反映する。
   * @param operation 実行する API 呼び出し
   */
  const run = useCallback(
    async (operation: () => Promise<InquiryView>) => {
      dispatch({ type: "requestStarted" });
      try {
        const received = await operation();
        dispatch({ type: "viewReceived", view: received });
        return received;
      } catch (e) {
        const failure =
          e instanceof ApiError
            ? { code: e.code, message: e.message, inquiryId: e.inquiryId }
            : { code: "UNKNOWN", message: String(e) };
        dispatch({ type: "requestFailed", error: failure });
        if (failure.inquiryId !== undefined) {
          api
            .getInquiry(failure.inquiryId)
            .then((saved) => {
              dispatch({ type: "viewRefreshed", view: saved });
              navigateTo(inquiryPathOf(saved.id));
            })
            .catch(() => {});
        }
        return null;
      }
    },
    [api],
  );

  // URL に合わせて問い合わせを開く。新しい問い合わせや一覧に移ったら状態を捨て、
  // `/inquiries/{id}` を開いたら（作成直後で、すでに持っている場合を除き）取得して再開する。
  useEffect(() => {
    latest.current = { view, resume, api, language };
  });
  // 言語を切り替えたら、表示中の問い合わせを取り直す（質問や選択肢の名前を、新しい言語にするため）。
  // 入力の途中の文章（draft、入力欄の中身）は、この取り直しでは消えない
  const previousApi = useRef(api);
  useEffect(() => {
    if (previousApi.current === api) return;
    previousApi.current = api;
    const current = latest.current.view;
    if (current === null) return;
    api
      .getInquiry(current.id)
      .then((refreshed) => dispatch({ type: "viewRefreshed", view: refreshed }))
      .catch(() => {});
  }, [api]);

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
    latest.current.api
      .getInquiry(id)
      .then((resumed) => {
        if (!cancelled) dispatch({ type: "inquiryResumed", view: resumed });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // 形式の崩れた id には API が 400 VALIDATION を返す。無い問い合わせと同じく「見つからない」と示す
        if (
          e instanceof ApiError &&
          (e.code === "NOT_FOUND" || e.code === "VALIDATION")
        ) {
          dispatch({ type: "resumeNotFound" });
        } else {
          dispatch({
            type: "resumeFailed",
            error:
              e instanceof ApiError
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

  // 問い合わせを開いている間（読み込み中、見つからない）は、最初の質問を出さない
  const sections =
    route.kind === "new" || route.kind === "operator" || view !== null
      ? pageSections(view)
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
  const typeHints = useMemo(
    () => Object.fromEntries(types.map((type) => [type.id, type.hint])),
    [types],
  );

  /**
   * 問い合わせを作り、続けて最初の答えを送る。作成は最初の答えを押した時点で行い、
   * 返ってきた `intake.next`（最初の質問と同じ）の質問に答える。作成したら URL を
   * `/inquiries/{id}` に変える（開き直しても続きから再開できる）。
   * @param answer 1問目への答え
   */
  const startWithAnswer = async (answer: IntakeAnswerRequest) => {
    const created = await run(() =>
      api.createInquiry(
        urgencyChoice === "NORMAL" ? {} : { urgency: urgencyChoice },
      ),
    );
    if (created === null) return;
    navigateTo(inquiryPathOf(created.id));
    const first = created.intake.next;
    if (first === null) return;
    await run(() => api.answerIntake(created.id, first.questionId, answer));
  };

  /**
   * 入口の質問に答える。問い合わせの作成前（画面が描いた1問目）なら、作成してから答えを送る。
   * @param questionId 答える質問の id
   * @param answer 答え方
   */
  const answerQuestion = (questionId: string, answer: IntakeAnswerRequest) => {
    if (view === null) {
      void startWithAnswer(answer);
    } else {
      void run(() => api.answerIntake(view.id, questionId, answer));
    }
  };

  /**
   * 「文章で書く」の文章を送る。作成前は文章つきで作成し、作成後は文章で書く道に切り替える。
   */
  const submitText = async () => {
    const text = draft.trim();
    if (text === "") return;
    const received =
      view === null
        ? await run(() =>
            api.createInquiry(
              urgencyChoice === "NORMAL"
                ? { text }
                : { text, urgency: urgencyChoice },
            ),
          )
        : await run(() => api.submitIntakeText(view.id, text));
    if (received === null) return;
    dispatch({ type: "textSent" });
    if (view === null) navigateTo(inquiryPathOf(received.id));
  };

  /**
   * 緊急度を選ぶ。作成前は選んだ値を持っておき（作成のリクエストに付ける）、作成後は変更を送る。
   * @param urgency 選んだ緊急度
   */
  const chooseUrgency = (urgency: Urgency) => {
    if (view === null) {
      dispatch({ type: "urgencyChosen", urgency });
    } else {
      void run(() => api.changeUrgency(view.id, urgency));
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
      case "utterance":
        return (
          <UtteranceRow text={view!.utterance!} language={view!.language} />
        );
      case "entry":
        return (
          <EntryRow
            entry={view!.intake.entries[section.index]}
            interactive={section.interactive}
            language={view!.language}
            busy={busy}
            onBack={(questionId) =>
              void run(() => api.backIntake(view!.id, questionId))
            }
          />
        );
      case "question": {
        // 作成前は、設定の種類から描いた1問目。作成後は API が返す次の質問
        const question =
          view === null
            ? firstQuestionOf(types, TEXT.firstQuestion)
            : view.intake.next!;
        return (
          <>
            {question.options.length > 0 || view !== null ? (
              <QuestionPrompt
                question={question}
                busy={busy}
                typeHints={typeHints}
                onAnswer={(answer) =>
                  answerQuestion(question.questionId, answer)
                }
              />
            ) : null}
            <UrgencyChoice
              options={urgencies}
              value={view?.urgency ?? urgencyChoice}
              busy={busy}
              onChange={chooseUrgency}
            />
            {question.kind !== "NOTE" && (
              <WriteByText
                open={writing}
                draft={draft}
                busy={busy}
                onOpen={() => dispatch({ type: "writingOpened" })}
                onChange={(value) =>
                  dispatch({ type: "draftChanged", draft: value })
                }
                onSubmit={() => void submitText()}
              />
            )}
          </>
        );
      }
      case "restatement":
        return section.confirmed ? (
          <ConfirmedRestatement
            summary={view!.structured!.summary}
            language={view!.language}
          />
        ) : (
          <Restatement
            summary={view!.structured!.summary}
            language={view!.language}
            revisionsLeft={view!.revisionsLeft}
            busy={busy}
            canGoBack={view!.intake.entries.length > 0}
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
        return <FiledNotice text={section.text} />;
      case "result":
        return (
          <Result
            answer={view!.answer!}
            awaitingResolution={
              view!.awaiting === "RESULT_CONFIRMATION" &&
              !resultFromOperator(view!)
            }
            doForMe={view!.type?.id === "DO_FOR_ME"}
            language={view!.language}
            noReply={view!.noReply}
            busy={busy}
            onResolve={(resolved) =>
              void run(() => api.resolve(view!.id, resolved))
            }
          />
        );
      case "closing":
        return (
          <Closing
            ending={view!.ending!}
            declined={view!.declined}
            closedBy={view!.closedBy}
            onRestart={restart}
            showRestart={!hasFollowUp}
          />
        );
      case "followup":
        return (
          <FollowUp
            statusLabel={askerStatusLabel(
              view!,
              resultFromOperator(view!),
              messages.asker,
            )}
            messages={view!.messages ?? []}
            awaiting={view!.awaiting}
            canAppend={canAppend(view!)}
            ended={view!.ending !== null}
            handover={
              view!.handedOverAt === null
                ? null
                : handoverText(view!, filedGuidance, messages.asker.handover)
            }
            language={view!.language}
            noReply={view!.noReply}
            handoverNote={
              view!.awaiting === "HANDLING" && view!.handoverReason !== null
                ? (messages.asker.handoverReasonNote[view!.handoverReason] ??
                  null)
                : null
            }
            busy={busy}
            onSend={async (body) =>
              (await run(() => api.postMessage(view!.id, body))) !== null
            }
            onResolve={(resolved, note) =>
              void run(() => api.resolve(view!.id, resolved, note))
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
  let sendNowInError: (() => void) | undefined;
  if (awaiting === "INVESTIGATION" && streamError !== null) {
    retry = () => dispatch({ type: "streamRetried" });
    retryLabel = TEXT.retryResearch;
  } else if (
    view !== null &&
    composeStalled(view) &&
    error !== null &&
    error.source === "action"
  ) {
    // 入口の最後の答えのあとに問いを組み立てられなかった。答えは保存済みで、組み立てだけをやり直せる
    retry = () => void run(() => api.composeIntake(view.id));
    retryLabel = TEXT.retryCompose;
    if (view.canSendNow) {
      sendNowInError = () => void run(() => api.sendNow(view.id));
    }
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
                  hasInquiry={showSendNowFooter(view)}
                  busy={busy}
                  onRetry={retry}
                  retryLabel={retryLabel}
                  onSendNow={sendNowInError}
                />
              </div>
            )}
          </main>
          {view !== null && showSendNowFooter(view) && (
            <footer className="send-now">
              <p>{TEXT.sendNowHint}</p>
              <button
                type="button"
                disabled={busy}
                onClick={() => void run(() => api.sendNow(view.id))}
              >
                {TEXT.sendNow}
              </button>
            </footer>
          )}
          {view !== null && canWithdraw(view) && (
            <Withdraw
              busy={busy}
              onWithdraw={() => void run(() => api.withdraw(view.id))}
            />
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
