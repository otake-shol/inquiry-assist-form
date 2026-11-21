import { describe, expect, it } from "vitest";
import { makeView } from "../test/fakes.ts";
import { flowReducer, initialState, type FlowState } from "./reducer.ts";

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
});
