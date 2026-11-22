import { messagesOf } from "../i18n/messages.ts";
import type { Language } from "../i18n/language.ts";
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
  language: Language,
) => () => void;

/**
 * EventSource で `answer-stream` を購読する本番実装。
 * `done` / `failed` を受けたら接続を閉じ、自動再接続を止める。
 * `EventSource` には、ヘッダーを付けられないので、見る人の言語をクエリの `lang` で渡す。
 * @param id 問い合わせ ID
 * @param handlers イベントの受け口
 * @param language 見る人の言語
 * @returns 購読解除関数
 */
export const subscribeAnswerStream: SubscribeAnswerStream = (
  id,
  handlers,
  language,
) => {
  const source = new EventSource(
    `/api/inquiries/${id}/answer-stream?lang=${language}`,
  );
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
      message: messagesOf(language).errors.streamLost,
    });
  });

  return close;
};

/** オペレータ通知ストリームのイベント受け口。 */
export interface OperatorStreamHandlers {
  /** 担当者に送られた問い合わせが届いた */
  onFiled: (item: OperatorItem) => void;
  /** 本人の追記や結果の確かめ、担当者の発言や区間の変更などで、問い合わせの状態が変わった */
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
  language: Language,
) => () => void;

/** 切断後に再接続するまでの待ち時間（ミリ秒）。 */
const RECONNECT_DELAY_MS = 3000;

/**
 * EventSource で `/api/operator/stream` を購読する本番実装。
 * 切断したら一定時間後に自分で接続し直し、つながったら `onReconnected` を呼ぶ。
 * 見る人の言語はクエリの `lang` で渡す。言語を切り替えたら、つなぎ直す。
 * @param handlers イベントの受け口
 * @param language 見る人の言語
 * @returns 購読解除関数
 */
export const subscribeOperatorStream: SubscribeOperatorStream = (
  handlers,
  language,
) => {
  let source: EventSource | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;
  let dropped = false;

  const connect = () => {
    source = new EventSource(`/api/operator/stream?lang=${language}`);
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
