import { useMemo } from "react";
import type { InquiryList, InquiryListItem } from "../api/types.ts";
import {
  ASKER_COLUMNS,
  ASKER_COLUMN_LABELS,
  arrangeAskerBoard,
  type AskerColumn,
} from "../flow/board.ts";
import { OP_TEXT, formatFiledAt } from "../operator/labels.ts";
import { inquiryPathOf } from "../path.ts";
import { USER_TEXT, listStatusLabel } from "../text/inquiries.ts";
import { TEXT } from "../text/labels.ts";
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
 * ボードのカード1枚。言い直しの文章、日時、やり取りの件数、未読の返信の印を出し、押すとその問い合わせを開く。
 * 状態の言葉は、完了の列では終わり方の添え書きとして見せ、ほかの列では読み上げ用の文字にとどめる
 * （列の見出しが同じことを示しているため）。
 * @param props 1件と、入っている列
 */
function AskerCard({
  item,
  column,
}: {
  item: InquiryListItem;
  column: AskerColumn;
}) {
  return (
    <li>
      <NavLink
        to={inquiryPathOf(item.id)}
        className={`board-card inquiry-card ${item.unreadReply ? "unread" : ""}`}
      >
        <span className="inquiry-summary">{item.summary}</span>
        <span className="inquiry-meta">
          <time dateTime={item.createdAt}>{formatFiledAt(item.createdAt)}</time>
          {item.messageCount > 0 && (
            <span>{OP_TEXT.messageCount(item.messageCount)}</span>
          )}
          {item.unreadReply && (
            <span className="unread-mark">{USER_TEXT.unreadReplyMark}</span>
          )}
        </span>
        <span
          className={
            column === "DONE" ? "board-note inquiry-status" : "visually-hidden"
          }
        >
          {listStatusLabel(item)}
        </span>
      </NavLink>
    </li>
  );
}

/**
 * 送った問い合わせのボード（`/inquiries`）。状態ごとの列（入力の途中、担当者の確認待ち、
 * 担当者が対応中、完了）にカードを並べる。本人は状態を変えられないので、カードは動かせない。
 * まだ1件もなければ、列を出さずにその旨と新しい問い合わせを書くボタンを出す。
 * @param props 一覧と、新しい問い合わせへの受け口
 */
export function InquiryListPage({ list, failed, onNew }: InquiryListPageProps) {
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
              title={ASKER_COLUMN_LABELS[column]}
              count={board[column].length}
            >
              {board[column].map((item) => (
                <AskerCard key={item.id} item={item} column={column} />
              ))}
            </BoardColumn>
          ))}
        </div>
      )}
    </section>
  );
}
