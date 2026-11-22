import { defineConfig, devices } from "@playwright/test";

/**
 * UI スモークの設定。実際の API（スキルは、すべてモック）と Vite を両方起動する。
 * API は `../backend` の bootRun、画面は Vite の開発サーバー。
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      // Playwright の既定のブラウザの言語は英語。画面は最初の表示をブラウザの言語に
      // 合わせるため、日本語に固定する（英語の確認は、テストの中で切り替える）
      use: { ...devices["Desktop Chrome"], locale: "ja-JP" },
    },
  ],
  webServer: [
    {
      command: "bash ../scripts/gradle.sh bootRun --console=plain",
      url: "http://localhost:8080/api/config",
      env: {
        // 調査の時間切れ（既定60秒）を、待たずに確かめるため。モックの [遅延] の調査は6秒かかる
        INQUIRY_RULES_ANSWER_TIMEOUT: "3s",
      },
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
    {
      command: "npm run dev -- --port 5173 --strictPort",
      url: "http://localhost:5173",
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});
