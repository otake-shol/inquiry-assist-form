import { expect, test } from "@playwright/test";

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
    .getByLabel("困りごと")
    .fill("求人の掲載期限は、何日まで延長できますか？");
  await page.getByRole("button", { name: "送信する" }).click();

  // 追加質問（全問「わからない」で進める）
  await expect(page.getByText("仕様の確認・相談として扱います")).toBeVisible();
  const unknowns = page.getByLabel("わからない");
  const count = await unknowns.count();
  for (let i = 0; i < count; i++) {
    await unknowns.nth(i).check();
  }
  await page.getByRole("button", { name: /次へ進む/ }).click();

  // 整形結果の確認
  await expect(page.getByLabel("やりたいこと（必須）")).not.toHaveValue("");
  await page.getByRole("button", { name: "この内容で進める" }).click();

  // 0次回答
  await expect(page.getByRole("region", { name: "0次回答" })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("技術的な詳細（担当者向けメモ）")).toBeVisible();
  await page.getByRole("button", { name: "解決した" }).click();

  // 完了
  await expect(page.getByText("解決として記録しました")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "新しい問い合わせを書く" }),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
