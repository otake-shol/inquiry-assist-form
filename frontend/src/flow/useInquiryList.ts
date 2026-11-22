import { useEffect, useState } from "react";
import { useApi } from "../api/useApi.ts";
import type { InquiryList } from "../api/types.ts";

/** `useInquiryList` の戻り値。 */
export interface InquiryListState {
  /** 取得した一覧（まだ取得できていなければ null） */
  list: InquiryList | null;
  /** 直近の取得に失敗したか */
  failed: boolean;
}

/**
 * 送った問い合わせの一覧（`GET /api/inquiries`）を取得する。ページの上部の未読の件数と、
 * 一覧の画面の両方に使う。`refreshKey` が変わるたびに取り直す（古い応答は捨てる）。
 * 言語を切り替えると取り直す。取得に失敗しても画面は壊さず、失敗の印だけを返す。
 * @param refreshKey 取り直すきっかけ（開いているページや問い合わせが変わったときに変える）
 */
export function useInquiryList(refreshKey: string): InquiryListState {
  const api = useApi();
  const [state, setState] = useState<InquiryListState>({
    list: null,
    failed: false,
  });
  useEffect(() => {
    let cancelled = false;
    api
      .getInquiries()
      .then((list) => {
        if (!cancelled) setState({ list, failed: false });
      })
      .catch(() => {
        if (!cancelled) setState((previous) => ({ ...previous, failed: true }));
      });
    return () => {
      cancelled = true;
    };
  }, [api, refreshKey]);
  return state;
}
