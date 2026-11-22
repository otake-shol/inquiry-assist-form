import { describe, expect, it } from "vitest";
import { makeView } from "../test/fakes.ts";
import { createInitialState, flowReducer, type FlowState } from "./reducer.ts";

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

  it("文章で書く入力欄は、開く・例文や入力で開いたまま入る・送ると閉じて空になる", () => {
    const opened = flowReducer(initialState, { type: "writingOpened" });
    expect(opened.writing).toBe(true);
    // 例文を選んだ場合など、入力欄が閉じていても開いて入れる
    const drafted = flowReducer(initialState, {
      type: "draftChanged",
      draft: "掲載期限",
    });
    expect(drafted).toMatchObject({ writing: true, draft: "掲載期限" });
    expect(flowReducer(drafted, { type: "textSent" })).toMatchObject({
      writing: false,
      draft: "",
    });
  });

  it("作成前に選んだ緊急度を持っておき、reset で通常に戻す", () => {
    const chosen = flowReducer(initialState, {
      type: "urgencyChosen",
      urgency: "HURRY",
    });
    expect(chosen.urgencyChoice).toBe("HURRY");
    expect(flowReducer(chosen, { type: "reset" }).urgencyChoice).toBe("NORMAL");
  });

  it("入力欄の初期値を渡せ、空でなければ入力欄を開いておく", () => {
    expect(createInitialState("掲載期限")).toMatchObject({
      draft: "掲載期限",
      writing: true,
    });
    expect(createInitialState().writing).toBe(false);
  });

  it("失敗のあとに取り直した問い合わせは、エラーを残して置き換わる。作成の途中で失敗した件は入力欄を閉じる", () => {
    const failed: FlowState = {
      ...initialState,
      draft: "書いた文章",
      writing: true,
      error: { code: "AI_UNAVAILABLE", message: "m", source: "action" },
    };
    const refreshed = flowReducer(failed, {
      type: "viewRefreshed",
      view: makeView({ utterance: "書いた文章", canSendNow: true }),
    });
    expect(refreshed.view?.canSendNow).toBe(true);
    expect(refreshed.error?.code).toBe("AI_UNAVAILABLE");
    expect(refreshed).toMatchObject({ draft: "", writing: false });

    // すでに問い合わせがある場合は、入力欄に触れない
    const existing: FlowState = {
      ...failed,
      view: makeView({ utterance: "前の文章" }),
    };
    expect(
      flowReducer(existing, {
        type: "viewRefreshed",
        view: makeView({ utterance: "前の文章" }),
      }),
    ).toMatchObject({ draft: "書いた文章", writing: true });
  });

  it("開き直した問い合わせは、そのまま表示する状態になる", () => {
    const resumed = flowReducer(
      { ...initialState, resume: "loading" },
      { type: "inquiryResumed", view: makeView({ step: "STRUCTURED" }) },
    );
    expect(resumed).toMatchObject({
      resume: "idle",
      progress: [],
      view: { step: "STRUCTURED" },
    });
  });

  it("見つからない・取得に失敗した場合は問い合わせの状態を捨て、設定は残す", () => {
    const configured: FlowState = {
      ...initialState,
      mock: true,
      userName: "開発用ユーザー",
      trace: true,
      view: makeView(),
    };
    const missing = flowReducer(configured, { type: "resumeNotFound" });
    expect(missing).toMatchObject({
      resume: "notFound",
      view: null,
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
      types: [],
      urgencies: [],
    });
    expect(loaded).toMatchObject({ userName: null, trace: false });
  });
});
