---
type: spec
title: フォームAPIの契約
description: 問い合わせフォームの画面とAPIの間のHTTP契約。状態遷移、エンドポイント、エラー、モックのシナリオを定める。
---

# フォームAPIの契約

画面（`frontend/`）とAPI（`backend/`）はこの契約だけを共有する。変更するときは両方の実装とテストを同時に直す。

## 用語

- **種別（category）**: `SPEC_QUESTION`（仕様確認・相談）／`SUSPECTED_BUG`（不具合の疑い）／`WORK_REQUEST`（作業依頼）／`INCIDENT`（障害）
- **段階（step）**: 問い合わせ1件の進行位置
- **結果（outcome）**: `IN_PROGRESS`／`SELF_RESOLVED`（自己解決）／`FILED`（起票）／`INCIDENT_REDIRECTED`（障害の案内）／`ABANDONED`（離脱。初版では自動設定しない）

## 段階の遷移

| step | 意味 | 次の操作 |
| --- | --- | --- |
| `TRIAGED` | 種別の判定と追加質問が出た | 種別の変更（3）、追加質問への回答（4） |
| `STRUCTURED` | 整形後の問い（言い直し）が出た | 確定（5）、作り直し（5a）、追加質問へ戻る（5b） |
| `ANSWERING` | 0次回答の生成待ち | 進捗ストリームの購読（6） |
| `ANSWERED` | 0次回答が出て、本人の解決判定待ち | 解決の判定（7） |
| `CLOSED` | 終了。`outcome` が結果を示す | なし |

`CLOSED` 以外のどの段階でも「今すぐ担当者に送る」（8）を実行できる。

種別ごとの経路:

| 種別 | 追加質問 | 0次回答 | 終了のしかた |
| --- | --- | --- | --- |
| `SPEC_QUESTION` | AIの質問 | あり | 本人の判定で `SELF_RESOLVED` か `FILED` |
| `SUSPECTED_BUG` | AIの質問 | あり | 0次回答の完了と同時に `FILED` |
| `WORK_REQUEST` | 固定の3問 | なし | 整形結果の確定と同時に `FILED` |
| `INCIDENT` | 固定の1問 | なし | `INCIDENT_REDIRECTED`。「いいえ」なら `SUSPECTED_BUG` として続行 |

## InquiryView

すべてのエンドポイント（1を除く）が返す、問い合わせ1件の表現。

```json
{
  "id": "0b9c…(UUID)",
  "step": "TRIAGED",
  "outcome": "IN_PROGRESS",
  "utterance": "本人が書いた元の発話",
  "categoryByAi": "SPEC_QUESTION",
  "category": "SPEC_QUESTION",
  "questions": [{ "id": "q1", "text": "質問文", "options": ["選択肢A", "選択肢B"] }],
  "revisionsLeft": 2,
  "structured": {
    "summary": "昨日まで応募者向けの一覧に出ていた求人が、今日は表示されなくなった。なぜ表示されなくなったのかを知りたい。",
    "targetSystem": "求人管理画面",
    "goal": "必須",
    "whatHappened": null,
    "expectedResult": null,
    "urgency": "NORMAL"
  },
  "answer": {
    "grounded": true,
    "askerExplanation": "本人向けの説明",
    "memo": { "findings": ["…"], "notFound": ["…"], "nextChecks": ["…"] },
    "citations": [
      {
        "sourceType": "CODE",
        "repository": "job-board",
        "path": "src/main/java/…/JobPostingService.java",
        "startLine": 10,
        "endLine": 14,
        "quote": "引用文",
        "revision": "mock"
      }
    ]
  },
  "incidentGuidance": null,
  "sentImmediately": false,
  "mock": true
}
```

- `structured` は `STRUCTURED` 以降で非 null。`answer` は0次回答の完了後に非 null。
- `structured.summary` は本人に見せる言い直しの文章（必須）。画面はこの文章だけを見せ、ほかの項目は保存と起票に使う。
- `revisionsLeft` は言い直しを作り直せる残り回数（初期値2）。
- `answer.citations` には照合できた出典だけを入れる。照合できた出典が0件なら `grounded` は `false`、`askerExplanation` は `null`、`memo` は残る。
- `questions[].options` が空の質問は自由記述で答える。「わからない」は画面が常に追加し、APIには `answer: null` で送る。
- `urgency` は `LOW`／`NORMAL`／`HIGH`。`sourceType` は `CODE`／`FAQ`／`DOCUMENT`／`GLOSSARY`。
- `incidentGuidance` は `outcome` が `INCIDENT_REDIRECTED` のときだけ非 null。

## エンドポイント

### 1. `GET /api/config`

`200` `{ "mock": true, "filedGuidance": "担当者が内容を確認して連絡します。" }`。画面はモック動作中に「モック」と表示する。`filedGuidance` は担当者に送った後の案内文（設定 `inquiry.filed-guidance`）。

### 2. `POST /api/inquiries`

リクエスト `{ "utterance": "1〜4000文字" }`。種別を判定し追加質問を作る。

`201` InquiryView（`step: TRIAGED`）。`questions` は種別に応じて次のとおり。

- AIの判定が `SPEC_QUESTION`／`SUSPECTED_BUG`: AIが作った質問（0〜3個）
- `WORK_REQUEST`: 固定の3問。id は `wr-what`（何をしてほしいですか）、`wr-when`（いつまでに必要ですか）、`wr-target`（対象は何ですか）。いずれも `options` は空
- `INCIDENT`: 固定の1問。id は `incident-check`、文言は「いま業務やサービスが止まっていますか」、`options` は `["はい", "いいえ"]`

種別の判定でAIが失敗した場合（`AI_REFUSED`／`AI_UNAVAILABLE`）も、問い合わせは保存する。応答はエラー（422／503）で、本文に保存した問い合わせの id を加える。

```json
{ "code": "AI_UNAVAILABLE", "message": "…", "inquiryId": "0b9c…(UUID)" }
```

保存した問い合わせは `step: TRIAGED`、`categoryByAi` と `category` が `null`、`questions` が空。実行できる操作は「今すぐ担当者に送る」（8）と取得（9）だけで、3と4は `INVALID_STATE` を返す。画面は元の発話を消さずにエラーを示し、「今すぐ担当者に送る」へ誘導する。

### 3. `PUT /api/inquiries/{id}/category`

リクエスト `{ "category": "WORK_REQUEST" }`。本人が種別を変更する。`step` が `TRIAGED` のときだけ有効。

`200` InquiryView。`questions` は変更後の種別に合わせて差し替える。`SPEC_QUESTION`／`SUSPECTED_BUG` に変更した場合は、AIの判定がそのどちらかであればAIの質問、そうでなければ空。

### 4. `POST /api/inquiries/{id}/answers`

リクエスト `{ "answers": [{ "questionId": "q1", "answer": "選択肢A" }, { "questionId": "q2", "answer": null }] }`。`step` が `TRIAGED` のときだけ有効。質問がない場合は空配列を送る。

`200` InquiryView。

- `INCIDENT` で `incident-check` の回答が「いいえ」: 種別を `SUSPECTED_BUG` に変えて構造化し、`step: STRUCTURED`
- `INCIDENT` でそれ以外（「はい」、`null`）: `step: CLOSED`、`outcome: INCIDENT_REDIRECTED`、`incidentGuidance` に案内文
- それ以外の種別: 構造化し、`step: STRUCTURED`

### 5. `PUT /api/inquiries/{id}/structured`

リクエスト `{}` または `{ "summary": "本人が書き直した文章" }`。言い直しを確定する。`step` が `STRUCTURED` のときだけ有効。

- 本文が空（`summary` なし）: AIの言い直しをそのまま確定する（「合っています」）
- `summary` あり（1〜2000文字）: 本人が書き直した文章で確定する。確定値の `summary` だけをこの文章に置き換え、ほかの項目はAIの値を残す。0次回答は `summary` を問いの本文として扱う

`200` InquiryView。

- `WORK_REQUEST`: `step: CLOSED`、`outcome: FILED`。担当者に通知する
- それ以外: `step: ANSWERING`

### 5a. `POST /api/inquiries/{id}/revision`

リクエスト `{ "note": "消えたのは一昨日から" }`（1〜1000文字）。本人が違う点を一言で伝え、AIが言い直しを作り直す。`step` が `STRUCTURED` で `revisionsLeft` が1以上のときだけ有効。残りが0なら `INVALID_STATE`。

`200` InquiryView（`step: STRUCTURED`、`structured` が新しい言い直し、`revisionsLeft` が1減る）。伝えられた一言は記録に残す。AIが失敗した場合（`AI_REFUSED`／`AI_UNAVAILABLE`）は残り回数を減らさない。

### 5b. `POST /api/inquiries/{id}/reopen`

本文なし。追加質問へ戻る。`step` が `STRUCTURED` のときだけ有効。

`200` InquiryView（`step: TRIAGED`、`structured` は `null`、`questions` は戻る前と同じ）。`revisionsLeft` は元に戻さない。

### 6. `GET /api/inquiries/{id}/answer-stream`

`text/event-stream`。`step` が `ANSWERING` のときに0次回答を生成し、進捗と結果を流す。

| event | data | 意味 |
| --- | --- | --- |
| `progress` | `{ "message": "コードを検索しています" }` | 進捗。0回以上 |
| `done` | InquiryView | 完了。この後ストリームを閉じる |
| `failed` | `{ "code": "AI_REFUSED", "message": "…" }` | 失敗。この後ストリームを閉じる |

- 完了時: `SPEC_QUESTION` は `step: ANSWERED`。`SUSPECTED_BUG` は `step: CLOSED`、`outcome: FILED`（担当者に通知）。
- 失敗時の `code`: `AI_REFUSED`／`AI_UNAVAILABLE`／`INVALID_STATE`（`ANSWERING` でない、または同じ問い合わせの生成が進行中）。失敗しても `step` は `ANSWERING` のままで、再購読による再試行と「今すぐ担当者に送る」ができる。
- 画面は `done` か `failed` を受けたら接続を閉じる（EventSource の自動再接続を止める）。

### 7. `POST /api/inquiries/{id}/resolution`

リクエスト `{ "resolved": true }`。`step` が `ANSWERED` のときだけ有効。

`200` InquiryView（`step: CLOSED`）。`resolved: true` は `SELF_RESOLVED`、`false` は `FILED`（担当者に通知）。

### 8. `POST /api/inquiries/{id}/send-now`

本文なし。`CLOSED` 以外で有効。その時点の内容で起票する。

`200` InquiryView（`step: CLOSED`、`outcome: FILED`、`sentImmediately: true`）。担当者に通知する。

### 9. `GET /api/inquiries/{id}`

`200` InquiryView。

## エラー

本文は `{ "code": "…", "message": "…" }`。

| HTTP | code | 場合 |
| --- | --- | --- |
| 400 | `VALIDATION` | 入力が不正 |
| 404 | `NOT_FOUND` | 問い合わせが存在しない |
| 409 | `INVALID_STATE` | その段階では実行できない操作 |
| 422 | `AI_REFUSED` | モデルが回答を拒否した |
| 503 | `AI_UNAVAILABLE` | モデルを呼び出せなかった |

`AI_REFUSED` と `AI_UNAVAILABLE` では、画面は「今すぐ担当者に送る」へ誘導する。

## モックのシナリオ

モック実装は発話の内容でシナリオを選ぶ。種別はキーワードで決まり、優先順位は上から。

| 発話に含まれる語 | 種別 |
| --- | --- |
| `障害`、`止まって`、`落ちて`、`つながらない` | `INCIDENT` |
| `依頼`、`してほしい`、`お願いします`、`してください` | `WORK_REQUEST` |
| `不具合`、`バグ`、`おかしい` | `SUSPECTED_BUG` |
| 上記以外 | `SPEC_QUESTION` |

振る舞いは角括弧のタグで切り替える。タグのない発話は正常系（追加質問2問、出典2件の根拠あり回答）。

| タグ | 振る舞い |
| --- | --- |
| `[質問なし]` | 追加質問を出さない |
| `[根拠なし]` | 0次回答が根拠なし（`grounded: false`） |
| `[幽霊出典]` | 実在しない出典だけを返す。照合で捨てられ、根拠なしになる |
| `[遅延]` | 進捗の各段階で待つ（既定2秒、設定 `inquiry.mock.slow-step-delay` で変更） |
| `[エラー]` | 0次回答で `AI_UNAVAILABLE` |
| `[拒否]` | 0次回答で `AI_REFUSED` |
| `[判定エラー]` | 種別の判定（2）で `AI_UNAVAILABLE` |

モックの構造化は発話を `goal` にそのまま入れる。0次回答はタグを `goal` から読む（`goal` は書き直しや作り直しで変わらないため、タグの振る舞いは最後まで保たれる）。

モックの言い直し（`summary`）は、タグを取り除いた発話と追加質問への回答から作る。

| 種別 | 言い直しの形 |
| --- | --- |
| `SPEC_QUESTION` | 「（発話）」について、いまの仕組みがどうなっているかを知りたい。 |
| `SUSPECTED_BUG` | 「（発話）」という状況が起きている。これが意図した動きかどうかを確認したい。 |
| `WORK_REQUEST` | 固定3問の回答を使い「（何を）を（いつまでに）までに対応してほしい。対象は（対象）。」。「わからない」の項目は省く |

「わからない」以外の追加質問への回答は、言い直しの末尾に「（質問文: 回答）」の形で足す。作り直し（5a）では、伝えられた一言を「補足: （一言）」として言い直しに足す。

モックの一次情報は `backend/src/main/resources/mock-knowledge/` の固定ファイル（サンプルのコード、FAQ、用語対応表）。正常系の出典はこのファイルの実在する行を指し、出典の照合は実際に動く。

## 対象システム

本人は最初の入力と同時に、対象システムを一覧から選べる。選ばない（「わからない」）場合はAIが発話から推測する。

- `GET /api/config` の応答に `systems`（`[{ "id": "job-admin", "label": "求人管理画面" }]`）を加える。一覧は設定 `inquiry.systems` で定義する。
- `POST /api/inquiries` の本文に `systemId`（任意。一覧の id、または `null`）を加える。一覧にない id は `VALIDATION`。
- InquiryView に `system`（`{ "id", "label" }` または `null`）と `systemGuessed`（AIが推測した値なら `true`）を加える。
  - 本人が選んだ場合: `system` はその値、`systemGuessed` は `false`
  - 選ばなかった場合: 受付が推測できれば `system` はその値で `systemGuessed` は `true`、推測できなければ `system` は `null`
- `PUT /api/inquiries/{id}/system`: リクエスト `{ "systemId": "job-admin" }` または `{ "systemId": null }`。本人が対象システムを直す。`step` が `TRIAGED` のときだけ有効。`200` InquiryView（`systemGuessed` は `false`）。
- 確定した対象システムの `label` を `structured.targetSystem` に入れる。
- 対象システムが決まっている（選択または推測）場合、聞き手は画面や対象を尋ねる質問を出さない。

モックの推測は発話に一覧の `label` が含まれるかで行う。モックの一覧は `job-admin`（求人管理画面）、`applicant-list`（応募者向けの一覧）、`billing`（請求管理）の3件。モックの正常系の質問は、対象システムが未定なら2問（「どの画面での話ですか」「求人の状態はどれですか」）、決まっていれば後者の1問だけにする。

## オペレータへの通知

問い合わせが担当者に送られた時点（`outcome` が `FILED` になった時点）で、オペレータに2つの方法で知らせる。自己解決と障害の案内では通知しない。

- **ツール内の通知**: オペレータ画面の一覧に未読として現れ、開いている画面には即時に届く。
- **Slack への投稿**: 対象システムに対応するチャンネルへ投稿する。投稿先は設定で決める。
  - `inquiry.systems[].slack-webhook-url`: 対象システムごとの Incoming Webhook の URL
  - `inquiry.slack.default-webhook-url`: 対象システムが未定、または個別の設定がない場合の投稿先
  - `inquiry.public-base-url`: 投稿に載せるオペレータ画面へのリンクの基点
  - 投稿に載せるリンクは `{public-base-url}/operator/{id}`。画面はこの URL を直接開くと、オペレータ画面でその問い合わせを開いた状態になる（下記「オペレータ画面の URL」）。
  - どちらの URL も未設定なら投稿せず、その旨をログに出す（ローカルとモックの既定）。
  - 投稿の内容: 種別、対象システム、言い直しの文章（なければ元の発話の先頭）、「今すぐ送る」による送付かどうか、オペレータ画面の該当問い合わせへのリンク。調査内容の全文とコードの抜粋は載せない。
  - Slack への投稿が失敗しても、担当者への送付は取り消さない。失敗はログと記録に残す。

オペレータ向けの API は `/api/operator` 配下に置く。認証は後のフェーズ（SSO）で加え、それまではローカルでの利用に限る。

### `GET /api/operator/inquiries`

担当者に送られた問い合わせの一覧（新しい順）。クエリ `unreadOnly=true` で未読だけに絞る。

`200` `{ "unreadCount": 2, "items": [{ "id", "filedAt", "category", "system": { "id", "label" } | null, "summary", "sentImmediately", "read": false }] }`。`summary` は言い直しの文章、なければ元の発話の先頭80文字。

### `GET /api/operator/inquiries/{id}`

`200` 問い合わせの全内容。`id`、`filedAt`、`askerId`、`utterance`、`categoryByAi`、`category`、`system`、`systemGuessed`、`followUps`（`[{ "text", "answer" }]`）、`structured`、`revisionNotes`、`answer`（InquiryView と同じ形。ただし `citations` は照合に落ちたものも含め、各出典に `verified` を付ける）、`sentImmediately`、`read`、`slackStatus`。担当者に送られていない問い合わせは `NOT_FOUND`。

`slackStatus` は Slack への投稿の状態で、次の4つのいずれか。真偽値ひとつでは「投稿先が未設定」と「投稿に失敗」を区別できず、未設定の環境ですべての問い合わせが失敗に見えるため、状態を分ける。

| 値 | 意味 | 画面での扱い |
| --- | --- | --- |
| `POSTED` | 投稿できた | 「Slack に投稿済み」と控えめに示す |
| `SKIPPED` | 投稿先が未設定のため投稿していない | 何も表示しない |
| `PENDING` | 投稿先はあるが、まだ結果が出ていない | 何も表示しない |
| `FAILED` | 投稿を試みて失敗した | 「Slack への投稿に失敗しました」と目立たせる |

`SKIPPED` かどうかは、その問い合わせの対象システムに対する投稿先（個別の設定、なければ既定）が、読み出しの時点で設定されているかで決める。

### オペレータ画面の URL

| パス | 画面 |
| --- | --- |
| `/operator` | 一覧。問い合わせは未選択 |
| `/operator/{id}` | 一覧と、その問い合わせの詳細を開いた状態 |

一覧で問い合わせを選ぶと URL が `/operator/{id}` に変わり、ブラウザの「戻る」で前の選択に戻れる。`{id}` が存在しない、または担当者に送られていない場合は、一覧を表示したうえで「この問い合わせは見つかりませんでした」と示す。`/operator` で始まらないパスは本人向けの画面。

### `POST /api/operator/inquiries/{id}/read`

既読にする。`200` 上と同じ全内容（`read: true`）。

### `GET /api/operator/stream`

`text/event-stream`。接続中、問い合わせが担当者に送られるたびに event `filed` を流す。data は一覧の1件と同じ形。接続を保つため、一定間隔でコメント行を送る。
