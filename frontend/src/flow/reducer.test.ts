import { describe, expect, it } from "vitest";
import { makeView } from "../test/fakes.ts";
import {
  answersFromSubmitted,
  createInitialState,
  flowReducer,
  type FlowState,
} from "./reducer.ts";

const initialState = createInitialState();

describe("flowReducer", () => {
  it("要求の開始で busy になりエラーが消え、失敗で busy が戻る", () => {
    const withError: FlowState = {
      ...initialState,
      error: { code: "X", message: "x", source: "action" },
    };
    const started = flowReducer(withError, { type: "requestStarted" });
    expect(started).toMatchObject({ busy: true, error: null });

    const failed = flowReducer(started, {
      type: "requestFailed",
      error: { code: "AI_REFUSED", message: "m" },
    });
    expect(failed.busy).toBe(false);
    expect(failed.error).toEqual({
      code: "AI_REFUSED",
      message: "m",
      source: "action",
    });
  });

  it("ANSWERING 以外の view を受け取ると進捗が消える", () => {
    const streaming = flowReducer(
      { ...initialState, view: makeView({ step: "ANSWERING" }) },
      { type: "streamProgress", message: "検索中" },
    );
    expect(streaming.progress).toEqual(["検索中"]);
    const done = flowReducer(streaming, {
      type: "viewReceived",
      view: makeView({ step: "ANSWERED" }),
    });
    expect(done.progress).toEqual([]);
  });

  it("ストリーム失敗は source: stream で記録され、再試行で購読回数が増える", () => {
    const failed = flowReducer(initialState, {
      type: "streamFailed",
      error: { code: "AI_UNAVAILABLE", message: "m" },
    });
    expect(failed.error?.source).toBe("stream");
    const retried = flowReducer(failed, { type: "streamRetried" });
    expect(retried.error).toBeNull();
    expect(retried.streamAttempt).toBe(failed.streamAttempt + 1);
  });

  it("判定失敗で保存された id は送信失敗後も残り、view を受け取ると消える", () => {
    const saved = flowReducer(initialState, {
      type: "requestFailed",
      error: { code: "AI_UNAVAILABLE", message: "m", inquiryId: "saved-1" },
    });
    expect(saved.savedInquiryId).toBe("saved-1");
    const retryFailed = flowReducer(saved, {
      type: "requestFailed",
      error: { code: "INTERNAL", message: "m" },
    });
    expect(retryFailed.savedInquiryId).toBe("saved-1");
    const closed = flowReducer(retryFailed, {
      type: "viewReceived",
      view: makeView({ step: "CLOSED", outcome: "FILED" }),
    });
    expect(closed.savedInquiryId).toBeNull();
  });

  it("reset は問い合わせと入力を消すがモック設定は保つ", () => {
    const state: FlowState = {
      ...initialState,
      mock: true,
      draft: "書きかけ",
      view: makeView(),
    };
    expect(flowReducer(state, { type: "reset" })).toEqual({
      ...initialState,
      mock: true,
    });
  });

  it("回答は順に積まれ、答え直すと以降が消える", () => {
    let state = flowReducer(initialState, {
      type: "answerGiven",
      index: 0,
      answer: "詳細",
    });
    state = flowReducer(state, { type: "answerGiven", index: 1, answer: null });
    expect(state.answers).toEqual(["詳細", null]);
    expect(
      flowReducer(state, { type: "answersRewound", index: 1 }).answers,
    ).toEqual(["詳細"]);
    // 同じ位置に答え直すと、それより後ろは残らない
    expect(
      flowReducer(state, { type: "answerGiven", index: 0, answer: "一覧" })
        .answers,
    ).toEqual(["一覧"]);
  });

  it("追加質問の段階の view を受け取ると回答を最初から聞き直し、後の段階では保つ", () => {
    const answered: FlowState = {
      ...initialState,
      answers: ["詳細"],
      categoryOpen: true,
    };
    const reopened = flowReducer(answered, {
      type: "viewReceived",
      view: makeView({ step: "TRIAGED" }),
    });
    expect(reopened.answers).toEqual([]);
    expect(reopened.categoryOpen).toBe(false);
    const structured = flowReducer(answered, {
      type: "viewReceived",
      view: makeView({ step: "STRUCTURED" }),
    });
    expect(structured.answers).toEqual(["詳細"]);
  });

  it("入力欄の初期値を渡せる", () => {
    expect(createInitialState("掲載期限").draft).toBe("掲載期限");
  });

  it("開き直した問い合わせは、送信済みの回答から答えた質問を再現し、追加質問の途中（TRIAGED）は最初から聞く", () => {
    const questions = [
      { id: "q1", text: "どの画面", options: ["一覧"] },
      { id: "q2", text: "いつ", options: [] },
    ];
    const resumed = flowReducer(
      { ...initialState, resume: "loading" },
      {
        type: "inquiryResumed",
        view: makeView({
          step: "STRUCTURED",
          questions,
          submittedAnswers: [
            { questionId: "q1", answer: "一覧" },
            { questionId: "q2", answer: null },
          ],
        }),
      },
    );
    expect(resumed).toMatchObject({
      resume: "idle",
      answers: ["一覧", null],
      progress: [],
    });
    const triaged = flowReducer(initialState, {
      type: "inquiryResumed",
      view: makeView({
        step: "TRIAGED",
        questions,
        submittedAnswers: [{ questionId: "q1", answer: "一覧" }],
      }),
    });
    expect(triaged.answers).toEqual([]);
  });

  it("answersFromSubmitted は記録が無い・欠けている場合に、答えがある先頭からの連続ぶんだけ返す", () => {
    const questions = [
      { id: "q1", text: "a", options: [] },
      { id: "q2", text: "b", options: [] },
    ];
    expect(answersFromSubmitted(makeView({ questions }))).toEqual([]);
    expect(
      answersFromSubmitted(makeView({ questions, submittedAnswers: null })),
    ).toEqual([]);
    expect(
      answersFromSubmitted(
        makeView({
          questions,
          submittedAnswers: [{ questionId: "q2", answer: "x" }],
        }),
      ),
    ).toEqual([]);
  });

  it("見つからない・取得に失敗した場合は問い合わせの状態を捨て、設定は残す", () => {
    const configured: FlowState = {
      ...initialState,
      mock: true,
      userName: "開発用ユーザー",
      trace: true,
      view: makeView(),
      answers: ["a"],
    };
    const missing = flowReducer(configured, { type: "resumeNotFound" });
    expect(missing).toMatchObject({
      resume: "notFound",
      view: null,
      answers: [],
      mock: true,
      userName: "開発用ユーザー",
      trace: true,
    });
    const failed = flowReducer(configured, {
      type: "resumeFailed",
      error: { code: "NETWORK", message: "m" },
    });
    expect(failed).toMatchObject({
      resume: "idle",
      view: null,
      error: { code: "NETWORK", source: "action" },
      userName: "開発用ユーザー",
    });
  });

  it("config の新しい項目が無い応答でも名前なし・実行記録なしになる", () => {
    const loaded = flowReducer(initialState, {
      type: "configLoaded",
      mock: false,
      skills: null,
      filedGuidance: null,
      systems: [],
    });
    expect(loaded).toMatchObject({ userName: null, trace: false });
  });
});
