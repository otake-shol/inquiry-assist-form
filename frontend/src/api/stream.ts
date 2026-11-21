import type { ErrorBody, InquiryView } from "./types.ts";

/** 0次回答ストリームのイベント受け口。 */
export interface AnswerStreamHandlers {
  onProgress: (message: string) => void;
  onDone: (view: InquiryView) => void;
  onFailed: (error: ErrorBody) => void;
}

/**
 * 0次回答ストリームを購読する関数。戻り値は購読解除（接続を閉じる）関数。
 * テストでは偽物を注入できるよう、画面はこの型だけに依存する。
 */
export type SubscribeAnswerStream = (
  id: string,
  handlers: AnswerStreamHandlers,
) => () => void;

/**
 * EventSource で `answer-stream` を購読する本番実装。
 * `done` / `failed` を受けたら接続を閉じ、自動再接続を止める。
 * @param id 問い合わせ ID
 * @param handlers イベントの受け口
 * @returns 購読解除関数
 */
export const subscribeAnswerStream: SubscribeAnswerStream = (id, handlers) => {
  const source = new EventSource(`/api/inquiries/${id}/answer-stream`);
  let closed = false;
  const close = () => {
    closed = true;
    source.close();
  };

  source.addEventListener("progress", (event) => {
    if (closed) return;
    const data = JSON.parse((event as MessageEvent<string>).data) as {
      message: string;
    };
    handlers.onProgress(data.message);
  });
  source.addEventListener("done", (event) => {
    if (closed) return;
    close();
    handlers.onDone(JSON.parse((event as MessageEvent<string>).data));
  });
  source.addEventListener("failed", (event) => {
    if (closed) return;
    close();
    handlers.onFailed(JSON.parse((event as MessageEvent<string>).data));
  });
  // サーバーが送ったイベントではない接続エラー（切断・接続失敗）
  source.addEventListener("error", () => {
    if (closed) return;
    close();
    handlers.onFailed({
      code: "NETWORK",
      message: "サーバーとの接続が切れました",
    });
  });

  return close;
};
