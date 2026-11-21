import type { ErrorBody, InquiryView, SystemOption } from "../api/types.ts";

/** 対象システムで「わからない」を明示的に選んだ状態。 */
export const UNKNOWN_SYSTEM = "__unknown__";

/**
 * 選択状態を API に送る systemId に変える。未選択と「わからない」はどちらも null。
 * @param choice 画面での選択（未選択は null）
 */
export function systemIdOf(choice: string | null): string | null {
  return choice === null || choice === UNKNOWN_SYSTEM ? null : choice;
}

/** 画面に出すエラー。`source` は発生元（通信の操作か、進捗ストリームか）。 */
export interface FlowError extends ErrorBody {
  source: "action" | "stream";
}

/** 画面全体の状態。 */
export interface FlowState {
  mock: boolean;
  /** スキルごとの実装（GET /api/config。無ければ null） */
  skills: Record<string, string> | null;
  /** 担当者に送った後の案内文（GET /api/config） */
  filedGuidance: string | null;
  /** 対象システムの選択肢（GET /api/config） */
  systems: SystemOption[];
  /** 入力時に選んだ対象システム（未選択は null、「わからない」は UNKNOWN_SYSTEM） */
  systemChoice: string | null;
  draft: string;
  view: InquiryView | null;
  busy: boolean;
  error: FlowError | null;
  /** 判定のAI失敗で保存された問い合わせの id。担当者へ送る操作に使う。 */
  savedInquiryId: string | null;
  /** 追加質問への回答（順番どおり。「わからない」は null）。 */
  answers: (string | null)[];
  /** 受け取りの「変更」で選択肢を開いているか */
  categoryOpen: boolean;
  progress: string[];
  /** 進捗ストリームの購読回数。再試行で増やし、購読し直すきっかけにする。 */
  streamAttempt: number;
}

/** 状態を変える操作。 */
export type FlowAction =
  | {
      type: "configLoaded";
      mock: boolean;
      skills: Record<string, string> | null;
      filedGuidance: string | null;
      systems: SystemOption[];
    }
  | { type: "systemChosen"; choice: string | null }
  | { type: "draftChanged"; draft: string }
  | { type: "requestStarted" }
  | { type: "viewReceived"; view: InquiryView }
  | { type: "requestFailed"; error: ErrorBody }
  | { type: "answerGiven"; index: number; answer: string | null }
  | { type: "answersRewound"; index: number }
  | { type: "categoryToggled" }
  | { type: "streamProgress"; message: string }
  | { type: "streamFailed"; error: ErrorBody }
  | { type: "streamRetried" }
  | { type: "reset" };

/**
 * 最初の状態を作る。
 * @param draft 入力欄の初期値（URL の `?text=`）
 */
export function createInitialState(draft = ""): FlowState {
  return {
    mock: false,
    skills: null,
    filedGuidance: null,
    systems: [],
    systemChoice: null,
    draft,
    view: null,
    busy: false,
    error: null,
    savedInquiryId: null,
    answers: [],
    categoryOpen: false,
    progress: [],
    streamAttempt: 0,
  };
}

/**
 * 画面の状態遷移を1か所にまとめた reducer。
 * @param state 現在の状態
 * @param action 操作
 */
export function flowReducer(state: FlowState, action: FlowAction): FlowState {
  switch (action.type) {
    case "configLoaded":
      return {
        ...state,
        mock: action.mock,
        skills: action.skills,
        filedGuidance: action.filedGuidance,
        systems: action.systems,
      };
    case "systemChosen":
      return { ...state, systemChoice: action.choice };
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
        categoryOpen: false,
        // 追加質問に（戻って）入り直したら、質問を最初から聞き直す
        answers: action.view.step === "TRIAGED" ? [] : state.answers,
        progress: action.view.step === "ANSWERING" ? state.progress : [],
      };
    case "requestFailed":
      return {
        ...state,
        busy: false,
        error: { ...action.error, source: "action" },
        savedInquiryId: action.error.inquiryId ?? state.savedInquiryId,
      };
    case "answerGiven":
      return {
        ...state,
        answers: [...state.answers.slice(0, action.index), action.answer],
        error: null,
      };
    case "answersRewound":
      return {
        ...state,
        answers: state.answers.slice(0, action.index),
        error: null,
      };
    case "categoryToggled":
      return { ...state, categoryOpen: !state.categoryOpen };
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
      return {
        ...createInitialState(),
        mock: state.mock,
        skills: state.skills,
        filedGuidance: state.filedGuidance,
        systems: state.systems,
      };
  }
}
