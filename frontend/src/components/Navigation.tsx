import type { MouseEvent, ReactNode } from "react";
import { navigateTo } from "../location.ts";
import { INQUIRIES_PATH, NEW_INQUIRY_PATH, type Route } from "../path.ts";
import { useMessages } from "../i18n/context.ts";
import { LanguageSwitch } from "./LanguageSwitch.tsx";

/** NavLink の props。 */
interface NavLinkProps {
  /** 遷移先のパス */
  to: string;
  /** いま開いているページか（`aria-current="page"` をつける） */
  current?: boolean;
  className?: string;
  children: ReactNode;
}

/**
 * 画面内の移動に使うリンク。通常のクリックは履歴に積んで画面を切り替え、
 * 修飾キーつきのクリック（別タブで開くなど）はブラウザの動きに任せる。
 * @param props 遷移先と表示
 */
export function NavLink({ to, current, className, children }: NavLinkProps) {
  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    event.preventDefault();
    navigateTo(to);
  };
  return (
    <a
      href={to}
      className={className}
      aria-current={current === true ? "page" : undefined}
      onClick={handleClick}
    >
      {children}
    </a>
  );
}

/** ページの上部（本人向け）の props。 */
interface UserHeaderProps {
  route: Route;
  /** 利用者の名前（無ければ出さない） */
  userName: string | null;
  /** 未読の返信がある問い合わせの数 */
  unreadReplyCount: number;
}

/**
 * ページの上部（本人向け）。左に「問い合わせアシスト」（新しい問い合わせの入力へ）、
 * 右に「送った問い合わせ」（未読の返信があれば件数の印つき）と利用者の名前を置く。
 * 件数は目で見る印と別に、読み上げ用の文でも伝える。
 * @param props 開いているページと、表示する名前・件数
 */
export function UserHeader({
  route,
  userName,
  unreadReplyCount,
}: UserHeaderProps) {
  const { text: TEXT, user: USER_TEXT } = useMessages();
  return (
    <header className="site-header">
      <h1 className="brand">
        <NavLink to={NEW_INQUIRY_PATH} current={route.kind === "new"}>
          {TEXT.appTitle}
        </NavLink>
      </h1>
      <nav className="site-nav" aria-label={USER_TEXT.navLabel}>
        <NavLink to={INQUIRIES_PATH} current={route.kind === "inquiries"}>
          {USER_TEXT.myInquiries}
          {unreadReplyCount > 0 && (
            <>
              {/* 読み上げで名前と件数がつながらないよう、空白を挟む */}{" "}
              <span className="count-badge" aria-hidden="true">
                {unreadReplyCount}
              </span>
              <span className="visually-hidden">
                {USER_TEXT.unreadReplies(unreadReplyCount)}
              </span>
            </>
          )}
        </NavLink>
        {userName !== null && <span className="user-name">{userName}</span>}
        <LanguageSwitch />
      </nav>
    </header>
  );
}

/** 開発用のリンクの props。 */
interface DevLinkProps {
  to: string;
  children: ReactNode;
}

/**
 * ページの最下部に置く、控えめな開発用のリンク（本人の画面と担当者の画面の行き来）。
 * @param props 遷移先と表示
 */
export function DevLink({ to, children }: DevLinkProps) {
  return (
    <footer className="dev-links">
      <NavLink to={to} className="dev-link">
        {children}
      </NavLink>
    </footer>
  );
}
