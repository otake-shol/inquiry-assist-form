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

/**
 * 本人が対象システムを選んで「今すぐ担当者に送る」→ オペレータ画面の一覧に出て、
 * 開くと詳細が見える。
 */
test("今すぐ担当者に送った問い合わせがオペレータ画面に届く", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const utterance = `スモーク確認 ${Date.now()} 掲載期限の延長日数を知りたい`;

  await page.goto("/");
  await page.getByRole("button", { name: "求人管理画面" }).click();
  await page.getByLabel(INPUT_LABEL).fill(utterance);
  await page.getByRole("button", { name: "送信する" }).click();
  await expect(page.getByText(/として受け取りました/)).toBeVisible();
  await page.getByRole("button", { name: "今すぐ担当者に送る" }).click();
  await expect(
    page.getByText(
      "担当者に送りました。書いていただいた内容をそのまま添えています。",
    ),
  ).toBeVisible();

  // オペレータ画面（本人向けの画面からはリンクしない）
  await page.goto("/operator");
  await expect(
    page.getByRole("heading", { name: "問い合わせ受付" }),
  ).toBeVisible();
  const row = page.getByRole("button", { name: new RegExp(utterance) });
  await expect(row).toBeVisible();
  await expect(row).toContainText("求人管理画面");
  await expect(row).toContainText("今すぐ送る");
  await row.click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText(utterance).first()).toBeVisible();
  await expect(detail.getByText("元の発話")).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * Slack の通知に載るリンク（`/operator/{id}`）を直接開くと、
 * オペレータ画面でその問い合わせの詳細が開く。
 */
test("/operator/{id} を直接開くとその問い合わせの詳細が見える", async ({
  page,
  request,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const utterance = `リンク確認 ${Date.now()} 請求書の宛名を知りたい`;

  // 本人が送る
  await page.goto("/");
  await page.getByLabel(INPUT_LABEL).fill(utterance);
  await page.getByRole("button", { name: "送信する" }).click();
  await expect(page.getByText(/として受け取りました/)).toBeVisible();
  await page.getByRole("button", { name: "今すぐ担当者に送る" }).click();
  await expect(page.getByText(/担当者に送りました/)).toBeVisible();

  // オペレータ一覧の API から id を取る
  const response = await request.get("/api/operator/inquiries");
  const list = (await response.json()) as {
    items: { id: string; summary: string }[];
  };
  const id = list.items.find((item) => item.summary.includes(utterance))?.id;
  expect(id).toBeDefined();

  await page.goto(`/operator/${id}`);
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText("元の発話")).toBeVisible();
  await expect(detail.getByText(utterance).first()).toBeVisible();
  await expect(page.getByLabel("受付一覧")).toBeVisible();

  // ここまで（本人の送付と、実在する id の直接表示）ではエラーが1件も出ないこと
  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);

  // 開けない id では、一覧のうえに見つからない旨が出る。
  // 形式は正しいが存在しない id（API は 404）と、途中で切れたリンクのように形式の崩れた id（API は 400）の両方。
  const missingIds = ["00000000-0000-0000-0000-000000000000", "0b9c-cut-off"];
  for (const missing of missingIds) {
    await page.goto(`/operator/${missing}`);
    await expect(
      page.getByText("この問い合わせは見つかりませんでした"),
    ).toBeVisible();
    await expect(page.getByLabel("受付一覧")).toBeVisible();
  }

  // 開けない id への取得は失敗するため、ブラウザがその応答（404 と 400）をコンソールに出す。
  // 許すのはこの2件だけで、それ以外のエラーは失敗にする。
  expect(consoleErrors).toHaveLength(missingIds.length);
  expect(consoleErrors.join("\n")).toMatch(/404/);
  expect(consoleErrors.join("\n")).toMatch(/400/);
  expect(pageErrors).toEqual([]);
});

/**
 * `[一部幽霊]` では事実の一部だけ根拠を確かめられず、
 * 結果の「担当者向けの詳しい内容」に「根拠を確かめられなかったこと」が出る。
 */
test("一部の事実の根拠を確かめられなかったことが詳しい内容に出る", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.goto("/");
  await page
    .getByLabel(INPUT_LABEL)
    .fill("求人の掲載期限は、何日まで延長できますか？ [一部幽霊]");
  await page.getByRole("button", { name: "送信する" }).click();

  const confirm = page.getByRole("button", { name: "合っています" });
  const unknown = page.getByRole("button", { name: "わからない", exact: true });
  for (let i = 0; i < 5; i++) {
    await expect(unknown.or(confirm)).toBeVisible();
    if (await confirm.isVisible()) break;
    await unknown.click();
  }
  await confirm.click();

  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByText("担当者向けの詳しい内容").click();
  await expect(page.getByText("根拠を確かめられなかったこと")).toBeVisible();
  await expect(
    page.getByText("調査で挙がりましたが、資料で裏づけを取れていません。"),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
