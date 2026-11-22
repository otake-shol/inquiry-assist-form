import type {
  ErrorBody,
  InquiryView,
  TypeOption,
  Urgency,
  SystemOption,
} from "../api/types.ts";

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
  /** 種類の選択肢（GET /api/config）。問い合わせの作成前の1問目に使う */
  types: TypeOption[];
  /** 緊急度の選択肢（GET /api/config） */
  urgencies: SystemOption[];
  /** 利用者の名前（GET /api/config の `user.name`。無ければ null） */
  userName: string | null;
  /** スキルの実行記録を見せるか（GET /api/config の `trace`） */
  trace: boolean;
  /** URL で開いた問い合わせの取得状況。読み込み中、見つからない、またはどちらでもない */
  resume: "idle" | "loading" | "notFound";
  /** 「文章で書く」の入力欄を開いているか */
  writing: boolean;
  /** 「文章で書く」の入力欄の中身 */
  draft: string;
  /** 問い合わせの作成前に選んだ緊急度（作成後は問い合わせの値を使う） */
  urgencyChoice: Urgency;
  view: InquiryView | null;
  busy: boolean;
  error: FlowError | null;
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
      types: TypeOption[];
      urgencies: SystemOption[];
      userName?: string | null;
      trace?: boolean;
    }
  | { type: "writingOpened" }
  | { type: "draftChanged"; draft: string }
  | { type: "textSent" }
  | { type: "urgencyChosen"; urgency: Urgency }
  | { type: "requestStarted" }
  | { type: "viewReceived"; view: InquiryView }
  | { type: "viewRefreshed"; view: InquiryView }
  | { type: "requestFailed"; error: ErrorBody }
  | { type: "streamProgress"; message: string }
  | { type: "streamFailed"; error: ErrorBody }
  | { type: "streamRetried" }
  | { type: "resumeStarted" }
  | { type: "inquiryResumed"; view: InquiryView }
  | { type: "resumeNotFound" }
  | { type: "resumeFailed"; error: ErrorBody }
  | { type: "reset" };

/**
 * 最初の状態を作る。
 * @param draft 「文章で書く」の入力欄の初期値（URL の `?text=`）。空でなければ入力欄を開いておく
 */
export function createInitialState(draft = ""): FlowState {
  return {
    mock: false,
    skills: null,
    filedGuidance: null,
    types: [],
    urgencies: [],
    userName: null,
    trace: false,
    resume: "idle",
    writing: draft !== "",
    draft,
    urgencyChoice: "NORMAL",
    view: null,
    busy: false,
    error: null,
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
        types: action.types,
        urgencies: action.urgencies,
        userName: action.userName ?? null,
        trace: action.trace ?? false,
      };
    case "writingOpened":
      return { ...state, writing: true };
    case "draftChanged":
      // 例文を選んだときも、入力欄を開いて入れる
      return { ...state, draft: action.draft, writing: true };
    case "textSent":
      return { ...state, draft: "", writing: false };
    case "urgencyChosen":
      return { ...state, urgencyChoice: action.urgency };
    case "requestStarted":
      return { ...state, busy: true, error: null };
    case "viewReceived":
      return {
        ...state,
        view: action.view,
        busy: false,
        error: null,
        progress:
          action.view.awaiting === "INVESTIGATION" ? state.progress : [],
      };
    case "viewRefreshed":
      // 失敗のあとに、保存された最新の状態を取り直したもの。エラーの表示は残す。
      // 作成の途中で失敗した問い合わせ（本文が保存済み）なら、送った文章の入力欄は閉じて空にする
      return {
        ...state,
        view: action.view,
        ...(state.view === null && action.view.utterance !== null
          ? { writing: false, draft: "" }
          : {}),
      };
    case "requestFailed":
      return {
        ...state,
        busy: false,
        error: { ...action.error, source: "action" },
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
    case "resumeStarted":
      return { ...resetKeepingConfig(state), resume: "loading" };
    case "inquiryResumed":
      return {
        ...state,
        view: action.view,
        busy: false,
        error: null,
        resume: "idle",
        progress: [],
      };
    case "resumeNotFound":
      return { ...resetKeepingConfig(state), resume: "notFound" };
    case "resumeFailed":
      return {
        ...resetKeepingConfig(state),
        error: { ...action.error, source: "action" },
      };
    case "reset":
      return resetKeepingConfig(state);
  }
}

/**
 * 問い合わせの状態を捨てて最初に戻す。`GET /api/config` から受け取った設定は残す。
 * @param state 現在の状態
 */
function resetKeepingConfig(state: FlowState): FlowState {
  return {
    ...createInitialState(),
    mock: state.mock,
    skills: state.skills,
    filedGuidance: state.filedGuidance,
    types: state.types,
    urgencies: state.urgencies,
    userName: state.userName,
    trace: state.trace,
  };
}
