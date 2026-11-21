import type { InquiryList } from "../api/types.ts";
import { formatFiledAt } from "../operator/labels.ts";
import { inquiryPathOf } from "../path.ts";
import { USER_TEXT, listStatusLabel } from "../text/inquiries.ts";
import { TEXT } from "../text/labels.ts";
import { NavLink } from "./Navigation.tsx";

/** 送った問い合わせの一覧の props。 */
interface InquiryListPageProps {
  /** 取得した一覧（取得前は null） */
  list: InquiryList | null;
  /** 取得に失敗したか */
  failed: boolean;
  /** 「新しい問い合わせを書く」を押した */
  onNew: () => void;
}

/**
 * 送った問い合わせの一覧（`/inquiries`）。1件ごとに言い直しの文章、日時、いまの状態、
 * 未読の返信の印を出し、押すとその問い合わせを開く。まだ1件もなければ、その旨と
 * 新しい問い合わせを書くボタンを出す。
 * @param props 一覧と、新しい問い合わせへの受け口
 */
export function InquiryListPage({ list, failed, onNew }: InquiryListPageProps) {
  return (
    <section className="inquiry-list" aria-labelledby="inquiry-list-title">
      <h2 id="inquiry-list-title">{USER_TEXT.myInquiries}</h2>
      {failed && <p role="alert">{USER_TEXT.loadFailed}</p>}
      {!failed && list === null && <p className="muted">{USER_TEXT.loading}</p>}
      {list !== null && list.items.length === 0 && (
        <div className="empty-list">
          <p>{USER_TEXT.listEmpty}</p>
          <div className="actions">
            <button type="button" className="primary" onClick={onNew}>
              {TEXT.restart}
            </button>
          </div>
        </div>
      )}
      {list !== null && list.items.length > 0 && (
        <ul className="inquiry-rows">
          {list.items.map((item) => (
            <li key={item.id}>
              <NavLink
                to={inquiryPathOf(item.id)}
                className={`inquiry-row ${item.unreadReply ? "unread" : ""}`}
              >
                <span className="inquiry-summary">{item.summary}</span>
                <span className="inquiry-meta">
                  <time dateTime={item.createdAt}>
                    {formatFiledAt(item.createdAt)}
                  </time>
                  <span className="inquiry-status">
                    {listStatusLabel(item)}
                  </span>
                  {item.unreadReply && (
                    <span className="unread-mark">
                      {USER_TEXT.unreadReplyMark}
                    </span>
                  )}
                </span>
              </NavLink>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
