import type {
  AppConfig,
  ErrorBody,
  HandlingStatus,
  InquiryList,
  InquiryView,
  IntakeAnswerRequest,
  OperatorDetail,
  OperatorList,
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
 * JSON を送受信し、失敗を ApiError に変換する。
 * @param method HTTP メソッド
 * @param path `/api` から始まるパス
 * @param body 送る JSON（省略可）
 */
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers:
        body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("NETWORK", "サーバーに接続できませんでした");
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

/** `GET /api/config`。 */
export function getConfig(): Promise<AppConfig> {
  return request<AppConfig>("GET", "/api/config");
}

/** 問い合わせの作成の入力。どちらも省ける。 */
export interface CreateInquiryInput {
  /** 文章で始める場合の本文（1〜4000文字） */
  text?: string;
  /** 作成前に選んだ緊急度（省くと通常） */
  urgency?: Urgency;
}

/**
 * `POST /api/inquiries`。問い合わせを作る。文章がなければ最初の質問が `intake.next` に入る。
 * @param input 文章で始める場合の本文と、作成前に選んだ緊急度
 */
export function createInquiry(
  input: CreateInquiryInput = {},
): Promise<InquiryView> {
  return request<InquiryView>("POST", "/api/inquiries", input);
}

/**
 * `POST /api/inquiries/{id}/intake/answers`。`intake.next` の質問に答える。
 * @param id 問い合わせ ID
 * @param questionId 答える質問の id
 * @param answer 答え方（選ぶ、文章、わからない、書かずに進む）
 */
export function answerIntake(
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
export function backIntake(
  id: string,
  questionId: string,
): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/intake/back`, {
    questionId,
  });
}

/**
 * `POST /api/inquiries/{id}/intake/text`。文章で書く道に切り替える。
 * @param id 問い合わせ ID
 * @param text 本文（1〜4000文字）
 */
export function submitIntakeText(
  id: string,
  text: string,
): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/intake/text`, {
    text,
  });
}

/**
 * `POST /api/inquiries/{id}/intake/compose`。問いの組み立てだけをやり直す。
 * @param id 問い合わせ ID
 */
export function composeIntake(id: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/intake/compose`);
}

/**
 * `PUT /api/inquiries/{id}/urgency`。緊急度を変える。
 * @param id 問い合わせ ID
 * @param urgency 新しい緊急度
 */
export function changeUrgency(
  id: string,
  urgency: Urgency,
): Promise<InquiryView> {
  return request<InquiryView>("PUT", `/api/inquiries/${id}/urgency`, {
    urgency,
  });
}

/**
 * `PUT /api/inquiries/{id}/structured`。言い直しを確定する。
 * @param id 問い合わせ ID
 * @param summary 本人が書き直した文章（省略すると AI の言い直しをそのまま確定する）
 */
export function confirmStructured(
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
export function reviseStructured(
  id: string,
  note: string,
): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/revision`, {
    note,
  });
}

/** `POST /api/inquiries/{id}/reopen`。入口の質問へ戻る。 */
export function reopenQuestions(id: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/reopen`);
}

/** `POST /api/inquiries/{id}/resolution`。解決したかどうかを送る。 */
export function resolve(id: string, resolved: boolean): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/resolution`, {
    resolved,
  });
}

/** `POST /api/inquiries/{id}/send-now`。答えを待たずに担当者に送る。 */
export function sendNow(id: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/send-now`);
}

/** `GET /api/inquiries`。自分が作った問い合わせの一覧（新しい順）。 */
export function getInquiries(): Promise<InquiryList> {
  return request<InquiryList>("GET", "/api/inquiries");
}

/**
 * `GET /api/inquiries/{id}`。問い合わせを取得する（開き直したときの再開に使う）。
 * 取得すると、その問い合わせの未読の返信は既読になる。
 * @param id 問い合わせ ID
 */
export function getInquiry(id: string): Promise<InquiryView> {
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
export function postMessage(id: string, body: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/messages`, {
    body,
  });
}

/** `GET /api/operator/inquiries`。担当者に送られた問い合わせの一覧（全件）。 */
export function getOperatorList(): Promise<OperatorList> {
  return request<OperatorList>("GET", "/api/operator/inquiries");
}

/** `GET /api/operator/inquiries/{id}`。問い合わせの全内容。 */
export function getOperatorDetail(id: string): Promise<OperatorDetail> {
  return request<OperatorDetail>("GET", `/api/operator/inquiries/${id}`);
}

/** `POST /api/operator/inquiries/{id}/read`。既読にして全内容を受け取る。 */
export function markRead(id: string): Promise<OperatorDetail> {
  return request<OperatorDetail>("POST", `/api/operator/inquiries/${id}/read`);
}

/**
 * `PUT /api/operator/inquiries/{id}/status`。対応状況を切り替える。
 * @param id 問い合わせ ID
 * @param status 新しい対応状況
 */
export function setHandlingStatus(
  id: string,
  status: HandlingStatus,
): Promise<OperatorDetail> {
  return request<OperatorDetail>(
    "PUT",
    `/api/operator/inquiries/${id}/status`,
    { status },
  );
}

/**
 * `POST /api/operator/inquiries/{id}/replies`。担当者が本人に返信する。
 * @param id 問い合わせ ID
 * @param body 返信の本文（1〜4000文字）
 */
export function postReply(id: string, body: string): Promise<OperatorDetail> {
  return request<OperatorDetail>(
    "POST",
    `/api/operator/inquiries/${id}/replies`,
    { body },
  );
}
