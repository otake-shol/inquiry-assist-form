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
  await page.getByRole("button", { name: "システムA" }).click();
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
  await expect(row).toContainText("システムA");
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
 * `[一部幽霊]` では事実の一部だけ根拠を確かめられない。
 * その内容は担当者にだけ見せる: 本人の画面には出ず、オペレータ画面には出る。
 */
test("根拠を確かめられなかったことは本人には見せず担当者にだけ見せる", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const marker = `一部確認 ${Date.now()}`;

  await page.goto("/");
  await page
    .getByLabel(INPUT_LABEL)
    .fill(`求人の掲載期限は、何日まで延長できますか？ ${marker} [一部幽霊]`);
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
  // 本人の画面: 詳しい内容を開いても、確かめられなかったことは見えない
  await page.getByText("担当者向けの詳しい内容").click();
  await expect(page.getByText("分かったこと")).toBeVisible();
  await expect(page.getByText("根拠を確かめられなかったこと")).toHaveCount(0);

  // 解決しないで担当者に送る
  await page.getByRole("button", { name: /まだ解決していません/ }).click();
  await expect(page.getByText(/担当者に送りました/)).toBeVisible();
  await expect(page.getByText("根拠を確かめられなかったこと")).toHaveCount(0);

  // オペレータ画面: 見える
  await page.goto("/operator");
  await page.getByRole("button", { name: new RegExp(marker) }).click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText("根拠を確かめられなかったこと")).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * `[的外れ]` では説明が「問いに答えていない」と判定する。
 * 本人の画面では、答えを確認できなかったと先に示し、説明は「関連して分かったこと」として出す。
 */
test("問いに答えていない結果は関連して分かったこととして示す", async ({
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
    .fill("求人の掲載期限は、何日まで延長できますか？ [的外れ]");
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
  await expect(
    page.getByText("ご質問への答えは、手元の資料では確認できませんでした。"),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "関連して分かったこと" }),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 送った後の往復: 本人が送る → 担当者が返信する → 本人が開き直して読み、追記する →
 * 担当者に未読として戻り、完了にする → 本人の画面に完了が出る。
 */
test("送った後に担当者と本人がやり取りでき、開き直しても続きが見える", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));

  const mark = `往復確認 ${Date.now()}`;
  const reply = `担当者からの返信 ${Date.now()}`;
  const append = `本人からの追記 ${Date.now()}`;

  // 本人が送る。作成後は URL がその問い合わせを指す
  await page.goto("/");
  await page.getByLabel(INPUT_LABEL).fill(`${mark} 請求書の宛名を知りたい`);
  await page.getByRole("button", { name: "送信する" }).click();
  await expect(page.getByText("として受け取りました")).toBeVisible();
  await expect(page).toHaveURL(/\/inquiries\/[0-9a-f-]{36}$/);
  const askerUrl = page.url();
  await page.getByRole("button", { name: "今すぐ担当者に送る" }).click();
  await expect(page.getByText("担当者の確認を待っています")).toBeVisible();
  await expect(
    page.getByText("担当者からの返信は、ここに表示されます。"),
  ).toBeVisible();

  // 担当者が開いて返信する。未対応は自動で対応中になる
  await page.goto("/operator");
  const list = page.getByRole("region", { name: "受付一覧" });
  await list.getByRole("button").filter({ hasText: mark }).click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  const statusSwitch = detail.getByRole("group", {
    name: "対応状況を切り替える",
  });
  await expect(
    statusSwitch.getByRole("button", { name: "未対応" }),
  ).toHaveAttribute("aria-pressed", "true");
  await detail.getByLabel("本人に返信する").fill(reply);
  await detail.getByRole("button", { name: "返信を送る" }).click();
  await expect(detail.getByText(reply)).toBeVisible();
  await expect(
    statusSwitch.getByRole("button", { name: "対応中" }),
  ).toHaveAttribute("aria-pressed", "true");

  // 本人の一覧に未読の返信の印がつき、開くと返信が読める
  await page.goto("/inquiries");
  const mine = page.getByRole("listitem").filter({ hasText: mark });
  await expect(mine).toContainText("担当者が対応中");
  await expect(mine).toContainText("未読の返信");
  await page.goto(askerUrl);
  await expect(page.getByText("担当者が対応しています")).toBeVisible();
  await expect(page.getByText(reply)).toBeVisible();

  // 本人が追記する
  await page.getByLabel("追加で伝える").fill(append);
  await page.getByRole("button", { name: "送る", exact: true }).click();
  await expect(page.getByText(append)).toBeVisible();

  // 担当者に追記が届き、完了にする
  await page.goto("/operator");
  await list.getByRole("button").filter({ hasText: mark }).click();
  await expect(detail.getByText(append)).toBeVisible();
  await statusSwitch.getByRole("button", { name: "完了" }).click();
  await expect(
    statusSwitch.getByRole("button", { name: "完了" }),
  ).toHaveAttribute("aria-pressed", "true");

  // 本人の画面に完了が出る。裏側の表示も開ける
  await page.goto(askerUrl);
  await expect(page.getByText("対応が完了しました")).toBeVisible();
  await page.getByText("裏側を見る（開発者向け）").click();
  await expect(page.getByText("照合（コード）")).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});
