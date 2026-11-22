import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";

// テストの既定の言語は日本語にする（jsdom のブラウザの言語は en-US のため）
beforeEach(() => {
  window.localStorage.clear();
  vi.spyOn(window.navigator, "languages", "get").mockReturnValue([
    "ja-JP",
    "ja",
  ]);
  vi.spyOn(window.navigator, "language", "get").mockReturnValue("ja-JP");
});

afterEach(() => {
  cleanup();
  document.documentElement.lang = "";
});
