import { expect, test } from "@playwright/test";

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";

/**
 * 実際の API（モック）につないで正常系を最後まで通し、
 * コンソールエラーとページエラーが出ないことを確認する。
 */
test("仕様確認の正常系を最後まで通せる", async ({ page }) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("/");
  await expect(page.getByText("モック", { exact: true })).toBeVisible();

  // 入力
  await page
    .getByLabel(INPUT_LABEL)
    .fill("求人の掲載期限は、何日まで延長できますか？");
  await page.getByRole("button", { name: "送信する" }).click();
  await expect(
    page.getByText("仕様の確認・相談として受け取りました"),
  ).toBeVisible();

  // 追加質問（1問ずつ「わからない」で進める。数は決め打ちしない）
  const confirm = page.getByRole("button", { name: "合っています" });
  const unknown = page.getByRole("button", { name: "わからない", exact: true });
  for (let i = 0; i < 5; i++) {
    await expect(unknown.or(confirm)).toBeVisible();
    if (await confirm.isVisible()) break;
    await unknown.click();
  }

  // 言い直し
  await expect(page.getByText("つまり、こういうことですね。")).toBeVisible();
  await confirm.click();

  // 調べた結果
  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("担当者向けの詳しい内容")).toBeVisible();
  await page.getByRole("button", { name: "解決しました" }).click();

  // 終わり
  await expect(
    page.getByText("解決してよかったです。この内容は記録しておきます。"),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "新しい問い合わせを書く" }),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
