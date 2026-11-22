import { useMessages } from "../i18n/context.ts";
import { useId, type ReactNode } from "react";

/** BoardColumn の props。 */
interface BoardColumnProps {
  /** 列の見出し */
  title: string;
  /** 列にあるカードの数 */
  count: number;
  /** 列の識別子（テストや見た目の手がかり） */
  columnId: string;
  /** カード（`li`）の並び */
  children: ReactNode;
}

/**
 * ボードの1列。見出しの領域として、列の名前と件数を読み上げられる形で示す。
 * 見出しは固定し、カードが長くなったら列の中だけを縦にスクロールする。
 * カードがなければ「ありません」と薄く示す。
 * @param props 見出し、件数、カード
 */
export function BoardColumn({
  title,
  count,
  columnId,
  children,
}: BoardColumnProps) {
  const { text } = useMessages();
  const id = useId();
  const titleId = `${id}-title`;
  const countId = `${id}-count`;
  return (
    <section
      className="board-column"
      data-column={columnId}
      aria-labelledby={`${titleId} ${countId}`}
    >
      <div className="board-column-head">
        <h3 id={titleId}>{title}</h3>
        <span id={countId} className="board-count">
          {count}
          <span className="visually-hidden">{text.countUnit(count)}</span>
        </span>
      </div>
      <div className="board-column-body">
        {count === 0 ? (
          <p className="board-empty">{text.boardEmpty}</p>
        ) : (
          <ul className="board-cards">{children}</ul>
        )}
      </div>
    </section>
  );
}
