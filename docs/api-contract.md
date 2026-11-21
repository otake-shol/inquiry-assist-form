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
| `STRUCTURED` | 整形後の問いが出た | 整形結果の確定（5） |
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
  "structured": {
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
- `answer.citations` には照合できた出典だけを入れる。照合できた出典が0件なら `grounded` は `false`、`askerExplanation` は `null`、`memo` は残る。
- `questions[].options` が空の質問は自由記述で答える。「わからない」は画面が常に追加し、APIには `answer: null` で送る。
- `urgency` は `LOW`／`NORMAL`／`HIGH`。`sourceType` は `CODE`／`FAQ`／`DOCUMENT`／`GLOSSARY`。
- `incidentGuidance` は `outcome` が `INCIDENT_REDIRECTED` のときだけ非 null。

## エンドポイント

### 1. `GET /api/config`

`200` `{ "mock": true }`。画面はモック動作中に「モック」と表示する。

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

リクエスト `{ "targetSystem": null, "goal": "必須", "whatHappened": null, "expectedResult": null, "urgency": "NORMAL" }`。本人が確認・修正した整形結果を確定する。`step` が `STRUCTURED` のときだけ有効。

`200` InquiryView。

- `WORK_REQUEST`: `step: CLOSED`、`outcome: FILED`。担当者に通知する
- それ以外: `step: ANSWERING`

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

モックの構造化は発話を `goal` にそのまま入れる。0次回答はタグを `goal` から読む。

モックの一次情報は `backend/src/main/resources/mock-knowledge/` の固定ファイル（サンプルのコード、FAQ、用語対応表）。正常系の出典はこのファイルの実在する行を指し、出典の照合は実際に動く。
