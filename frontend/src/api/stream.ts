import type { ErrorBody, InquiryView, OperatorItem } from "./types.ts";

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

/** オペレータ通知ストリームのイベント受け口。 */
export interface OperatorStreamHandlers {
  /** 担当者に送られた問い合わせが届いた */
  onFiled: (item: OperatorItem) => void;
  /** 本人の追記、対応状況の変更、担当者の返信などで、問い合わせが更新された */
  onUpdated: (item: OperatorItem) => void;
  /** 切断から再接続できた（一覧を取り直す合図） */
  onReconnected: () => void;
}

/**
 * オペレータ通知ストリームを購読する関数。戻り値は購読解除関数。
 * テストでは偽物を注入できる。
 */
export type SubscribeOperatorStream = (
  handlers: OperatorStreamHandlers,
) => () => void;

/** 切断後に再接続するまでの待ち時間（ミリ秒）。 */
const RECONNECT_DELAY_MS = 3000;

/**
 * EventSource で `/api/operator/stream` を購読する本番実装。
 * 切断したら一定時間後に自分で接続し直し、つながったら `onReconnected` を呼ぶ。
 * @param handlers イベントの受け口
 * @returns 購読解除関数
 */
export const subscribeOperatorStream: SubscribeOperatorStream = (handlers) => {
  let source: EventSource | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;
  let dropped = false;

  const connect = () => {
    source = new EventSource("/api/operator/stream");
    source.addEventListener("open", () => {
      if (dropped) {
        dropped = false;
        handlers.onReconnected();
      }
    });
    source.addEventListener("filed", (event) => {
      handlers.onFiled(JSON.parse((event as MessageEvent<string>).data));
    });
    source.addEventListener("updated", (event) => {
      handlers.onUpdated(JSON.parse((event as MessageEvent<string>).data));
    });
    source.addEventListener("error", () => {
      if (stopped) return;
      dropped = true;
      source?.close();
      timer = setTimeout(connect, RECONNECT_DELAY_MS);
    });
  };
  connect();

  return () => {
    stopped = true;
    if (timer !== null) clearTimeout(timer);
    source?.close();
  };
};
