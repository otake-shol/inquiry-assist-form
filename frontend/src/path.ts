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

/** パスから読み取った仕様の持ち主の画面の指定。 */
export interface OwnerRoute {
  /** 仕様の持ち主の画面か（`/owner` と `/owner/{id}` だけ） */
  owner: boolean;
  /** 開く問い合わせの id（一覧だけなら null） */
  id: string | null;
}

/** 仕様の持ち主の画面の一覧のパス。 */
export const OWNER_PATH = "/owner";

/**
 * `location.pathname` から仕様の持ち主の画面かどうかと、開く問い合わせの id を読み取る。
 * `/owner` と `/owner/{id}` 以外（`/ownerx` や `/owner/a/b` を含む）は、持ち主の画面ではない。
 * @param pathname `location.pathname` の値
 */
export function parseOwnerPath(pathname: string): OwnerRoute {
  const match = /^\/owner(?:\/([^/]+))?\/?$/.exec(pathname);
  if (match === null) return { owner: false, id: null };
  if (match[1] === undefined) return { owner: true, id: null };
  try {
    return { owner: true, id: decodeURIComponent(match[1]) };
  } catch {
    return { owner: false, id: null };
  }
}

/**
 * 仕様の持ち主の画面のパスを作る。
 * @param id 開く問い合わせの id（一覧だけなら null）
 */
export function ownerPathOf(id: string | null): string {
  return id === null ? OWNER_PATH : `${OWNER_PATH}/${encodeURIComponent(id)}`;
}

/** 先例の一覧の画面のパス。 */
export const PRECEDENTS_PATH = "/precedents";

/** 先例の一覧の絞り込み（URL のクエリに持つ）。 */
export interface PrecedentQuery {
  systemId?: string;
  functionId?: string;
  /** 種類の id */
  type?: string;
}

/**
 * 先例の一覧のパスを、絞り込みつきで作る。指定のない項目はクエリに入れない。
 * @param query 絞り込み
 */
export function precedentsPathOf(query: PrecedentQuery = {}): string {
  const params = new URLSearchParams();
  if (query.systemId) params.set("systemId", query.systemId);
  if (query.functionId) params.set("functionId", query.functionId);
  if (query.type) params.set("type", query.type);
  const text = params.toString();
  return text === "" ? PRECEDENTS_PATH : `${PRECEDENTS_PATH}?${text}`;
}

/**
 * URL のクエリ（`location.search`）から、先例の一覧の絞り込みを読み取る。
 * @param search `location.search` の値
 */
export function parsePrecedentQuery(search: string): PrecedentQuery {
  const params = new URLSearchParams(search);
  const query: PrecedentQuery = {};
  const systemId = params.get("systemId");
  const functionId = params.get("functionId");
  const type = params.get("type");
  if (systemId) query.systemId = systemId;
  if (functionId) query.functionId = functionId;
  if (type) query.type = type;
  return query;
}

/** パスから読み取った画面の指定。 */
export type Route =
  /** 新しい問い合わせの入力（`/`、および下記のどれにも当たらないパス） */
  | { kind: "new" }
  /** 送った問い合わせの一覧 */
  | { kind: "inquiries" }
  /** 1件の問い合わせ。id が null なら形式が崩れている（見つからない扱い） */
  | { kind: "inquiry"; id: string | null }
  /** 担当者の画面。id が null なら一覧だけ */
  | { kind: "operator"; id: string | null }
  /** 仕様の持ち主の画面。id が null なら一覧だけ */
  | { kind: "owner"; id: string | null }
  /** 先例の一覧（絞り込みは、クエリから別に読む） */
  | { kind: "precedents" };

/**
 * `location.pathname` から開く画面を決める。
 * `/inquiries` と `/inquiries/{id}` は本人向けの一覧と1件、`/operator` 系は担当者の画面、`/owner` 系は仕様の持ち主の画面、`/precedents` は先例の一覧。
 * それ以外（`/inquiriesx` や `/inquiries/a/b` を含む）は新しい問い合わせの入力。
 * `{id}` を復元できないときは、API を呼ばずに「見つからない」と示せるよう id を null にする。
 * @param pathname `location.pathname` の値
 */
export function parseRoute(pathname: string): Route {
  const operator = parseOperatorPath(pathname);
  if (operator.operator) return { kind: "operator", id: operator.id };
  const owner = parseOwnerPath(pathname);
  if (owner.owner) return { kind: "owner", id: owner.id };
  if (/^\/precedents\/?$/.test(pathname)) return { kind: "precedents" };
  const match = /^\/inquiries(?:\/([^/]+))?\/?$/.exec(pathname);
  if (match === null) return { kind: "new" };
  if (match[1] === undefined) return { kind: "inquiries" };
  try {
    return { kind: "inquiry", id: decodeURIComponent(match[1]) };
  } catch {
    return { kind: "inquiry", id: null };
  }
}

/** 新しい問い合わせの入力のパス。 */
export const NEW_INQUIRY_PATH = "/";

/** 送った問い合わせの一覧のパス。 */
export const INQUIRIES_PATH = "/inquiries";

/**
 * 1件の問い合わせのパスを作る。
 * @param id 問い合わせの id
 */
export function inquiryPathOf(id: string): string {
  return `/inquiries/${encodeURIComponent(id)}`;
}
