import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.tsx";
import { LANGUAGE_STORAGE_KEY } from "./language.ts";
import {
  FakeOperatorStream,
  FakeStream,
  installFakeFetch,
  makeConfig,
} from "../test/fakes.ts";

// 同梱の `overrides.ts` は空のまま。このファイルの中だけ、上書きを差し込む。
vi.mock("./overrides.ts", () => ({
  overrides: {
    ja: {
      text: { appTitle: "社内ヘルプ" },
      op: { title: "社内ヘルプ受付", tabTitle: "社内ヘルプ受付" },
    },
    en: { text: { appTitle: "Internal Help" } },
  },
}));

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
  document.title = "";
});

describe("上書きを与えたときの画面", () => {
  it("本人向けの画面の製品の名前と、タブの題名が、上書きした名前になる。上書きしていない文言は元のまま", async () => {
    installFakeFetch({ "GET /api/config": { body: makeConfig() } });
    render(<Root subscribeAnswers={new FakeStream().subscribe} />);

    expect(
      await screen.findByRole("link", { name: "社内ヘルプ" }),
    ).toBeInTheDocument();
    expect(document.title).toBe("社内ヘルプ");
    expect(
      screen.queryByRole("link", { name: "問い合わせアシスト" }),
    ).toBeNull();
    expect(
      await screen.findByRole("group", { name: "何をしたいですか" }),
    ).toBeInTheDocument();
  });

  it("担当者の画面の題名も変わる", async () => {
    window.history.replaceState({}, "", "/operator");
    installFakeFetch({
      "GET /api/operator/inquiries": { body: { unreadCount: 0, items: [] } },
    });
    render(<Root subscribeOperator={new FakeOperatorStream().subscribe} />);

    expect(
      await screen.findByRole("heading", { name: "社内ヘルプ受付" }),
    ).toBeInTheDocument();
    expect(document.title).toBe("社内ヘルプ受付");
  });

  it("英語の画面には、英語の上書きだけが効き、日本語の上書きは混ざらない", async () => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, "en");
    installFakeFetch({ "GET /api/config": { body: makeConfig() } });
    render(<Root subscribeAnswers={new FakeStream().subscribe} />);

    expect(
      await screen.findByRole("link", { name: "Internal Help" }),
    ).toBeInTheDocument();
    expect(document.title).toBe("Internal Help");
    expect(screen.queryByText("社内ヘルプ")).toBeNull();
  });
});
