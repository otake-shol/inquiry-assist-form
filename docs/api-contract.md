---
type: spec
title: フォームAPIの契約
description: 問い合わせフォームの画面とAPIの間のHTTP契約。状態遷移、エンドポイント、エラー、モックのシナリオを定める。
---

# フォームAPIの契約

画面（`frontend/`）とAPI（`backend/`）はこの契約だけを共有する。変更するときは両方の実装とテストを同時に直す。

## 用語

言葉の定義は `framework.md` が正。この文書で使う値だけを挙げる。

- **種類（type）**: `HOW_TO`（使い方を知りたい）／`HOW_IT_WORKS`（仕組みを知りたい）／`LOOKS_WRONG`（おかしいかもしれない）／`DO_FOR_ME`（やってほしい）／`CHANGE_REQUEST`（変えてほしい）
- **緊急度（urgency）**: `NORMAL`（通常）／`HURRY`（急ぎ）／`BLOCKED`（業務が止まっている）。種類とは別に持つ。障害は種類ではない
- **置き場所**: 種類、システム、機能の3つ。それぞれ、本人が選んだか（`ASKER`）、AI が置いたか（`AI`）を持つ
- **段階（step）**: 問い合わせ1件の進行位置
- **結果（outcome）**: `IN_PROGRESS`／`SELF_RESOLVED`（自己解決）／`FILED`（担当者に送った）／`ABANDONED`（離脱。自動では設定しない）

状態を「区間 × 誰の番か」に、結果を4つの終わり方に置き換えるのは、次の作業（段階 1-B）。この文書の `step` と `outcome` は、それまでの形。

## 段階の遷移

| step | 意味 | 次の操作 |
| --- | --- | --- |
| `INTAKE` | 入口の質問に答えている | 質問に答える（3）、前の質問に戻る（3a）、文章で書く（3b） |
| `STRUCTURED` | 組み立てた問い（言い直し）が出た | 確定（5）、作り直し（5a）、入口の質問へ戻る（5b） |
| `ANSWERING` | 調べて答えを作っている | 進捗ストリームの購読（6） |
| `ANSWERED` | 答えが出て、本人が結果を確かめる | 解決の判定（7） |
| `CLOSED` | 終了。`outcome` が結果を示す | なし |

種類ごとの経路:

| 種類 | 調べるか | 言い直しを確定した後 |
| --- | --- | --- |
| `HOW_TO`、`HOW_IT_WORKS` | 調べる | 答えを本人が確かめる（`ANSWERED`）。本人の判定で `SELF_RESOLVED` か `FILED` |
| `LOOKS_WRONG` | 調べる | 答えの完了と同時に `FILED`（意図した動きかは、担当者が判断する） |
| `DO_FOR_ME` | 調べる | 根拠があり、問いに答えている（本人が自分でできる手順が見つかった）場合は `ANSWERED`。本人が、自分で行うか（`SELF_RESOLVED`）、担当者に頼むか（`FILED`）を選ぶ。それ以外は、答えの完了と同時に `FILED` |
| `CHANGE_REQUEST` | 調べない | 確定と同時に `FILED` |

緊急度が `BLOCKED` の問い合わせは、種類にかかわらず、入口の質問を終えた時点で `FILED` にする（言い直しの確認と調査を待たない）。

## InquiryView

すべてのエンドポイント（1を除く）が返す、問い合わせ1件の表現。

```json
{
  "id": "0b9c…(UUID)",
  "createdAt": "2026-11-22T03:00:00Z",
  "step": "INTAKE",
  "outcome": "IN_PROGRESS",
  "urgency": "NORMAL",
  "type": { "id": "LOOKS_WRONG", "label": "おかしいかもしれない", "by": "ASKER" },
  "system": { "id": "system-a", "label": "システムA", "by": "ASKER" },
  "function": null,
  "utterance": null,
  "intake": {
    "entries": [
      { "questionId": "type", "kind": "TYPE", "text": "何をしたいですか",
        "answer": { "kind": "OPTION", "optionId": "LOOKS_WRONG", "label": "おかしいかもしれない" } },
      { "questionId": "system", "kind": "SYSTEM", "text": "どのシステムですか",
        "answer": { "kind": "OPTION", "optionId": "system-a", "label": "システムA" } }
    ],
    "next": { "questionId": "function", "kind": "FUNCTION", "text": "どの機能ですか",
              "options": [{ "id": "a-1", "label": "機能A-1" }], "allowsText": false, "canSkip": false },
    "complete": false
  },
  "revisionsLeft": 2,
  "structured": null,
  "answer": null,
  "canSendNow": false,
  "sentImmediately": false,
  "handlingStatus": null,
  "messages": [],
  "trace": [],
  "mock": true
}
```

- `type`、`system`、`function` は、決まっていなければ `null`。`by` は `ASKER`（本人が選んだ）か `AI`（AI が置いた）。本人が「わからない」と答え、AI も置けなかった場合は `null` のまま。
- `utterance` は、本人が書いた文章。文章で始めた場合の本文、文章に切り替えたときの本文、補足のいずれか。複数ある場合は、書いた順に空行でつなぐ。何も書いていなければ `null`。
- `intake.entries` は、答え終えた質問（聞いた順）。`intake.next` は、次に答える質問。入口が終わっていれば `null` で、`complete` が `true`。
- `intake.entries[].answer.kind` は `OPTION`（選択肢を選んだ。`optionId` と `label`）、`TEXT`（文章で答えた。`text`）、`UNKNOWN`（わからない）、`SKIPPED`（補足を書かずに進んだ）、`INFERRED`（文章から AI が置いた。`optionId` と `label`）のいずれか。
- `intake.next.kind` は `TYPE`、`SYSTEM`、`FUNCTION`、`DETAIL`、`NOTE` のいずれか。`options` が空の質問は文章で答える。`allowsText` が `true` の質問は、選択肢のほかに文章でも答えられる。`canSkip` は `NOTE` だけが `true` になりうる。「わからない」は、`NOTE` 以外のすべての質問で選べる（画面が常に出す）。
- `structured` は `STRUCTURED` 以降で非 null。項目は `summary`（本人に見せる言い直しの文章。必須）、`targetSystem`、`goal`、`whatHappened`、`expectedResult`。`urgency` は `structured` から外し、問い合わせの `urgency` に一本化する。
- `answer` は、答えの完了後に非 null。形は従来どおり（`grounded`、`answered`、`askerExplanation`、`memo`、`citations`）。
- `canSendNow` は、いま「答えを待たずに担当者へ送る」（8）を実行できるか。
- `revisionsLeft` は、言い直しを作り直せる残り回数（初期値2）。
- 廃止した項目: `categoryByAi`、`category`、`questions`、`systemGuessed`、`submittedAnswers`、`incidentGuidance`。

## 入口の質問

入口は、1度に1問ずつ出す。質問を決めるのは API で、画面は `intake.next` を描くだけにする。

### 聞く順

1. **種類**（`TYPE`、id は `type`）: 「何をしたいですか」。選択肢は5つの種類。
2. **システム**（`SYSTEM`、id は `system`）: 「どのシステムですか」。選択肢は設定のシステムの一覧。
3. **機能**（`FUNCTION`、id は `function`）: 「どの機能ですか」。選択肢は、選ばれたシステムの機能。システムが決まっていない場合と、そのシステムに機能の設定がない場合は、聞かない。
4. **詳しい質問**（`DETAIL`）: 0〜3問。マスの設定（下記）に質問があれば、それを使う（設定の順。3問を超える分は捨てる）。設定に質問がなければ、聞き手のスキルが質問を作る（0〜2問）。聞き手が失敗した場合は、詳しい質問なしで進む。
5. **補足**（`NOTE`、id は `note`）: 「補足があれば書いてください」。書かずに進める（`canSkip: true`）。次の場合は、書かずには進めない（`canSkip: false`、文言は「状況を一言で教えてください」）。
   - ここまでのすべての質問に「わからない」と答え、文章も書いていない（手がかりが何もないまま担当者に渡さない）
   - 緊急度が `BLOCKED` で、文章を書いていない

緊急度が `BLOCKED` の場合は、詳しい質問（4）を聞かない。

### 文章で書く

本人は、最初から文章で始めることも、途中で文章に切り替えることもできる。文章を受け取ったら、受付のスキルが、まだ答えていない置き場所（種類、システム、機能）を文章から置く。

- 置けたものは、答え終えた質問として `entries` に入れる（`answer.kind: INFERRED`）。置き場所の `by` は `AI`。
- 置けなかったものは、質問として聞く（聞く順は上と同じ）。
- 本人が既に選んだものは、変えない。
- 受付が失敗した場合は、何も置かずに、残りの質問を聞く（エラーにしない）。
- 文章を書いた場合、補足（5）は聞かない。
- 詳しい質問は、上の4と同じ決まりで出す。聞き手には、書かれた文章も渡す。

### マスの設定

詳しい質問は、設定 `inquiry.cells` で、置き場所ごとに決める。

```yaml
inquiry:
  cells:
    - system: system-a        # 省くと、すべてのシステム
      function: a-1           # 省くと、すべての機能
      type: LOOKS_WRONG       # 省くと、すべての種類
      questions:
        - id: since
          text: { ja: いつから起きていますか, en: "Since when has this been happening?" }
          options:
            - { id: today, label: { ja: 今日から, en: Since today } }
            - { id: this-week, label: { ja: 今週から, en: Since this week } }
            - { id: earlier, label: { ja: もっと前から, en: Earlier } }
```

- 問い合わせの置き場所に当てはまる設定のうち、指定した項目（`system`、`function`、`type`）が最も多いものを1つ使う。同じ数なら、設定に書かれた順で先のもの。当てはまるものがなければ、設定の質問はない。
- 置き場所が決まっていない項目（`null`）は、その項目を指定した設定には当てはまらない。
- `options` を省いた質問は、文章で答える。

設定の文言（種類、システム、機能、質問、選択肢の名前）は、言語ごとの名前の対応で持つ（`{ ja: …, en: … }`）。API が返す `label` と `text` は、いまは既定の言語（`inquiry.languages` の先頭。既定は `ja`）の名前。本人の言語に合わせて返すのは、段階 1-C。

## エンドポイント

### 1. `GET /api/config`

`200`

```json
{
  "mock": true,
  "skills": { "reception": "mock", "interviewer": "mock", "restater": "mock", "investigator": "mock", "explainer": "mock" },
  "trace": true,
  "user": { "id": "local-user", "name": "開発用ユーザー" },
  "operatorName": "担当者（開発用）",
  "filedGuidance": "担当者が内容を確認し、このページに返信します。…",
  "languages": ["ja", "en"],
  "types": [{ "id": "HOW_TO", "label": "使い方を知りたい", "hint": "操作の手順" }],
  "urgencies": [{ "id": "NORMAL", "label": "通常" }, { "id": "HURRY", "label": "急ぎ" }, { "id": "BLOCKED", "label": "業務が止まっている" }],
  "systems": [{ "id": "system-a", "label": "システムA", "functions": [{ "id": "a-1", "label": "機能A-1" }] }]
}
```

`types` は5つを、聞く順（上の用語の順）で返す。`incidentGuidance` は廃止。

### 2. `POST /api/inquiries`

リクエスト `{}`、または `{ "text": "1〜4000文字", "urgency": "HURRY" }`。どちらの項目も省ける。

`201` InquiryView（`step: INTAKE`）。

- `text` がない: 最初の質問（種類）が `intake.next` に入る。AI は呼ばない。
- `text` がある: 「文章で書く」の決まりで置き場所を置き、残りの最初の質問を `intake.next` に入れる。残りの質問がなければ、そのまま問いを組み立てて `step: STRUCTURED` にする。
- `urgency` を省くと `NORMAL`。

AI が失敗しても、この操作はエラーにしない（受付と聞き手の失敗は、質問を聞く形に置き換わる。言い直しの失敗は下の3と同じ扱い）。

### 3. `POST /api/inquiries/{id}/intake/answers`

`intake.next` の質問に答える。`step` が `INTAKE` のときだけ有効。リクエストは次のいずれか。

| 答え方 | リクエスト |
| --- | --- |
| 選択肢を選ぶ | `{ "questionId": "type", "optionId": "LOOKS_WRONG" }` |
| 文章で答える | `{ "questionId": "since", "text": "1〜1000文字" }` |
| わからない | `{ "questionId": "system", "unknown": true }` |
| 補足を書かずに進む | `{ "questionId": "note", "skipped": true }` |

- `questionId` が `intake.next` の id と違えば `INVALID_STATE`。
- 選択肢にない `optionId`、選択肢のある質問への文章（`allowsText` が `false` の場合）、`NOTE` への「わからない」、`canSkip` が `false` の質問への `skipped` は `VALIDATION`。
- 補足（`NOTE`）の文章は1〜4000文字。

`200` InquiryView。

- 次の質問があれば、`intake.next` に入る（`step: INTAKE`）。
- 最後の質問に答えた場合は、言い直しのスキルが問いを組み立て、`step: STRUCTURED` にする。
- 最後の質問に答え、緊急度が `BLOCKED` の場合は、問いを組み立てたうえで `step: CLOSED`、`outcome: FILED`、`sentImmediately: true` にする（担当者に通知する）。言い直しが失敗した場合も、組み立てなしで同じく `FILED` にする。
- 言い直しのスキルが失敗した場合（`BLOCKED` 以外）は、答えを保存したうえで、エラー（`AI_REFUSED` は422、`AI_UNAVAILABLE` は503）を返す。本文に `inquiryId` を含める。`step` は `INTAKE` のまま、`intake.complete` は `true`、`canSendNow` は `true` になる。エラーの本文には状態を含めないので、画面は `inquiryId` で取得（9）し直して、状態を反映する。同じ操作をやり直す方法は、5b（入口の質問へ戻る）ではなく、`POST /api/inquiries/{id}/intake/compose`（本文なし。問いの組み立てだけをやり直す。`intake.complete` が `true` で `step` が `INTAKE` のときだけ有効）。

### 3a. `POST /api/inquiries/{id}/intake/back`

リクエスト `{ "questionId": "system" }`。答え終えた質問に戻る。`step` が `INTAKE` のときだけ有効。

`200` InquiryView。指定した質問と、それより後の答えを捨て、指定した質問を `intake.next` にする。捨てた答えで決まっていた置き場所も、決まっていない状態に戻す。`entries` にない `questionId` は `VALIDATION`。本人が書いた文章（`utterance`）は消さない。

### 3b. `POST /api/inquiries/{id}/intake/text`

リクエスト `{ "text": "1〜4000文字" }`。文章で書く道に切り替える。`step` が `INTAKE` のときだけ有効。

`200` InquiryView。「文章で書く」の決まりで、まだ答えていない置き場所を置く。答えかけの質問（`intake.next`）は、置けなければ改めて聞く。

### 4. `PUT /api/inquiries/{id}/urgency`

リクエスト `{ "urgency": "BLOCKED" }`。本人が緊急度を変える。`step` が `INTAKE` か `STRUCTURED` のときだけ有効。

`200` InquiryView。`INTAKE` で `BLOCKED` に変えた場合、まだ答えていない詳しい質問は聞かない（`intake.next` が詳しい質問なら、補足に進める）。`STRUCTURED` で `BLOCKED` に変えた場合は、その時点の言い直しで `step: CLOSED`、`outcome: FILED`、`sentImmediately: true` にする。

### 5. `PUT /api/inquiries/{id}/structured`

リクエスト `{}` または `{ "summary": "本人が書き直した文章" }`。言い直しを確定する。`step` が `STRUCTURED` のときだけ有効。

- 本文が空（`summary` なし）: AIの言い直しをそのまま確定する（「合っています」）
- `summary` あり（1〜2000文字）: 本人が書き直した文章で確定する。確定値の `summary` だけをこの文章に置き換え、ほかの項目はAIの値を残す。0次回答は `summary` を問いの本文として扱う

`200` InquiryView。

- `CHANGE_REQUEST`: `step: CLOSED`、`outcome: FILED`。担当者に通知する
- それ以外: `step: ANSWERING`

### 5a. `POST /api/inquiries/{id}/revision`

リクエスト `{ "note": "消えたのは一昨日から" }`（1〜1000文字）。本人が違う点を一言で伝え、AIが言い直しを作り直す。`step` が `STRUCTURED` で `revisionsLeft` が1以上のときだけ有効。残りが0なら `INVALID_STATE`。

`200` InquiryView（`step: STRUCTURED`、`structured` が新しい言い直し、`revisionsLeft` が1減る）。伝えられた一言は記録に残す。AIが失敗した場合（`AI_REFUSED`／`AI_UNAVAILABLE`）は残り回数を減らさない。

### 5b. `POST /api/inquiries/{id}/reopen`

本文なし。入口の質問へ戻る。`step` が `STRUCTURED` のときだけ有効。

`200` InquiryView（`step: INTAKE`、`structured` は `null`）。最後に答えた質問を `intake.next` に戻し、その答えを捨てる（3a で最後の質問を指定したのと同じ）。`revisionsLeft` は元に戻さない。

### 6. `GET /api/inquiries/{id}/answer-stream`

`text/event-stream`。`step` が `ANSWERING` のときに0次回答を生成し、進捗と結果を流す。

| event | data | 意味 |
| --- | --- | --- |
| `progress` | `{ "message": "コードを検索しています" }` | 進捗。0回以上 |
| `done` | InquiryView | 完了。この後ストリームを閉じる |
| `failed` | `{ "code": "AI_REFUSED", "message": "…" }` | 失敗。この後ストリームを閉じる |

- 完了時: 種類ごとの経路の表のとおり。`HOW_TO` と `HOW_IT_WORKS` は `step: ANSWERED`。`LOOKS_WRONG` は `step: CLOSED`、`outcome: FILED`（担当者に通知）。`DO_FOR_ME` は、`answer.grounded` と `answer.answered` がともに `true` なら `step: ANSWERED`、そうでなければ `step: CLOSED`、`outcome: FILED`（担当者に通知）。
- 失敗時の `code`: `AI_REFUSED`／`AI_UNAVAILABLE`／`INVALID_STATE`（`ANSWERING` でない、または同じ問い合わせの生成が進行中）。失敗しても `step` は `ANSWERING` のままで、再購読による再試行と「答えを待たずに担当者へ送る」（8）ができる。
- 画面は `done` か `failed` を受けたら接続を閉じる（EventSource の自動再接続を止める）。

### 7. `POST /api/inquiries/{id}/resolution`

リクエスト `{ "resolved": true }`。`step` が `ANSWERED` のときだけ有効。

`200` InquiryView（`step: CLOSED`）。`resolved: true` は `SELF_RESOLVED`、`false` は `FILED`（担当者に通知）。

### 8. `POST /api/inquiries/{id}/send-now`

本文なし。答えを待たずに担当者へ送る。**問いを確かめた後に限る。** 入力が足りないまま担当者に渡すと、聞き返しが増えるだけになるため。

実行できるのは、`canSendNow` が `true` のとき。次のいずれか。

- `step` が `ANSWERING` か `ANSWERED`（言い直しを確定した後）
- AI の失敗で先へ進めない（3 で言い直しが失敗し、`intake.complete` が `true` のまま `step` が `INTAKE` にある）。こちらの失敗を理由に、本人を止めない
- `step` が `STRUCTURED` で、`revisionsLeft` が 0（言い直しを2回作り直しても合わなかった）。汲み取れなかったのはこちらの側なので、本人を止めない

それ以外は `INVALID_STATE`。

`200` InquiryView（`step: CLOSED`、`outcome: FILED`、`sentImmediately: true`）。担当者に通知する。

答えを待たずに送った後も AI の調査を裏で続け、材料を担当者に渡すのは、段階2で作る（RC-8）。いまは、送った時点で調査を行わない。

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

`AI_REFUSED` と `AI_UNAVAILABLE` では、画面は `canSendNow` を見て、送れる場合は「担当者に送る」へ誘導する。

## モックのシナリオ

モックの実装は、本人が書いた文章（`utterance`。文章で始めた本文、切り替えた本文、補足）でシナリオを選ぶ。押して答えただけで文章がない場合は、すべて正常系になる。

### 受付（文章から置き場所を置く）

種類は、文章に含まれる語で決まる。優先順位は上から。

| 文章に含まれる語 | 種類 |
| --- | --- |
| `変えてほしい`、`要望`、`改善` | `CHANGE_REQUEST` |
| `依頼`、`してほしい`、`お願いします`、`してください` | `DO_FOR_ME` |
| `不具合`、`バグ`、`おかしい` | `LOOKS_WRONG` |
| `使い方`、`やり方`、`手順`、`どうやって` | `HOW_TO` |
| 上記以外 | `HOW_IT_WORKS` |

システムは、文章に設定のシステムの名前（既定の言語の `label`）が含まれていれば、そのシステム。機能は、システムが決まっていて、文章にそのシステムの機能の名前が含まれていれば、その機能。含まれていなければ置かない（質問として聞く）。緊急度は、文章からは決めない。

### タグ

振る舞いは、文章に書いた角括弧のタグで切り替える。タグのない場合は正常系（詳しい質問は、設定に質問がなければ聞き手の1問。出典2件の根拠あり回答）。

| タグ | 失敗・変化するスキル | 振る舞い |
| --- | --- | --- |
| `[判定エラー]` | 受付 | 受付が `AI_UNAVAILABLE` で失敗する。置き場所は置かれず、種類から順に質問として聞く（エラーは返さない） |
| `[質問なし]` | 聞き手 | 詳しい質問を出さない |
| `[質問エラー]` | 聞き手 | 聞き手が `AI_UNAVAILABLE` で失敗する。詳しい質問なしで先へ進む（エラーは返さない） |
| `[言い直しエラー]` | 言い直し | 問いの組み立てが `AI_UNAVAILABLE` で失敗する（3 の失敗の扱いになる） |
| `[根拠なし]` | 調査 | 事実を1件も返さない。根拠なし（`grounded: false`）になる |
| `[幽霊出典]` | 調査 | すべての事実の根拠が実在しない。照合で全部落ち、根拠なしになる。事実は `memo.unconfirmed` に残る |
| `[一部幽霊]` | 調査 | 事実2件のうち1件は実在する根拠、1件は実在しない根拠を持つ。根拠ありになり、説明は確かめられた1件だけから書かれ、もう1件は `memo.unconfirmed` に入る |
| `[遅延]` | 調査 | 進捗の各段階で待つ（既定2秒、設定 `inquiry.mock.slow-step-delay` で変更） |
| `[エラー]` | 調査 | 進捗を流した後に `AI_UNAVAILABLE` |
| `[拒否]` | 調査 | 進捗を流した後に `AI_REFUSED` |
| `[説明エラー]` | 説明 | 調査と照合は成功し、説明が `AI_UNAVAILABLE` で失敗する。調査の結果は保存される |
| `[的外れ]` | 説明 | 調査は通常の事実を返し、説明が「問いに答えていない」と判定する。根拠あり（`grounded: true`）で `answered: false` になる |

### 聞き手（設定に質問がない場合）

モックの聞き手は、質問を1問返す。id は `q1`、文言は「どの場面での話ですか」、選択肢は「画面を開いたとき」「保存したとき」「一覧を見たとき」（選択肢の id は `o1`〜`o3`）。

### 言い直し（問いの組み立て）

モックの言い直し（`summary`）は、置き場所と答えから、次の形で作る。

1. 置き場所の前置き: システムと機能が決まっていれば「（システム）の（機能）について、」。システムだけなら「（システム）について、」。どちらも決まっていなければ前置きなし
2. 種類ごとの文
3. 文章があれば、タグを取り除いた文章を続ける: 「書かれた内容: （文章）」
4. 詳しい質問のうち、「わからない」以外の答えを続ける: 「（質問文: 答え）」
5. 作り直し（5a）で伝えられた一言を続ける: 「補足: （一言）」

| 種類 | 種類ごとの文 |
| --- | --- |
| `HOW_TO` | 使い方を知りたい。 |
| `HOW_IT_WORKS` | いまの仕組みがどうなっているかを知りたい。 |
| `LOOKS_WRONG` | 動きがおかしいかもしれない。意図した動きかどうかを確認したい。 |
| `DO_FOR_ME` | 作業をしてほしい。 |
| `CHANGE_REQUEST` | 変えてほしい点がある。 |
| 決まっていない | 相談したいことがある。 |

モックの言い直しは、本人が書いた文章を `goal` にそのまま入れる（文章がなければ、種類ごとの文）。調査と説明のモックは、タグを `goal` から読む（`goal` は書き直しや作り直しで変わらないため、タグの振る舞いは最後まで保たれる）。

モックの説明は、渡された事実の文をつないで作る（「（事実1）。（事実2）。」の形。`LOOKS_WRONG` では先頭に「現在の実装では、」を付け、末尾に「この動作が意図した仕様かどうかは、担当者が確認します。」を足す）。渡されなかった事実の内容が説明に現れないことを、テストで確かめられる形にする。

モックの一次情報は `backend/src/main/resources/mock-knowledge/` の固定ファイル（サンプルのコード、FAQ、用語対応表）。正常系の出典はこのファイルの実在する行を指し、出典の照合は実際に動く。

## 既定の設定

既定の設定（`application.yaml`）は、骨組みであることが分かる仮の名前にする。実際の一覧は、環境ごとの設定で差し替える。

| 項目 | 既定 |
| --- | --- |
| 言語 | `ja`、`en`（既定は `ja`） |
| システム | `system-a`（システムA。機能は `a-1`（機能A-1）、`a-2`（機能A-2））、`system-b`（システムB。機能は `b-1`（機能B-1））、`system-c`（システムC。機能の設定なし） |
| マスの設定 | 1件。`system-a` の `LOOKS_WRONG` に、質問「いつから起きていますか」（選択肢は、今日から、今週から、もっと前から） |

確定したシステムの名前（既定の言語の `label`）を、`structured.targetSystem` に入れる。

API のテストは、業務らしい名前の一覧をテスト用の設定（`backend/src/test/resources/config/application.yaml`）で与えて行う。名前から置き場所を置く動きを、意味のある名前で確かめるため。システムは `job-admin`（求人管理画面。機能は `posting`（求人の掲載）、`applicants`（応募者の一覧））、`billing`（請求管理。機能は `invoice`（請求書））、`reports`（レポート。機能の設定なし）。マスの設定は、`job-admin` の `LOOKS_WRONG` に質問「いつから起きていますか」（選択肢つき）と、`billing` の `invoice` の `DO_FOR_ME` に、文章で答える質問「対象の取引先はどこですか」。

## オペレータへの通知

問い合わせが担当者に送られた時点（`outcome` が `FILED` になった時点）で、オペレータに2つの方法で知らせる。自己解決では通知しない。

- **ツール内の通知**: オペレータ画面の一覧に未読として現れ、開いている画面には即時に届く。
- **Slack への投稿**: 対象システムに対応するチャンネルへ投稿する。投稿先は設定で決める。
  - `inquiry.systems[].slack-webhook-url`: 対象システムごとの Incoming Webhook の URL
  - `inquiry.slack.default-webhook-url`: 対象システムが未定、または個別の設定がない場合の投稿先
  - `inquiry.public-base-url`: 投稿に載せるオペレータ画面へのリンクの基点
  - 投稿に載せるリンクは `{public-base-url}/operator/{id}`。画面はこの URL を直接開くと、オペレータ画面でその問い合わせを開いた状態になる（下記「オペレータ画面の URL」）。
  - どちらの URL も未設定なら投稿せず、その旨をログに出す（ローカルとモックの既定）。
  - 投稿の内容: 種類、システムと機能、緊急度（通常以外のとき）、言い直しの文章（なければ本人が書いた文章の先頭）、答えを待たずに送られたかどうか、オペレータ画面の該当問い合わせへのリンク。調査内容の全文とコードの抜粋は載せない。
  - Slack への投稿が失敗しても、担当者への送付は取り消さない。失敗はログと記録に残す。

オペレータ向けの API は `/api/operator` 配下に置く。認証は後のフェーズ（SSO）で加え、それまではローカルでの利用に限る。

### `GET /api/operator/inquiries`

担当者に送られた問い合わせの一覧（新しい順）。クエリ `unreadOnly=true` で未読だけに絞る。

`200` `{ "unreadCount": 2, "items": [{ "id", "filedAt", "type": { "id", "label" } | null, "system": { "id", "label" } | null, "function": { "id", "label" } | null, "urgency", "summary", "sentImmediately", "read": false }] }`。`summary` は言い直しの文章、なければ本人が書いた文章の先頭80文字、それもなければ置き場所の名前（種類、システム、機能を「・」でつないだもの）。並びは新しい順。ただし緊急度が `BLOCKED` の件を先頭に寄せる。

### `GET /api/operator/inquiries/{id}`

`200` 問い合わせの全内容。`id`、`filedAt`、`askerId`、`askerName`、`utterance`、`urgency`、`type`、`system`、`function`（それぞれ InquiryView と同じ形。`by` を含む）、`intake`（InquiryView の `intake.entries` と同じ形の配列。本人が何を選び、何に「わからない」と答えたかを、聞いた順に示す）、`structured`、`revisionNotes`、`answer`（InquiryView と同じ形。ただし `citations` は照合に落ちたものも含め、各出典に `verified` を付ける）、`sentImmediately`、`read`、`slackStatus`、`handlingStatus`、`messages`、`trace`。担当者に送られていない問い合わせは `NOT_FOUND`。

`followUps` は廃止し、`intake` に置き換える。入力を終える前に担当者へ送られることがなくなったため、「未回答」の状態は要らなくなった。

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
| `/operator` | ボード。問い合わせは未選択 |
| `/operator/{id}` | ボードと、その問い合わせの詳細のパネルを開いた状態 |

一覧で問い合わせを選ぶと URL が `/operator/{id}` に変わり、ブラウザの「戻る」で前の選択に戻れる。`{id}` が存在しない、または担当者に送られていない場合は、一覧を表示したうえで「この問い合わせは見つかりませんでした」と示す。`/operator` で始まらないパスは本人向けの画面。

### `POST /api/operator/inquiries/{id}/read`

既読にする。`200` 上と同じ全内容（`read: true`）。

### `GET /api/operator/stream`

`text/event-stream`。接続中、問い合わせが担当者に送られるたびに event `filed` を流す。data は一覧の1件と同じ形。接続を保つため、一定間隔でコメント行を送る。

## スキルの構成に伴う取り決め

AI処理の内部の構成は `docs/skill-architecture.md` が正。画面との契約に関わる点だけをここに書く。

- **`answer.memo.unconfirmed`**: 調査が挙げたが、根拠の実在を確かめられなかった事実の文の一覧。該当がなければ空配列。**担当者にだけ返す。** 中身が入るのはオペレータの詳細（`GET /api/operator/inquiries/{id}`）の `answer.memo.unconfirmed` だけで、本人向けの InquiryView（2〜9 と進捗ストリームの `done`）では常に空配列にする。裏づけのない内容は、本人には真偽を判断できず、読めばそのまま試してしまうおそれがあるため。画面で隠すのではなく、API が本人向けの応答に含めない。オペレータ画面は「根拠を確かめられなかったこと」として、分かったこと（`findings`）と区別して示す。本人向けの説明（`askerExplanation`）は `findings` の内容だけから書かれ、`unconfirmed` の内容を含まない。
- **0次回答の進捗**: 調査の進捗に続けて「根拠を確かめています」が流れ、根拠ありの場合はさらに「答えをまとめています」が流れる。
- **説明の失敗**: 調査と照合が成功し、説明だけが失敗した場合、進捗ストリームは `failed`（`AI_UNAVAILABLE` または `AI_REFUSED`）を流し、`step` は `ANSWERING` のまま。調査の結果（技術メモ、出典と照合結果、利用量）は保存する。ただし本人向けの InquiryView の `answer` は `ANSWERING` の間は `null` のままにする（説明がない状態を「答えが見つからなかった」と誤って見せないため）。画面は従来どおり再試行と「今すぐ担当者に送る」を出す。送った場合、オペレータの詳細（`GET /api/operator/inquiries/{id}`）の `answer` に保存済みの調査の内容が入り、`grounded` は `false`、`askerExplanation` は `null` になる。再試行で調査が成功すれば、保存済みの内容は新しい結果で置き換える。
- **利用量の記録**: `usages[].step` はスキルの識別子（`reception`、`interviewer`、`restater`、`investigator`、`explainer`）。押して答えただけの入口では、受付は呼ばれない（記録にも現れない）。
- **問いに答えられたか（`answer.answered`）**: 確かめられた事実があること（`grounded`）と、その事実が本人の問いに答えていること（`answered`）を区別する。根拠はあるが的外れな事実しか集まらなかった場合に、それを答えとして見せないため。
  - `answered` は真偽値。`grounded` が `false` のときは常に `false`。`grounded` が `true` のときは、説明のスキルの判定（問いに答えているか）をそのまま入れる。
  - `grounded: true`、`answered: false` の場合も、`askerExplanation`（確認できなかったことと、関連して分かったこと）、`citations`、`memo` は従来どおり返す。
  - 画面の扱い（本人向け）:

    | `grounded` | `answered` | 表示 |
    | --- | --- | --- |
    | `true` | `true` | 従来どおり。説明を主に示し、「解決しました」を主のボタンにする |
    | `true` | `false` | 「ご質問への答えは、手元の資料では確認できませんでした。」と示し、その下に「関連して分かったこと」として説明を出す。「担当者に送る」を主のボタン、「解決しました」を副のボタンにする |
    | `false` | `false` | 従来どおり。「手元の資料からは答えを見つけられませんでした。」 |

  - 種類ごとの終わり方は変えない（`LOOKS_WRONG` は、どの場合も担当者に送る）。
  - オペレータの詳細の `answer` にも `answered` を含める。
  - モックの説明は `answered: true` を返す。タグ `[的外れ]` を付けた場合だけ `false` を返す（調査は通常の事実を返し、説明のスキルが「問いに答えていない」と判定した場合を再現する）。
- **スキルごとの実装**: `GET /api/config` に `skills` を加える。スキルの識別子から、動いている実装の名前（`mock` または `ollama`）への対応。例: `{ "reception": "ollama", "interviewer": "ollama", "restater": "ollama", "investigator": "mock", "explainer": "ollama" }`。`mock` は、スキルか一次情報の検索に1つでもモックがあれば `true`。画面はモックの案内枠に、どのスキルがモックかを示す。モックのシナリオのタグは、モックで動いているスキルにだけ効く（たとえば受付が実際のモデルなら、`[判定エラー]` を書いても失敗しない）。

## 送った後のやり取り（往復）

問い合わせは、担当者に届いて終わりではない。担当者が返信し、本人がそれを読み、必要なら追記する。やり取りは問い合わせ1件に結びつく。対象は、担当者に送られた問い合わせ（`outcome: FILED`）だけ。

### 対応状況（handlingStatus）

担当者に送られた問い合わせが持つ、担当者側の進み具合。`step`（本人の入力の進行位置）とは別に持つ。

| 値 | 意味 | 本人向けの画面の言葉 |
| --- | --- | --- |
| `OPEN` | 未対応。担当者に送られた時点の値 | 担当者の確認を待っています |
| `IN_PROGRESS` | 対応中 | 担当者が対応しています |
| `DONE` | 完了 | 対応が完了しました |

- 担当者に送られていない問い合わせでは `null`。
- 担当者が返信すると、`OPEN` は自動で `IN_PROGRESS` になる（`IN_PROGRESS` と `DONE` は変えない）。
- 本人が追記すると、`DONE` は自動で `IN_PROGRESS` に戻る（終わった件に追記があったことを、担当者が見落とさないため）。

### メッセージ（messages）

担当者の返信と本人の追記を、1本の時系列として持つ。

```json
{ "id": "UUID", "from": "OPERATOR", "authorName": "担当者（開発用）", "body": "本文", "createdAt": "2026-11-21T12:00:00Z" }
```

- `from` は `OPERATOR`（担当者の返信）か `ASKER`（本人の追記）。
- `authorName` は表示用の名前。認証を加えるまでは、設定の固定値（`inquiry.dev.operator-name`、`inquiry.dev.user-name`）。
- 古い順に並べる。編集と削除はできない。

### 未読

- **担当者側**（既存の `read`）: 本人が追記すると `read` を `false` に戻す。
- **本人側**（`unreadReply`）: 担当者が返信すると `true`、本人がその問い合わせを取得（9）すると `false` になる。

### InquiryView への追加

| 項目 | 内容 |
| --- | --- |
| `createdAt` | 作成日時 |
| `handlingStatus` | 上の表の値、または `null` |
| `messages` | メッセージの配列。なければ空配列 |
| `trace` | スキルの実行記録（下記「スキルの実行記録」）。無効なら `null` |

### 10. `GET /api/inquiries`

自分（現在の利用者）が作った問い合わせの一覧（新しい順、最大50件）。

`200` `{ "unreadReplyCount": 1, "items": [{ "id", "createdAt", "summary", "step", "outcome", "handlingStatus", "unreadReply", "messageCount" }] }`。`summary` は言い直しの文章、なければ元の発話の先頭80文字。

### 11. `POST /api/inquiries/{id}/messages`

リクエスト `{ "body": "1〜4000文字" }`。本人が追記する。`outcome` が `FILED` のときだけ有効（それ以外は `INVALID_STATE`）。

`200` InquiryView。担当者側の `read` を `false` に戻し、オペレータのストリームに event `updated` を流す。

### 9（変更）. `GET /api/inquiries/{id}`

他人の問い合わせは `NOT_FOUND`。取得すると、その問い合わせの `unreadReply` を `false` にする。

### オペレータ向けの追加

- 一覧（`GET /api/operator/inquiries`）の各件に `handlingStatus`、`messageCount` を加える。
- 詳細（`GET /api/operator/inquiries/{id}`）に `handlingStatus`、`messages`、`trace`、`askerName`（投稿者の表示用の名前）を加える。
- `PUT /api/operator/inquiries/{id}/status`: リクエスト `{ "status": "DONE" }`。`200` 詳細と同じ全内容。
- `POST /api/operator/inquiries/{id}/replies`: リクエスト `{ "body": "1〜4000文字" }`。担当者が返信する。`200` 詳細と同じ全内容。本人側の `unreadReply` を `true` にする。
- ストリーム（`GET /api/operator/stream`）に event `updated` を加える。data は一覧の1件と同じ形。本人の追記、対応状況の変更、担当者の返信で流す（別の担当者の画面を揃えるため）。

担当者に送られていない問い合わせへの操作は、従来どおり `NOT_FOUND`。

### 本人向けの画面の URL

| パス | 画面 |
| --- | --- |
| `/` | 新しい問い合わせの入力 |
| `/inquiries` | 自分が送った問い合わせのボード |
| `/inquiries/{id}` | その問い合わせ。途中ならその段階から再開し、終わっていれば経過とやり取りを示す |

問い合わせを作成したら、URL を `/inquiries/{id}` に変える（ページを開き直しても続きから再開できる）。`{id}` が存在しない、形式が崩れている、他人の問い合わせの場合は、「この問い合わせは見つかりませんでした」と示し、新しい問い合わせを書けるようにする。「新しい問い合わせを書く」は `/` に移る。

開き直したときの再開:

| `step` | 画面 |
| --- | --- |
| `INTAKE` | 答え終えた質問（`intake.entries`）の要約と、次の質問（`intake.next`）。入口の答えは1問ごとに保存されるので、続きから再開できる |
| `STRUCTURED` | 答え終えた質問の要約と、言い直しの確認 |
| `ANSWERING` | 進捗ストリームを購読し直す（調査をやり直す） |
| `ANSWERED` | 調べた結果と、結果の確かめ |
| `CLOSED` | 経過の要約、終わりの文言、`FILED` なら対応状況とやり取り |

## スキルの実行記録（trace）

問い合わせの流れのどこで、どのスキルが、どの実装で動いたかを見せるための、開発者向けの情報。設定 `inquiry.trace.enabled`（既定 `true`）で有効にする。本番では `false` にする（内部の言葉とモデルの名前を、本人に見せないため）。`GET /api/config` に `trace`（真偽値）を加える。

`trace` は、スキルの呼び出しを実行した順に並べた配列。

```json
{ "skill": "investigator", "provider": "ollama", "model": "qwen3.6:35b", "promptVersion": "v1",
  "inputTokens": 2663, "outputTokens": 197, "durationMillis": 2921, "failed": false }
```

- `provider` は、その呼び出しがモックなら `mock`、そうでなければ設定上の実装名（`ollama`）。
- `durationMillis` は、呼び出しにかかった時間。この項目を加える前の記録では `null`。
- 照合（根拠の実在の確認）は通常のコードで、スキルではないため、記録には現れない。画面は、調査と説明の間に「照合（コード）」を固定で描く。

## 利用者の表示と仮の文言

- `GET /api/config` に `user`（`{ "id": "local-user", "name": "開発用ユーザー" }`）と `operatorName` を加える。認証を加えるまでは設定の固定値。
- `filedGuidance` の既定を「担当者が内容を確認し、このページに返信します。返信が届くと「送った問い合わせ」に印がつきます。」にする（往復ができたため、連絡方法が決まった）。
