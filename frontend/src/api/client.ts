import type {
  AnswerInput,
  AppConfig,
  ErrorBody,
  InquiryCategory,
  InquiryView,
} from "./types.ts";

/** API が返したエラー（`{code, message}`）を持つ例外。 */
export class ApiError extends Error {
  readonly code: string;
  /** 保存済みの問い合わせの id（種別の判定でAIが失敗した場合のみ） */
  readonly inquiryId?: string;

  /**
   * @param code エラーコード（`AI_REFUSED` など。通信失敗は `NETWORK`）
   * @param message サーバーまたはクライアントが付けた説明
   * @param inquiryId 保存済みの問い合わせの id（あれば）
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

/** `POST /api/inquiries`。種別を判定し追加質問を作る。 */
export function createInquiry(utterance: string): Promise<InquiryView> {
  return request<InquiryView>("POST", "/api/inquiries", { utterance });
}

/** `PUT /api/inquiries/{id}/category`。種別を変更する。 */
export function changeCategory(
  id: string,
  category: InquiryCategory,
): Promise<InquiryView> {
  return request<InquiryView>("PUT", `/api/inquiries/${id}/category`, {
    category,
  });
}

/** `POST /api/inquiries/{id}/answers`。追加質問に回答する。 */
export function submitAnswers(
  id: string,
  answers: AnswerInput[],
): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/answers`, {
    answers,
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

/** `POST /api/inquiries/{id}/reopen`。追加質問へ戻る。 */
export function reopenQuestions(id: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/reopen`);
}

/** `POST /api/inquiries/{id}/resolution`。解決したかどうかを送る。 */
export function resolve(id: string, resolved: boolean): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/resolution`, {
    resolved,
  });
}

/** `POST /api/inquiries/{id}/send-now`。今すぐ担当者に送る。 */
export function sendNow(id: string): Promise<InquiryView> {
  return request<InquiryView>("POST", `/api/inquiries/${id}/send-now`);
}
