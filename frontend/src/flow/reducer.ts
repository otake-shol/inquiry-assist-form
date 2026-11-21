import type { ErrorBody, InquiryView } from "../api/types.ts";

/** 画面に出すエラー。`source` は発生元（通信の操作か、進捗ストリームか）。 */
export interface FlowError extends ErrorBody {
  source: "action" | "stream";
}

/** 画面全体の状態。 */
export interface FlowState {
  mock: boolean;
  draft: string;
  view: InquiryView | null;
  busy: boolean;
  error: FlowError | null;
  /** 種別の判定でAIが失敗して保存された問い合わせの id。担当者へ送る操作に使う。 */
  savedInquiryId: string | null;
  progress: string[];
  /** 進捗ストリームの購読回数。再試行で増やし、購読し直すきっかけにする。 */
  streamAttempt: number;
}

/** 状態を変える操作。 */
export type FlowAction =
  | { type: "configLoaded"; mock: boolean }
  | { type: "draftChanged"; draft: string }
  | { type: "requestStarted" }
  | { type: "viewReceived"; view: InquiryView }
  | { type: "requestFailed"; error: ErrorBody }
  | { type: "streamProgress"; message: string }
  | { type: "streamFailed"; error: ErrorBody }
  | { type: "streamRetried" }
  | { type: "reset" };

/** 最初の状態。 */
export const initialState: FlowState = {
  mock: false,
  draft: "",
  view: null,
  busy: false,
  error: null,
  savedInquiryId: null,
  progress: [],
  streamAttempt: 0,
};

/**
 * 画面の状態遷移を1か所にまとめた reducer。
 * @param state 現在の状態
 * @param action 操作
 */
export function flowReducer(state: FlowState, action: FlowAction): FlowState {
  switch (action.type) {
    case "configLoaded":
      return { ...state, mock: action.mock };
    case "draftChanged":
      return { ...state, draft: action.draft };
    case "requestStarted":
      return { ...state, busy: true, error: null };
    case "viewReceived":
      return {
        ...state,
        view: action.view,
        busy: false,
        error: null,
        savedInquiryId: null,
        progress: action.view.step === "ANSWERING" ? state.progress : [],
      };
    case "requestFailed":
      return {
        ...state,
        busy: false,
        error: { ...action.error, source: "action" },
        savedInquiryId: action.error.inquiryId ?? state.savedInquiryId,
      };
    case "streamProgress":
      return { ...state, progress: [...state.progress, action.message] };
    case "streamFailed":
      return {
        ...state,
        busy: false,
        error: { ...action.error, source: "stream" },
      };
    case "streamRetried":
      return {
        ...state,
        error: null,
        progress: [],
        streamAttempt: state.streamAttempt + 1,
      };
    case "reset":
      return { ...initialState, mock: state.mock };
  }
}
