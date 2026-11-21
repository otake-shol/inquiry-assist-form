import { defineConfig, devices } from "@playwright/test";

/**
 * UI スモークの設定。実際の API（モックプロファイル）と Vite を両方起動する。
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
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "bash ../scripts/gradle.sh bootRun --console=plain",
      url: "http://localhost:8080/api/config",
      env: { SPRING_PROFILES_ACTIVE: "mock" },
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
