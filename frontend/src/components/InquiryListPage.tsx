import { useMemo } from "react";
import type { InquiryList, InquiryListItem } from "../api/types.ts";
import { ASKER_COLUMNS, arrangeAskerBoard } from "../flow/board.ts";
import { useMessages } from "../i18n/context.ts";
import { formatFiledAt } from "../operator/labels.ts";
import { inquiryPathOf } from "../path.ts";
import { askerStatusLabel } from "../text/inquiries.ts";
import { BoardColumn } from "./BoardColumn.tsx";
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
 * ボードのカード1枚。言い直しの文章、日時、やり取りの件数、未読の返信の印と、本人に見せる言葉
 * （いまの状況、終わっていれば終わり方）を出し、押すとその問い合わせを開く。
 * 結果を確かめている件は、担当者に渡したことがあれば担当者の結果が届いている件として扱う。
 * @param props 1件
 */
function AskerCard({ item }: { item: InquiryListItem }) {
  const { user: USER_TEXT, op, asker, meta } = useMessages();
  return (
    <li>
      <NavLink
        to={inquiryPathOf(item.id)}
        className={`board-card inquiry-card ${item.unreadReply ? "unread" : ""}`}
      >
        <span className="inquiry-summary" lang={item.language}>
          {item.summary}
        </span>
        <span className="inquiry-meta">
          <time dateTime={item.createdAt}>
            {formatFiledAt(item.createdAt, meta.locale)}
          </time>
          {item.messageCount > 0 && (
            <span>{op.messageCount(item.messageCount)}</span>
          )}
          {item.unreadReply && (
            <span className="unread-mark">{USER_TEXT.unreadReplyMark}</span>
          )}
        </span>
        <span className="board-note inquiry-status">
          {askerStatusLabel(item, item.handedOverAt !== null, asker)}
        </span>
      </NavLink>
    </li>
  );
}

/**
 * 送った問い合わせのボード（`/inquiries`）。誰の番かで分けた列（あなたの番、相手の番、終わり）に
 * カードを並べる。本人は状態を変えられないので、カードは動かせない。
 * まだ1件もなければ、列を出さずにその旨と新しい問い合わせを書くボタンを出す。
 * @param props 一覧と、新しい問い合わせへの受け口
 */
export function InquiryListPage({ list, failed, onNew }: InquiryListPageProps) {
  const { text: TEXT, user: USER_TEXT, asker } = useMessages();
  const board = useMemo(
    () => (list === null ? null : arrangeAskerBoard(list.items)),
    [list],
  );
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
      {board !== null && list !== null && list.items.length > 0 && (
        <div className="board-columns asker-columns">
          {ASKER_COLUMNS.map((column) => (
            <BoardColumn
              key={column}
              columnId={column}
              title={asker.columns[column]}
              count={board[column].length}
            >
              {board[column].map((item) => (
                <AskerCard key={item.id} item={item} />
              ))}
            </BoardColumn>
          ))}
        </div>
      )}
    </section>
  );
}
