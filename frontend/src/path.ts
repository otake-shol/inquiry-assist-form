/** パスから読み取ったオペレータ画面の指定。 */
export interface OperatorRoute {
  /** オペレータ画面か（`/operator` と `/operator/{id}` だけ） */
  operator: boolean;
  /** 開く問い合わせの id（一覧だけなら null） */
  id: string | null;
}

/**
 * `location.pathname` からオペレータ画面かどうかと、開く問い合わせの id を読み取る。
 * `/operator` と `/operator/{id}` 以外（`/operatorx` や `/operator/a/b` を含む）は本人向けの画面。
 * @param pathname `location.pathname` の値
 */
export function parseOperatorPath(pathname: string): OperatorRoute {
  const match = /^\/operator(?:\/([^/]+))?\/?$/.exec(pathname);
  if (match === null) return { operator: false, id: null };
  if (match[1] === undefined) return { operator: true, id: null };
  try {
    return { operator: true, id: decodeURIComponent(match[1]) };
  } catch {
    return { operator: false, id: null };
  }
}

/**
 * パスがオペレータ画面か。
 * @param pathname `location.pathname` の値
 */
export function isOperatorPath(pathname: string): boolean {
  return parseOperatorPath(pathname).operator;
}

/**
 * オペレータ画面のパスを作る。
 * @param id 開く問い合わせの id（一覧だけなら null）
 */
export function operatorPathOf(id: string | null): string {
  return id === null ? "/operator" : `/operator/${encodeURIComponent(id)}`;
}
