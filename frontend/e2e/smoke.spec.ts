import { expect, test, type Page } from "@playwright/test";

const INPUT_LABEL =
  "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。";

/** コンソールとページのエラーを集める。 */
function watchErrors(page: Page): {
  consoleErrors: string[];
  pageErrors: string[];
} {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  return { consoleErrors, pageErrors };
}

/** 最初の画面から「文章で書く」で始める。 */
async function startByText(page: Page, text: string) {
  await page.goto("/");
  await page.getByText("文章で書く", { exact: true }).click();
  await page.getByLabel(INPUT_LABEL).fill(text);
  await page.getByRole("button", { name: "送信する" }).click();
  // 書いた文章が吹き出しとして残るまで待つ（問い合わせが作成された合図）。
  // 待たずに進むと、作成前の画面に出ている1問目のボタンを押しに行ってしまう
  await expect(page.locator(".utterance")).toBeVisible();
  await expect(page).toHaveURL(/\/inquiries\/[0-9a-f-]{36}$/);
}

/**
 * 残りの質問を「わからない」で進め、言い直しの確認まで行って「合っています」を押す。
 * 何問出るかは決め打ちしない。
 */
async function answerUnknownAndConfirm(page: Page) {
  const confirm = page.getByRole("button", { name: "合っています" });
  const unknown = page.getByRole("button", { name: "わからない", exact: true });
  const answered = page.locator(".answered-row");
  for (let i = 0; i < 6; i++) {
    await expect(unknown.or(confirm)).toBeVisible();
    if (await confirm.isVisible()) break;
    // 押した質問が「質問 → 答え」の1行に変わるまで待つ。
    // 待たずに次を見ると、送信中でまだ残っている同じボタンを、もう一度押しに行ってしまう
    const before = await answered.count();
    await unknown.click();
    await expect(answered).not.toHaveCount(before);
  }
  await expect(page.getByText("つまり、こういうことですね。")).toBeVisible();
  await confirm.click();
}

/** 文章で始め、調べた結果を見たうえで「まだ解決していません」で担当者に送る。 */
async function fileByText(page: Page, text: string) {
  await startByText(page, text);
  await answerUnknownAndConfirm(page);
  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole("button", { name: /まだ解決していません/ }).click();
  await expect(page.getByText(/担当者に送りました/)).toBeVisible();
}

/**
 * 押して答える入口を、最初の質問から結果まで通す。
 * 文章を1度も書かずに進められ、問いが選んだ内容から組み立てられる。
 */
test("選択肢を押すだけで、入口から結果まで通せる", async ({ page }) => {
  const { consoleErrors, pageErrors } = watchErrors(page);

  await page.goto("/");
  await expect(page.getByText("モック", { exact: true })).toBeVisible();
  // 入力が揃う前は、担当者に送る道を出さない
  await expect(
    page.getByRole("button", { name: "答えを待たずに担当者に送る" }),
  ).toHaveCount(0);

  await page.getByRole("button", { name: /使い方を知りたい/ }).click();
  await expect(page).toHaveURL(/\/inquiries\/[0-9a-f-]{36}$/);
  await page.getByRole("button", { name: "システムB", exact: true }).click();
  await page.getByRole("button", { name: "機能B-1", exact: true }).click();
  // マスに質問の設定がないので、聞き手の質問が1つ出る
  await page
    .getByRole("button", { name: "画面を開いたとき", exact: true })
    .click();
  await expect(page.getByText("補足があれば書いてください")).toBeVisible();
  await page.getByRole("button", { name: "書かずに進む" }).click();

  // 選んだ内容から、問いが組み立てられる
  await expect(page.getByText("つまり、こういうことですね。")).toBeVisible();
  await expect(
    page.getByText(/システムBの機能B-1について、使い方を知りたい。/),
  ).toBeVisible();
  await page.getByRole("button", { name: "合っています" }).click();

  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  // 問いを確かめた後は、答えを待たずに送る道が出る
  await expect(
    page.getByRole("button", { name: "答えを待たずに担当者に送る" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "解決しました" }).click();
  await expect(
    page.getByText("解決してよかったです。この内容は記録しておきます。"),
  ).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * マスに設定した質問が出ること、補足に書いた文章が担当者に届くこと、
 * 「おかしいかもしれない」は答えの後に自動で担当者に送られること。
 */
test("マスごとの質問に答えて送った問い合わせが、担当者のボードに届く", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `スモーク確認 ${Date.now()}`;

  await page.goto("/");
  await page.getByRole("button", { name: /おかしいかもしれない/ }).click();
  await page.getByRole("button", { name: "システムA", exact: true }).click();
  await page.getByRole("button", { name: "機能A-1", exact: true }).click();
  // システムA の「おかしいかもしれない」に設定した質問
  await expect(page.getByText("いつから起きていますか")).toBeVisible();
  await page.getByRole("button", { name: "今日から", exact: true }).click();
  await page.getByLabel("補足があれば書いてください").fill(mark);
  await page.getByRole("button", { name: "次へ" }).click();
  await page.getByRole("button", { name: "合っています" }).click();
  await expect(
    page.getByText(
      "いまの動きを調べました。この内容を添えて担当者に送りました。",
    ),
  ).toBeVisible({ timeout: 30_000 });

  await page.goto("/operator");
  await expect(
    page.getByRole("heading", { name: "問い合わせ受付" }),
  ).toBeVisible();
  const board = page.getByRole("region", { name: "受付一覧" });
  const card = board.getByRole("button").filter({ hasText: mark });
  await expect(card).toContainText("おかしいかもしれない");
  await expect(card).toContainText("システムA");
  await expect(card).toContainText("機能A-1");
  await card.click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText("入口の質問と答え")).toBeVisible();
  await expect(
    detail.getByText("いつから起きていますか").first(),
  ).toBeVisible();
  await expect(detail.getByText("今日から").first()).toBeVisible();
  await expect(detail.getByText(mark).first()).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 緊急度を「業務が止まっている」にすると、詳しい質問を省き、
 * 状況の一言を書いた時点で担当者に届く。
 */
test("業務が止まっている件は、詳しい質問を省いてすぐ担当者に届く", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `停止確認 ${Date.now()}`;

  await page.goto("/");
  await page.getByRole("button", { name: /おかしいかもしれない/ }).click();
  await page.getByRole("button", { name: "システムA", exact: true }).click();
  // 選ぶと API に送られ、その応答で選択が切り替わる
  const blocked = page.getByRole("radio", { name: "業務が止まっている" });
  await blocked.click();
  await expect(blocked).toBeChecked();
  await page.getByRole("button", { name: "機能A-1", exact: true }).click();
  // 設定した質問（いつから起きていますか）は聞かれず、状況の一言を求められる。書かずには進めない
  await expect(page.getByText("状況を一言で教えてください")).toBeVisible();
  await expect(page.getByText("いつから起きていますか")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "書かずに進む" })).toHaveCount(
    0,
  );
  await page.getByLabel("状況を一言で教えてください").fill(mark);
  await page.getByRole("button", { name: "次へ" }).click();
  await expect(
    page.getByText(
      "担当者にすぐ届けました。書いていただいた内容をそのまま添えています。",
    ),
  ).toBeVisible();

  // 担当者のボードでは、緊急度が分かる
  await page.goto("/operator");
  const card = page
    .getByRole("region", { name: "受付一覧" })
    .getByRole("button")
    .filter({ hasText: mark });
  await expect(card).toContainText("業務が止まっている");

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 文章で書く道: 書いた文章から置き場所が読み取られ、読み取れなかった質問だけが続く。
 */
test("文章で書いて、入口から結果まで通せる", async ({ page }) => {
  const { consoleErrors, pageErrors } = watchErrors(page);

  await startByText(page, "求人の掲載期限は、何日まで延長できますか？");
  // 文章から読み取った答えには、その旨が添えられる
  await expect(
    page.getByText("（文章から読み取りました）").first(),
  ).toBeVisible();
  await answerUnknownAndConfirm(page);

  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("担当者向けの詳しい内容")).toBeVisible();
  await page.getByRole("button", { name: "解決しました" }).click();
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
 * 文章に答えが書かれている設定の質問は、聞かれない。
 * システムA の「おかしいかもしれない」には「いつから起きていますか」が設定されている。
 */
test("文章に答えが書かれている質問は、もう一度聞かれない", async ({ page }) => {
  const { consoleErrors, pageErrors } = watchErrors(page);

  await startByText(
    page,
    "システムAの機能A-1で、今日から一覧の表示がおかしい。",
  );
  // 文章から読み取られ、答え終えた質問として並ぶ
  const answered = page
    .locator(".answered-row")
    .filter({ hasText: "いつから起きていますか" });
  await expect(answered).toContainText("今日から");
  await expect(answered).toContainText("（文章から読み取りました）");
  // 置き場所も設定の質問も文章から読み取れたので、質問は1つも聞かれずに言い直しへ進む
  await expect(page.getByText("つまり、こういうことですね。")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "今日から", exact: true }),
  ).toHaveCount(0);

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
  const { consoleErrors, pageErrors } = watchErrors(page);
  const utterance = `リンク確認 ${Date.now()} 請求書の宛名を知りたい`;

  // 本人が送る
  await fileByText(page, utterance);

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
  const { consoleErrors, pageErrors } = watchErrors(page);
  const marker = `一部確認 ${Date.now()}`;

  await startByText(
    page,
    `求人の掲載期限は、何日まで延長できますか？ ${marker} [一部幽霊]`,
  );
  await answerUnknownAndConfirm(page);

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
  const { consoleErrors, pageErrors } = watchErrors(page);

  await startByText(
    page,
    "求人の掲載期限は、何日まで延長できますか？ [的外れ]",
  );
  await answerUnknownAndConfirm(page);

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
  const { consoleErrors, pageErrors } = watchErrors(page);

  const mark = `往復確認 ${Date.now()}`;
  const reply = `担当者からの返信 ${Date.now()}`;
  const append = `本人からの追記 ${Date.now()}`;

  // 本人が送る。作成後は URL がその問い合わせを指す
  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  await expect(page).toHaveURL(/\/inquiries\/[0-9a-f-]{36}$/);
  const askerUrl = page.url();
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

/**
 * 担当者のボード: カードを別の列へドラッグすると対応状況が変わり、
 * 開き直しても変わったままで、本人のボードでも同じ状態の列に入る。
 */
test("担当者がカードをドラッグして対応状況を変えられる", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const mark = `ボード確認 ${Date.now()}`;

  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  await expect(page.getByText("担当者の確認を待っています")).toBeVisible();

  // 届いた時点では「未対応」の列にある
  await page.goto("/operator");
  const board = page.getByRole("region", { name: "受付一覧" });
  const open = board.getByRole("region", { name: /^未対応/ });
  const inProgress = board.getByRole("region", { name: /^対応中/ });
  const card = board.getByRole("listitem").filter({ hasText: mark });
  await expect(
    open.getByRole("listitem").filter({ hasText: mark }),
  ).toBeVisible();

  // 「対応中」の列へドラッグする
  await card.dragTo(inProgress);
  await expect(
    inProgress.getByRole("listitem").filter({ hasText: mark }),
  ).toBeVisible();
  await expect(
    open.getByRole("listitem").filter({ hasText: mark }),
  ).toHaveCount(0);

  // 開き直しても「対応中」のまま
  await page.reload();
  await expect(
    inProgress.getByRole("listitem").filter({ hasText: mark }),
  ).toBeVisible();

  // 本人のボードでも「担当者が対応中」の列に入る
  await page.goto("/inquiries");
  await expect(
    page
      .getByRole("region", { name: /^担当者が対応中/ })
      .getByRole("listitem")
      .filter({ hasText: mark }),
  ).toBeVisible();

  expect(pageErrors).toEqual([]);
});

/**
 * スマホの幅では、ボードの列は横にスクロールして見るが、ページ全体は横にはみ出さない。
 * （視覚的に隠した文字の位置の基準が列の外にあり、ページ全体が横に広がったことがある）
 */
test("スマホの幅でボードがページの横幅からはみ出さない", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  for (const path of ["/operator", "/inquiries"]) {
    await page.goto(path);
    await expect(page.getByRole("region").first()).toBeVisible();
    // e2e の型の設定はブラウザの型（DOM）を含まないため、式を文字列で渡す
    const overflow = await page.evaluate<number>(
      "document.documentElement.scrollWidth - window.innerWidth",
    );
    expect(overflow, `${path} の横のはみ出し`).toBeLessThanOrEqual(0);
  }
});
