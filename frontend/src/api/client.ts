import { messagesOf } from "../i18n/messages.ts";
import type { Language } from "../i18n/language.ts";
import type {
  AppConfig,
  DeclineReasonKind,
  ErrorBody,
  InquiryList,
  InquiryView,
  IntakeAnswerRequest,
  OperatorDetail,
  OperatorList,
  Phase,
  Urgency,
} from "./types.ts";

/** API が返したエラー（`{code, message}`）を持つ例外。 */
export class ApiError extends Error {
  readonly code: string;
  /** 答えを保存した問い合わせの id（問いの組み立てに失敗した場合のみ） */
  readonly inquiryId?: string;

  /**
   * @param code エラーコード（`AI_REFUSED` など。通信失敗は `NETWORK`）
   * @param message サーバーまたはクライアントが付けた説明
   * @param inquiryId 答えを保存した問い合わせの id（あれば）
   */
  constructor(code: string, message: string, inquiryId?: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.inquiryId = inquiryId;
  }
}

/**
 * JSON を送受信し、失敗を ApiError に変換する。見る人の言語を `Accept-Language` ヘッダーで渡す。
 * @param language 見る人の言語
 * @param method HTTP メソッド
 * @param path `/api` から始まるパス
 * @param body 送る JSON（省略可）
 */
async function send<T>(
  language: Language,
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: {
        "Accept-Language": language,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("NETWORK", messagesOf(language).errors.network);
  }
  if (!response.ok) {
    let parsed: Partial<ErrorBody> = {};
    try {
      parsed = (await response.json()) as Partial<ErrorBody>;
    } catch {
      // 本文が JSON でない場合は HTTP ステータスだけで扱う
    }
    throw new ApiError(
      parsed.code ?? `HTTP_${response.status}`,
      parsed.message ?? `HTTP ${response.status}`,
      parsed.inquiryId,
    );
  }
  return (await response.json()) as T;
}

/** 問い合わせの作成の入力。どちらも省ける。 */
export interface CreateInquiryInput {
  /** 文章で始める場合の本文（1〜4000文字） */
  text?: string;
  /** 作成前に選んだ緊急度（省くと通常） */
  urgency?: Urgency;
}

/** 置き直しの内容。どれも決めない（null）にできる。 */
export interface PlacementInput {
  type: string | null;
  systemId: string | null;
  functionId: string | null;
}

/** 担当者が本人に送る発言の種類。聞き返し（`ASK`）か、結果を伝える（`RESULT`）。 */
export type ReplyKind = "ASK" | "RESULT";

/**
 * 見る人の言語を持った API の呼び出しを作る。どの呼び出しにも `Accept-Language` を付ける。
 * 画面は `useApi()` で、いまの言語の呼び出しを受け取る。
 * @param language 見る人の言語
 */
export function createClient(language: Language) {
  const request = <T>(method: string, path: string, body?: unknown) =>
    send<T>(language, method, path, body);

  /** `GET /api/config`。 */
  function getConfig(): Promise<AppConfig> {
    return request<AppConfig>("GET", "/api/config");
  }

  /**
   * `POST /api/inquiries`。問い合わせを作る。文章がなければ最初の質問が `intake.next` に入る。
   * @param input 文章で始める場合の本文と、作成前に選んだ緊急度
   */
  function createInquiry(input: CreateInquiryInput = {}): Promise<InquiryView> {
    return request<InquiryView>("POST", "/api/inquiries", input);
  }

  /**
   * `POST /api/inquiries/{id}/intake/answers`。`intake.next` の質問に答える。
   * @param id 問い合わせ ID
   * @param questionId 答える質問の id
   * @param answer 答え方（選ぶ、文章、わからない、書かずに進む）
   */
  function answerIntake(
    id: string,
    questionId: string,
    answer: IntakeAnswerRequest,
  ): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/intake/answers`, {
      questionId,
      ...answer,
    });
  }

  /**
   * `POST /api/inquiries/{id}/intake/back`。答え終えた質問に戻る（それより後の答えは消える）。
   * @param id 問い合わせ ID
   * @param questionId 戻る先の質問の id
   */
  function backIntake(id: string, questionId: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/intake/back`, {
      questionId,
    });
  }

  /**
   * `POST /api/inquiries/{id}/intake/text`。文章で書く道に切り替える。
   * @param id 問い合わせ ID
   * @param text 本文（1〜4000文字）
   */
  function submitIntakeText(id: string, text: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/intake/text`, {
      text,
    });
  }

  /**
   * `POST /api/inquiries/{id}/intake/compose`。問いの組み立てだけをやり直す。
   * @param id 問い合わせ ID
   */
  function composeIntake(id: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/intake/compose`);
  }

  /**
   * `PUT /api/inquiries/{id}/urgency`。緊急度を変える。
   * @param id 問い合わせ ID
   * @param urgency 新しい緊急度
   */
  function changeUrgency(id: string, urgency: Urgency): Promise<InquiryView> {
    return request<InquiryView>("PUT", `/api/inquiries/${id}/urgency`, {
      urgency,
    });
  }

  /**
   * `PUT /api/inquiries/{id}/structured`。言い直しを確定する。
   * @param id 問い合わせ ID
   * @param summary 本人が書き直した文章（省略すると AI の言い直しをそのまま確定する）
   */
  function confirmStructured(
    id: string,
    summary?: string,
  ): Promise<InquiryView> {
    return request<InquiryView>(
      "PUT",
      `/api/inquiries/${id}/structured`,
      summary === undefined ? {} : { summary },
    );
  }

  /**
   * `POST /api/inquiries/{id}/revision`。違う点を一言伝えて言い直しを作り直してもらう。
   * @param id 問い合わせ ID
   * @param note 違う点の一言
   */
  function reviseStructured(id: string, note: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/revision`, {
      note,
    });
  }

  /** `POST /api/inquiries/{id}/reopen`。入口の質問へ戻る。 */
  function reopenQuestions(id: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/reopen`);
  }

  /**
   * `POST /api/inquiries/{id}/resolution`。結果（AI の答え、または担当者が伝えた結果）を確かめる。
   * @param id 問い合わせ ID
   * @param resolved 解決したか
   * @param note 解決していない点（任意。「まだ解決していません」のときだけ付ける）
   */
  function resolve(
    id: string,
    resolved: boolean,
    note?: string,
  ): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/resolution`, {
      resolved,
      ...(note === undefined ? {} : { note }),
    });
  }

  /** `POST /api/inquiries/{id}/send-now`。答えを待たずに担当者に送る。 */
  function sendNow(id: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/send-now`);
  }

  /** `GET /api/inquiries`。自分が作った問い合わせの一覧（新しい順）。 */
  function getInquiries(): Promise<InquiryList> {
    return request<InquiryList>("GET", "/api/inquiries");
  }

  /**
   * `GET /api/inquiries/{id}`。問い合わせを取得する（開き直したときの再開に使う）。
   * 取得すると、その問い合わせの未読の返信は既読になる。
   * @param id 問い合わせ ID
   */
  function getInquiry(id: string): Promise<InquiryView> {
    return request<InquiryView>(
      "GET",
      `/api/inquiries/${encodeURIComponent(id)}`,
    );
  }

  /**
   * `POST /api/inquiries/{id}/messages`。担当者に送った問い合わせへ追記する。
   * @param id 問い合わせ ID
   * @param body 追記の本文（1〜4000文字）
   */
  function postMessage(id: string, body: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/messages`, {
      body,
    });
  }

  /**
   * `POST /api/inquiries/{id}/withdraw`。問い合わせを取り下げる（終わっていない件だけ）。
   * @param id 問い合わせ ID
   */
  function withdraw(id: string): Promise<InquiryView> {
    return request<InquiryView>("POST", `/api/inquiries/${id}/withdraw`);
  }

  /** `GET /api/operator/inquiries`。担当者に送られた問い合わせの一覧（全件）。 */
  function getOperatorList(): Promise<OperatorList> {
    return request<OperatorList>("GET", "/api/operator/inquiries");
  }

  /** `GET /api/operator/inquiries/{id}`。問い合わせの全内容。 */
  function getOperatorDetail(id: string): Promise<OperatorDetail> {
    return request<OperatorDetail>("GET", `/api/operator/inquiries/${id}`);
  }

  /** `POST /api/operator/inquiries/{id}/read`。既読にして全内容を受け取る。 */
  function markRead(id: string): Promise<OperatorDetail> {
    return request<OperatorDetail>(
      "POST",
      `/api/operator/inquiries/${id}/read`,
    );
  }

  /**
   * `POST /api/operator/inquiries/{id}/replies`。担当者が本人に聞き返す、または結果を伝える。
   * @param id 問い合わせ ID
   * @param kind 発言の種類
   * @param body 本文（1〜4000文字）
   */
  function postReply(
    id: string,
    kind: ReplyKind,
    body: string,
  ): Promise<OperatorDetail> {
    return request<OperatorDetail>(
      "POST",
      `/api/operator/inquiries/${id}/replies`,
      { kind, body },
    );
  }

  /**
   * `POST /api/operator/inquiries/{id}/decline`。見送りとして終える。
   * @param id 問い合わせ ID
   * @param reasonKind 理由の種類
   * @param reason 理由の文章（1〜2000文字）
   */
  function declineInquiry(
    id: string,
    reasonKind: DeclineReasonKind,
    reason: string,
  ): Promise<OperatorDetail> {
    return request<OperatorDetail>(
      "POST",
      `/api/operator/inquiries/${id}/decline`,
      { reasonKind, reason },
    );
  }

  /**
   * `PUT /api/operator/inquiries/{id}/phase`。区間を変える（分かる、決まる、行われる）。
   * @param id 問い合わせ ID
   * @param phase 新しい区間
   */
  function setPhase(id: string, phase: Phase): Promise<OperatorDetail> {
    return request<OperatorDetail>(
      "PUT",
      `/api/operator/inquiries/${id}/phase`,
      {
        phase,
      },
    );
  }

  /**
   * `PUT /api/operator/inquiries/{id}/placement`。種類、システム、機能を置き直す。
   * @param id 問い合わせ ID
   * @param placement 置き直す内容
   */
  function setPlacement(
    id: string,
    placement: PlacementInput,
  ): Promise<OperatorDetail> {
    return request<OperatorDetail>(
      "PUT",
      `/api/operator/inquiries/${id}/placement`,
      placement,
    );
  }

  return {
    getConfig,
    createInquiry,
    answerIntake,
    backIntake,
    submitIntakeText,
    composeIntake,
    changeUrgency,
    confirmStructured,
    reviseStructured,
    reopenQuestions,
    resolve,
    sendNow,
    getInquiries,
    getInquiry,
    postMessage,
    withdraw,
    getOperatorList,
    getOperatorDetail,
    markRead,
    postReply,
    declineInquiry,
    setPhase,
    setPlacement,
  };
}

/** 言語を持った API の呼び出しの一式。 */
export type ApiClient = ReturnType<typeof createClient>;
