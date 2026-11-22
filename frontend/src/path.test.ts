import { describe, expect, it } from "vitest";
import {
  inquiryPathOf,
  ownerPathOf,
  parsePrecedentQuery,
  parseRoute,
  precedentsPathOf,
} from "./path.ts";

describe("parseRoute", () => {
  it("/ は新しい問い合わせ、/inquiries は一覧", () => {
    expect(parseRoute("/")).toEqual({ kind: "new" });
    expect(parseRoute("/inquiries")).toEqual({ kind: "inquiries" });
    expect(parseRoute("/inquiries/")).toEqual({ kind: "inquiries" });
  });

  it("/inquiries/{id} は id を取り出し、エンコードされた id も復元する", () => {
    expect(parseRoute("/inquiries/0b9c-1")).toEqual({
      kind: "inquiry",
      id: "0b9c-1",
    });
    expect(parseRoute("/inquiries/0b9c-1/")).toEqual({
      kind: "inquiry",
      id: "0b9c-1",
    });
    expect(parseRoute("/inquiries/a%20b")).toEqual({
      kind: "inquiry",
      id: "a b",
    });
  });

  it("形式の崩れた id は、見つからない扱いにできるよう id を null にする", () => {
    expect(parseRoute("/inquiries/%E0%A4%A")).toEqual({
      kind: "inquiry",
      id: null,
    });
  });

  it("/operator と /operator/{id} は担当者の画面", () => {
    expect(parseRoute("/operator")).toEqual({ kind: "operator", id: null });
    expect(parseRoute("/operator/op-1")).toEqual({
      kind: "operator",
      id: "op-1",
    });
  });

  it("/owner と /owner/{id} は仕様の持ち主の画面。エンコードされた id も復元する", () => {
    expect(parseRoute("/owner")).toEqual({ kind: "owner", id: null });
    expect(parseRoute("/owner/")).toEqual({ kind: "owner", id: null });
    expect(parseRoute("/owner/op-1")).toEqual({ kind: "owner", id: "op-1" });
    expect(parseRoute(ownerPathOf("a b/c"))).toEqual({
      kind: "owner",
      id: "a b/c",
    });
    expect(ownerPathOf(null)).toBe("/owner");
    expect(ownerPathOf("op-1")).toBe("/owner/op-1");
  });

  it("/precedents は先例の一覧。絞り込みはクエリから別に読む（パスの判定には含めない）", () => {
    expect(parseRoute("/precedents")).toEqual({ kind: "precedents" });
    expect(parseRoute("/precedents/")).toEqual({ kind: "precedents" });
  });

  it("先例の一覧のパスは、指定のある項目だけをクエリに入れ、読み取りと往復できる", () => {
    expect(precedentsPathOf()).toBe("/precedents");
    expect(precedentsPathOf({ systemId: "s", type: "HOW_TO" })).toBe(
      "/precedents?systemId=s&type=HOW_TO",
    );
    const query = { systemId: "a b", functionId: "f&1", type: "LOOKS_WRONG" };
    expect(parsePrecedentQuery(precedentsPathOf(query).split("?")[1])).toEqual(
      query,
    );
    expect(parsePrecedentQuery("")).toEqual({});
    expect(parsePrecedentQuery("?systemId=&type=x&other=1")).toEqual({
      type: "x",
    });
  });

  it("別の単語や深いパスは新しい問い合わせ（本人向けの入力）として扱う", () => {
    for (const path of [
      "/inquiriesx",
      "/inquiries-x/1",
      "/inquiries/a/b",
      "/x/inquiries",
      "/operatorx",
      "/operator/a/b",
      "/ownerx",
      "/precedentsx",
      "/precedents/a",
      "/owner/a/b",
      "/x/owner",
    ]) {
      expect(parseRoute(path)).toEqual({ kind: "new" });
    }
  });

  it("inquiryPathOf は parseRoute と往復できる", () => {
    expect(inquiryPathOf("abc-1")).toBe("/inquiries/abc-1");
    expect(parseRoute(inquiryPathOf("a b/c"))).toEqual({
      kind: "inquiry",
      id: "a b/c",
    });
  });
});
