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
  // 担当者に渡しても、問い合わせは終わらない。担当者の番になる
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
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
  // 「使い方を知りたい」には、説明に加えて、自分で行える手順を番号つきで示す
  await expect(page.getByRole("heading", { name: "手順" })).toBeVisible();
  await expect(
    page.getByText("求人管理画面で、対象の求人の編集を開く"),
  ).toBeVisible();
  await expect(
    page.getByText(/確かめ方: 応募者向けの一覧に、求人が表示される/),
  ).toBeVisible();
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
 * AI が答えられなかった件は、本人に選ばせずに、担当者へ引き継ぐ。
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
  // 送るかどうかを選ばせない。引き継いだことと、その理由を伝える
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
  await expect(
    page.getByText(
      /はっきりした答えが見つかりませんでした。担当者に引き継ぎました/,
    ),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "解決しました" })).toHaveCount(
    0,
  );

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 担当者と本人が、番を受け渡しながら解決まで進める。
 * 聞き返す → 本人が答える → 結果を伝える → 本人が解決していないと答える →
 * もう一度結果を伝える → 本人が解決を伝えて終わる。
 */
test("担当者と本人が番を受け渡しながら、解決まで進められる", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `往復確認 ${Date.now()}`;
  const question = `担当者からの質問 ${Date.now()}`;
  const reply = `本人の返事 ${Date.now()}`;
  const result = `担当者からの結果 ${Date.now()}`;

  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  const askerUrl = page.url();

  const board = page.getByRole("region", { name: "受付一覧" });
  const operatorTurn = board.getByRole("region", { name: /^担当者の番/ });
  const askerTurn = board.getByRole("region", { name: /^本人の番/ });
  const closed = board.getByRole("region", { name: /^終わり/ });
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  const cardIn = (column: typeof operatorTurn) =>
    column.getByRole("listitem").filter({ hasText: mark });

  // 担当者が聞き返す。本人の番になる
  await page.goto("/operator");
  await cardIn(operatorTurn).getByRole("button").click();
  await detail.getByLabel("本人に送る").fill(question);
  await detail.getByRole("button", { name: "聞き返す" }).click();
  await expect(cardIn(askerTurn)).toBeVisible();
  await expect(cardIn(operatorTurn)).toHaveCount(0);
  // 本人の番の間は、担当者は送れない
  await expect(detail.getByRole("button", { name: "聞き返す" })).toHaveCount(0);

  // 本人のボードでは「あなたの番」に入り、質問が届いたことが分かる
  await page.goto("/inquiries");
  const mine = page
    .getByRole("region", { name: /^あなたの番/ })
    .getByRole("listitem")
    .filter({ hasText: mark });
  await expect(mine).toContainText("担当者から質問が届いています");
  await page.goto(askerUrl);
  await expect(page.getByText(question)).toBeVisible();
  await page.getByLabel("担当者の質問に答える").fill(reply);
  await page.getByRole("button", { name: "送る", exact: true }).click();
  await expect(page.getByText("担当者が確認しています")).toBeVisible();

  // 番が担当者に戻る。担当者が結果を伝える
  await page.goto("/operator");
  await cardIn(operatorTurn).getByRole("button").click();
  await expect(detail.getByText(reply)).toBeVisible();
  await detail.getByLabel("本人に送る").fill(result);
  await detail.getByRole("button", { name: "結果を伝える" }).click();
  await expect(cardIn(askerTurn)).toBeVisible();

  // 本人が結果を確かめる。解決していないと答えると、担当者の番に戻る
  await page.goto(askerUrl);
  await expect(page.getByText("担当者から結果が届いています")).toBeVisible();
  await expect(page.getByText(result)).toBeVisible();
  await page.getByRole("button", { name: "まだ解決していません" }).click();
  await page
    .getByLabel("どこが解決していませんか（任意）")
    .fill("宛名の変え方が分かりません");
  await page.getByRole("button", { name: "担当者に送る" }).click();
  await expect(page.getByText("担当者が確認しています")).toBeVisible();

  // 担当者がもう一度結果を伝え、本人が解決を伝えて終わる
  await page.goto("/operator");
  await cardIn(operatorTurn).getByRole("button").click();
  await expect(detail.getByText("宛名の変え方が分かりません")).toBeVisible();
  await detail.getByLabel("本人に送る").fill(`${result} その2`);
  await detail.getByRole("button", { name: "結果を伝える" }).click();
  await expect(cardIn(askerTurn)).toBeVisible();

  await page.goto(askerUrl);
  await page.getByRole("button", { name: "解決しました" }).click();
  await expect(
    page.getByText("解決してよかったです。この内容は記録しておきます。"),
  ).toBeVisible();

  // 担当者のボードでは「終わり」に入り、終わり方が分かる。状態の履歴も開ける
  await page.goto("/operator");
  await expect(cardIn(closed)).toContainText("解決済み");
  await cardIn(closed).getByRole("button").click();
  await detail.getByText("状態の履歴").click();
  await detail.getByText("裏側を見る（開発者向け）").click();
  await expect(detail.getByText("照合（コード）")).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 担当者のボード: カードを別の列に落としても状態は変わらず、
 * パネルが開いて、その列に対応する入力欄に移る。見送ると「終わり」に入る。
 */
test("カードを落とすと対応する入力欄が開き、見送りで終えられる", async ({
  page,
}) => {
  const { pageErrors } = watchErrors(page);
  const mark = `ボード確認 ${Date.now()}`;
  const reason = `見送りの理由 ${Date.now()}`;

  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  const askerUrl = page.url();

  await page.goto("/operator");
  const board = page.getByRole("region", { name: "受付一覧" });
  const operatorTurn = board.getByRole("region", { name: /^担当者の番/ });
  const askerTurn = board.getByRole("region", { name: /^本人の番/ });
  const closed = board.getByRole("region", { name: /^終わり/ });
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  const card = board.getByRole("listitem").filter({ hasText: mark });
  await expect(
    operatorTurn.getByRole("listitem").filter({ hasText: mark }),
  ).toBeVisible();

  // 「本人の番」に落とす: 本人へのメッセージが要るので、状態は変わらず、入力欄に移る
  await card.dragTo(askerTurn);
  await expect(detail.getByLabel("本人に送る")).toBeFocused();
  await expect(
    operatorTurn.getByRole("listitem").filter({ hasText: mark }),
  ).toBeVisible();

  // 「終わり」に落とす: 見送りの入力欄が開く。理由を書いて終える
  await card.dragTo(closed);
  await detail.getByLabel("理由", { exact: true }).fill(reason);
  await detail.getByRole("button", { name: "見送りとして終える" }).click();
  await expect(
    closed.getByRole("listitem").filter({ hasText: mark }),
  ).toContainText("見送り");

  // 本人のページに、見送りの連絡と理由が出る
  await page.goto(askerUrl);
  await expect(
    page.getByText("担当者から、今回は対応を見送るとの連絡がありました。"),
  ).toBeVisible();
  await expect(page.getByText(reason).first()).toBeVisible();

  expect(pageErrors).toEqual([]);
});

/**
 * 本人は、終わっていない問い合わせをいつでも取り下げられる。
 */
test("本人が問い合わせを取り下げられる", async ({ page }) => {
  const { consoleErrors, pageErrors } = watchErrors(page);

  await page.goto("/");
  await page.getByRole("button", { name: /仕組みを知りたい/ }).click();
  await expect(page).toHaveURL(/\/inquiries\/[0-9a-f-]{36}$/);
  await page
    .getByRole("button", { name: "この問い合わせを取り下げる" })
    .click();
  // その場に確認が出る。やめると何も起きない
  await expect(
    page.getByText("取り下げますか。取り下げた後は、続きを送れません。"),
  ).toBeVisible();
  await page.getByRole("button", { name: "やめる" }).click();
  await expect(page.getByText("どのシステムですか")).toBeVisible();

  await page
    .getByRole("button", { name: "この問い合わせを取り下げる" })
    .click();
  await page.getByRole("button", { name: "取り下げる", exact: true }).click();
  await expect(
    page.getByText("この問い合わせは取り下げました。"),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "この問い合わせを取り下げる" }),
  ).toHaveCount(0);

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 英語に切り替えて、入口から結果まで通せる。
 * 画面の文言と設定の名前は見る人の言語で出て、AI が書いたものは本人の言語（英語）で書かれ、
 * 根拠の引用は資料の原文のまま出る。日本語に戻すと、名前だけが日本語に戻る。
 */
test("英語に切り替えて通せ、書かれた内容は言語を戻しても変わらない", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const note = `The list shows an expired posting ${Date.now()}`;

  await page.goto("/");
  await page.getByRole("button", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page).toHaveTitle("Inquiry Assist");

  await expect(page.getByText("What can we help you with?")).toBeVisible();
  await page.getByRole("button", { name: /Something looks wrong/ }).click();
  await page.getByRole("button", { name: "System A", exact: true }).click();
  await page.getByRole("button", { name: "Function A-1", exact: true }).click();
  await expect(
    page.getByText("Since when has this been happening?"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Since today", exact: true }).click();
  await page.getByRole("textbox").first().fill(note);
  await page.getByRole("button", { name: "Next" }).click();

  // 言い直しは、本人の言語（英語）で書かれる
  await expect(page.getByText("Here is what I understood.")).toBeVisible();
  const restatement = page
    .getByText(/About Function A-1 in System A: Something may be behaving/)
    .first();
  await expect(restatement).toBeVisible();
  await page.getByRole("button", { name: "That's right" }).click();
  await expect(
    page.getByText("The support team is looking into it"),
  ).toBeVisible();

  // 説明は英語、根拠の引用は資料の原文（日本語）のまま
  await expect(
    page.getByText(/A job posting is treated as expired/).first(),
  ).toBeVisible();
  await page.getByText(/^Sources \(2\)/).click();
  await expect(
    page.locator('[lang="ja"]').filter({
      hasText: "掲載期限を過ぎた求人は応募者向けの一覧に表示されません。",
    }),
  ).toBeVisible();
  const askerUrl = page.url();

  // 開き直しても、選んだ言語のまま
  await page.reload();
  await expect(
    page.getByText("The support team is looking into it"),
  ).toBeVisible();

  // 日本語に戻すと、画面の文言と設定の名前は日本語に、書かれた内容は英語のまま
  await page.getByRole("button", { name: "日本語" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "ja");
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
  const answered = page.locator(".answered-row");
  await expect(
    answered.filter({ hasText: "おかしいかもしれない" }),
  ).toBeVisible();
  await expect(answered.filter({ hasText: "今日から" })).toBeVisible();
  await expect(restatement).toBeVisible();

  // 担当者には、本人の言語が画面の言語と違うことが分かる
  await page.goto(askerUrl.replace("/inquiries/", "/operator/"));
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText("本人の言語: English")).toBeVisible();
  // 訳を頼める。訳は原文に添えるもので、原文は残る（モックの訳は、原文に印を付けたもの）
  await detail.getByRole("button", { name: "訳を見る" }).click();
  await expect(detail.getByText("訳（AI）").first()).toBeVisible();
  await expect(detail.getByText(/【訳の見本】/).first()).toBeVisible();
  await detail.getByRole("button", { name: "訳を隠す" }).click();
  await expect(detail.getByText("訳（AI）")).toHaveCount(0);
  // 画面を本人の言語に合わせると、訳は要らないので、頼む口も出ない
  await page.getByRole("button", { name: "English" }).click();
  await expect(
    page.getByRole("region", { name: /^Staff's turn/ }),
  ).toBeVisible();
  await expect(page.getByText(/Requester's language/)).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Show translation" }),
  ).toHaveCount(0);

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * スマホの幅では、ボードの列は横にスクロールして見るが、ページ全体は横にはみ出さない。
 * （視覚的に隠した文字の位置の基準が列の外にあり、ページ全体が横に広がったことがある）
 */
/**
 * 返事がないとき: AI の答えを確かめないまま期間が過ぎると、1度知らせたうえで「回答済み」で終わる。
 * 終わった後に続きを送ると、担当者に渡る。期間が過ぎるのは待てないので、開発用の口で時刻を指定して判定する。
 */
test("返事がないまま期間が過ぎると知らせたうえで回答済みで終わり、続きを送ると担当者に渡る", async ({
  page,
  request,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `返事なし確認 ${Date.now()}`;
  const more = `やはり解決していません ${Date.now()}`;
  const day = 24 * 60 * 60 * 1000;
  /** いまから `days` 日後を現在として、期間の決まりを判定する。 */
  const runRulesAfter = async (days: number) => {
    const response = await request.post(
      "http://localhost:8080/api/dev/rules/run",
      { data: { now: new Date(Date.now() + days * day).toISOString() } },
    );
    expect(response.ok()).toBe(true);
    return (await response.json()) as { reminded: number; closed: number };
  };

  await startByText(page, `${mark} 請求書の宛名を知りたい`);
  await answerUnknownAndConfirm(page);
  await expect(page.getByRole("heading", { name: "調べた結果" })).toBeVisible({
    timeout: 30_000,
  });
  const askerUrl = page.url();
  // 知らせる前は、何も出さない
  await expect(page.getByText(/お返事がないため/)).toHaveCount(0);

  // 5営業日は、暦では長くても7日。8日後なら必ず過ぎている
  expect((await runRulesAfter(8)).reminded).toBeGreaterThanOrEqual(1);
  await page.goto("/inquiries");
  const mine = page
    .getByRole("region", { name: /^あなたの番/ })
    .getByRole("listitem")
    .filter({ hasText: mark });
  await expect(mine).toBeVisible();
  await page.goto(askerUrl);
  await expect(
    page.getByText(/お返事がないため、.+に「回答済み」として終了します/),
  ).toBeVisible();
  // 知らせた直後は、まだ終わらない（知らせた時刻から、もう一度期間を数える）
  await expect(
    page.getByRole("button", { name: "解決しました" }),
  ).toBeVisible();

  // 知らせた時刻（8日後）から、さらに8日後
  expect((await runRulesAfter(16)).closed).toBeGreaterThanOrEqual(1);
  await page.goto(askerUrl);
  await expect(
    page.getByText(/お返事がなかったため、いったん終了しました/),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "解決しました" })).toHaveCount(
    0,
  );

  // 続きを送ると、担当者に初めて渡る
  await page.getByLabel("追加で伝える").fill(more);
  await page.getByRole("button", { name: "送る", exact: true }).click();
  await expect(page.getByText("担当者が確認しています")).toBeVisible();

  await page.goto("/operator");
  const card = page
    .getByRole("region", { name: "受付一覧" })
    .getByRole("region", { name: /^担当者の番/ })
    .getByRole("listitem")
    .filter({ hasText: mark });
  await expect(card).toBeVisible();
  await card.getByRole("button").click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(detail.getByText(more)).toBeVisible();
  await detail.getByText("状態の履歴").click();
  await expect(
    detail.getByText("返事がないことを本人に知らせた"),
  ).toBeVisible();
  await expect(detail.getByText("返事がないまま終わった")).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 返事がないとき: 担当者の聞き返しに答えないまま期間が過ぎると、1度知らせたうえで「取り下げ」で終わる。
 * 時間で取り下げになった件は、続きを送ると開き直せる。
 */
test("聞き返しに返事がないまま期間が過ぎると取り下げで終わり、続きを送ると開き直せる", async ({
  page,
  request,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `聞き返し放置 ${Date.now()}`;
  const question = `担当者からの質問 ${Date.now()}`;
  const late = `遅くなりましたが答えます ${Date.now()}`;
  const day = 24 * 60 * 60 * 1000;
  const runRulesAfter = async (days: number) => {
    const response = await request.post(
      "http://localhost:8080/api/dev/rules/run",
      { data: { now: new Date(Date.now() + days * day).toISOString() } },
    );
    expect(response.ok()).toBe(true);
  };

  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  const askerUrl = page.url();

  const board = page.getByRole("region", { name: "受付一覧" });
  const cardIn = (name: RegExp) =>
    board
      .getByRole("region", { name })
      .getByRole("listitem")
      .filter({ hasText: mark });
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });

  await page.goto("/operator");
  await cardIn(/^担当者の番/)
    .getByRole("button")
    .click();
  await detail.getByLabel("本人に送る").fill(question);
  await detail.getByRole("button", { name: "聞き返す" }).click();
  await expect(cardIn(/^本人の番/)).toBeVisible();

  // 知らせる
  await runRulesAfter(8);
  await page.goto(askerUrl);
  await expect(
    page.getByText(/お返事がないため、.+に「取り下げ」として終了します/),
  ).toBeVisible();
  await expect(page.getByLabel("担当者の質問に答える")).toBeVisible();

  // 終える。担当者のボードでは「終わり」に入る
  await runRulesAfter(16);
  await page.goto("/operator");
  await expect(cardIn(/^終わり/)).toContainText("取り下げ");

  // 本人は、続きを送って開き直せる（自分で取り下げた件とは違う）
  await page.goto(askerUrl);
  await expect(
    page.getByText(/お返事がなかったため、取り下げとして終了しました/),
  ).toBeVisible();
  await page.getByLabel("追加で伝える").fill(late);
  await page.getByRole("button", { name: "送る", exact: true }).click();
  await expect(page.getByText("担当者が確認しています")).toBeVisible();

  await page.goto("/operator");
  await expect(cardIn(/^担当者の番/)).toBeVisible();

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 調査が時間内に終わらないと、打ち切らずに担当者へ引き継ぎ、調査は裏で続ける。
 * 結果は担当者だけに届き、本人のページには出ない。担当者は、調べ直しを頼める。
 *
 * `[遅延]` の調査は6秒かかる。スモークの API は、引き継ぐまでの時間を3秒にして起動する
 * （`playwright.config.ts` の `INQUIRY_RULES_ANSWER_TIMEOUT`）。手元で起動済みの API を使い回す場合も、
 * 同じ環境変数を付けて起動しておく。
 */
test("調査が時間切れになると担当者に引き継ぎ、裏で調べた結果は担当者だけに届く", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `時間切れ確認 ${Date.now()}`;

  await startByText(page, `${mark} 請求書の宛名を知りたい [遅延]`);
  await answerUnknownAndConfirm(page);

  // 失敗ではなく、引き継ぎとして伝える。調べた結果は出さない
  await expect(
    page.getByText(/調べるのに時間がかかっているため、担当者に引き継ぎました/),
  ).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
  await expect(page.getByRole("heading", { name: "調べた結果" })).toHaveCount(
    0,
  );
  const askerUrl = page.url();

  // 担当者のパネル: 渡った理由が分かり、裏での調査が終わると、開いたままのパネルに結果が出る
  await page.goto("/operator");
  await page
    .getByRole("region", { name: "受付一覧" })
    .getByRole("region", { name: /^担当者の番/ })
    .getByRole("listitem")
    .filter({ hasText: mark })
    .getByRole("button")
    .click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });
  await expect(
    detail.getByText(/AI の調査が、時間内に終わらなかった/),
  ).toBeVisible();
  await expect(
    detail.getByText("この結果は、本人には見せていません。"),
  ).toBeVisible({ timeout: 30_000 });
  await expect(detail.getByText("分かったこと")).toBeVisible();

  // 担当者は、調べ直しを頼める。番は変わらない
  await detail.getByRole("button", { name: "調べ直す" }).click();
  await expect(
    detail.getByText("この結果は、本人には見せていません。"),
  ).toBeVisible({ timeout: 30_000 });
  await expect(detail.getByLabel("本人に送る")).toBeVisible();

  // 本人のページには、裏で調べた結果を出さない
  await page.goto(askerUrl);
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
  await expect(page.getByRole("heading", { name: "調べた結果" })).toHaveCount(
    0,
  );

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 担当者に渡った件には、AI が、確かめられた事実から判断の案を添える（事実と区別して示す）。
 * 担当者は、返信の下書きを AI に作らせ、確かめてから自分で送る。AI が直接送ることはない。
 */
test("担当者のパネルに AI の案が出て、下書きを確かめてから結果を伝えられる", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `案と下書き ${Date.now()}`;

  await fileByText(page, `${mark} 請求書の宛名を知りたい`);
  const askerUrl = page.url();
  // 案は担当者だけに見せる
  await expect(page.getByText("AI の案")).toHaveCount(0);

  await page.goto("/operator");
  const board = page.getByRole("region", { name: "受付一覧" });
  const cardIn = (name: RegExp) =>
    board
      .getByRole("region", { name })
      .getByRole("listitem")
      .filter({ hasText: mark });
  await cardIn(/^担当者の番/)
    .getByRole("button")
    .click();
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });

  // 案は裏で作られ、開いたままのパネルに出る。基づく事実が並ぶ
  await expect(detail.getByText("判断の案")).toBeVisible({ timeout: 30_000 });
  await expect(detail.getByText(/決めるのは担当者です/)).toBeVisible();
  await expect(detail.getByText("基づく事実").first()).toBeVisible();
  await expect(detail.getByText("相談は要らなそうです。")).toBeVisible();

  // 下書きは入力欄に入るだけで、送られない
  const box = detail.getByLabel("本人に送る");
  await detail.getByRole("button", { name: "結果を下書きさせる" }).click();
  await expect(box).toHaveValue(
    /お待たせしました。確認した結果をお伝えします。/,
  );
  await expect(
    detail.getByText(/AI の下書きです。確かめて、直してから送ってください/),
  ).toBeVisible();
  await expect(cardIn(/^担当者の番/)).toBeVisible();
  // 書きかけの文章があるときは、下書きで上書きできない
  await expect(
    detail.getByRole("button", { name: "聞き返しを下書きさせる" }),
  ).toBeDisabled();

  // 担当者が直してから、自分で送る
  const edited = `${await box.inputValue()} 宛名は、取引先の設定から変えられます。`;
  await box.fill(edited);
  await detail.getByRole("button", { name: "結果を伝える" }).click();
  await expect(cardIn(/^本人の番/)).toBeVisible();

  await page.goto(askerUrl);
  await expect(page.getByText("担当者から結果が届いています")).toBeVisible();
  await expect(page.getByText(edited)).toBeVisible();
  await expect(page.getByText("AI の案")).toHaveCount(0);

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

/**
 * 新しい決定が要る件は、担当者が仕様の持ち主に相談する。相談している間は持ち主の番で、
 * 持ち主が材料を読んで決めると、決定が残り、担当者の番に戻る。本人に伝えるのは担当者。
 */
test("担当者が仕様の持ち主に相談し、決定が残って担当者の番に戻る", async ({
  page,
}) => {
  const { consoleErrors, pageErrors } = watchErrors(page);
  const mark = `相談確認 ${Date.now()}`;
  const ask = `この動きは意図した仕様でしょうか ${Date.now()}`;
  const decision = `意図した仕様です ${Date.now()}`;

  await fileByText(page, `${mark} システムAの機能A-1の使い方が分かりません。`);
  const askerUrl = page.url();

  const board = page.getByRole("region", { name: "受付一覧" });
  const cardIn = (name: RegExp) =>
    board
      .getByRole("region", { name })
      .getByRole("listitem")
      .filter({ hasText: mark });
  const detail = page.getByRole("region", { name: "問い合わせの詳細" });

  // 担当者が相談する。既定の相談先が選ばれている
  await page.goto("/operator");
  await cardIn(/^担当者の番/)
    .getByRole("button")
    .click();
  await detail.getByText("相談する", { exact: true }).click();
  await expect(
    detail.getByRole("checkbox", { name: "仕様の持ち主（開発用）" }),
  ).toBeChecked();
  await detail.getByLabel("相談したいこと").fill(ask);
  await detail.getByRole("button", { name: "仕様の持ち主に相談する" }).click();
  await expect(cardIn(/^相談中/)).toBeVisible();
  await expect(cardIn(/^相談中/)).toContainText("仕様の持ち主（開発用）");
  await expect(
    detail.getByText(/仕様の持ち主の決定を待っています/),
  ).toBeVisible();
  // 相談している間は、担当者は本人に送れない
  await expect(
    detail.getByRole("button", { name: "結果を伝える" }),
  ).toHaveCount(0);

  // 本人には、なぜ待っているかだけが見える（相談の中身は見せない）
  await page.goto(askerUrl);
  await expect(
    page.getByText("担当者が、仕様を決める人に確認しています"),
  ).toBeVisible();
  await expect(page.getByText(ask)).toHaveCount(0);

  // 仕様の持ち主が、材料を読んで決める
  await page.goto("/owner");
  await expect(
    page.getByRole("heading", { name: "決定を待っている相談" }),
  ).toBeVisible();
  await page.getByRole("button", { name: new RegExp(ask) }).click();
  await expect(page).toHaveURL(/\/owner\/[0-9a-f-]{36}$/);
  await expect(detail.getByRole("heading", { name: "材料" })).toBeVisible();
  await expect(detail.getByText("調べた結果")).toBeVisible();
  // 担当者の操作は、持ち主の画面には出さない
  await expect(
    detail.getByRole("button", { name: "結果を伝える" }),
  ).toHaveCount(0);
  await detail.getByLabel("決定の種類").selectOption({ label: "意図どおり" });
  await detail.getByLabel("決定の内容").fill(decision);
  await detail
    .getByLabel("理由")
    .fill("締め切りを過ぎた応募を受け付けないため。");
  await detail.getByRole("button", { name: "決定を記録する" }).click();
  await expect(
    page.getByText("決定を記録しました。担当者の番に戻りました。"),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: new RegExp(ask) })).toHaveCount(
    0,
  );

  // 担当者の番に戻り、決定が見える
  await page.goto("/operator");
  await cardIn(/^担当者の番/)
    .getByRole("button")
    .click();
  await expect(
    detail.getByRole("heading", { name: "仕様の持ち主の決定" }),
  ).toBeVisible();
  await expect(detail.getByText(decision)).toBeVisible();
  await expect(
    detail.getByText(/渡った理由: 仕様の持ち主が決めた/),
  ).toBeVisible();
  await expect(detail.getByLabel("本人に送る")).toBeVisible();

  // 決定は、本人のページには出さない（伝えるのは担当者）
  await page.goto(askerUrl);
  await expect(page.getByText("担当者が確認しています")).toBeVisible();
  await expect(page.getByText(decision)).toHaveCount(0);

  expect(consoleErrors).toEqual([]);
  expect(pageErrors).toEqual([]);
});

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
