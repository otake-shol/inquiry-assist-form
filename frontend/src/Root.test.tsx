import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { isOperatorPath, operatorPathOf, parseOperatorPath } from "./path.ts";
import Root from "./Root.tsx";
import {
  FakeOperatorStream,
  FakeStream,
  installFakeFetch,
} from "./test/fakes.ts";

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
});

describe("パスによる画面の切り替え", () => {
  it("isOperatorPath は /operator だけをオペレータ画面とみなす", () => {
    expect(isOperatorPath("/operator")).toBe(true);
    expect(isOperatorPath("/operator/")).toBe(true);
    expect(isOperatorPath("/")).toBe(false);
    expect(isOperatorPath("/operators")).toBe(false);
  });

  it("(d) /operator で始まっていても別の単語のパスは本人向けとみなす", () => {
    for (const path of [
      "/operatorx",
      "/operator-x/1",
      "/operatorx/abc",
      "/operator/a/b",
      "/x/operator",
    ]) {
      expect(parseOperatorPath(path)).toEqual({ operator: false, id: null });
    }
  });

  it("/operator/{id} は id を取り出し、エンコードされた id も復元する", () => {
    expect(parseOperatorPath("/operator")).toEqual({
      operator: true,
      id: null,
    });
    expect(parseOperatorPath("/operator/0b9c-1")).toEqual({
      operator: true,
      id: "0b9c-1",
    });
    expect(parseOperatorPath("/operator/0b9c-1/")).toEqual({
      operator: true,
      id: "0b9c-1",
    });
    expect(parseOperatorPath("/operator/a%20b")).toEqual({
      operator: true,
      id: "a b",
    });
    expect(parseOperatorPath("/operator/%E0%A4%A")).toEqual({
      operator: false,
      id: null,
    });
  });

  it("operatorPathOf は parseOperatorPath と往復できる", () => {
    expect(operatorPathOf(null)).toBe("/operator");
    expect(parseOperatorPath(operatorPathOf("a b/c")).id).toBe("a b/c");
  });

  it("(d) /operatorx では本人向けの画面が出る", async () => {
    window.history.pushState({}, "", "/operatorx");
    installFakeFetch({});
    render(
      <Root
        subscribeAnswers={new FakeStream().subscribe}
        subscribeOperator={new FakeOperatorStream().subscribe}
      />,
    );
    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
  });

  it("(j) /operator ではオペレータ画面が出る", async () => {
    window.history.pushState({}, "", "/operator");
    installFakeFetch({
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    render(
      <Root
        subscribeAnswers={new FakeStream().subscribe}
        subscribeOperator={new FakeOperatorStream().subscribe}
      />,
    );

    expect(
      await screen.findByRole("heading", { name: "問い合わせ受付" }),
    ).toBeInTheDocument();
    // 1件もなくても3つの列を出し、どれも「ありません」と示す
    expect(await screen.findAllByText("ありません")).toHaveLength(3);
    expect(
      screen.queryByRole("button", { name: "送信する" }),
    ).not.toBeInTheDocument();
  });

  it("(j) /operator 以外では本人向けの画面が出る", async () => {
    installFakeFetch({});
    render(
      <Root
        subscribeAnswers={new FakeStream().subscribe}
        subscribeOperator={new FakeOperatorStream().subscribe}
      />,
    );

    expect(
      await screen.findByRole("button", { name: "送信する" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "問い合わせ受付" }),
    ).not.toBeInTheDocument();
  });
});
