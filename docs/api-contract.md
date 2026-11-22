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
- **状態**: 問い合わせ1件がいまどこにあるか。区間、誰の番か、何を待っているか、終わり方の4つで表す（下記「状態」）

## 状態

問い合わせ1件の状態は、4つの項目で表す。以前の「段階」「結果」「対応状況」は、これに置き換えた。

| 項目 | 値 | 意味 |
| --- | --- | --- |
| 区間（`phase`） | `INTAKE`（受付）／`KNOW`（分かる）／`DECIDE`（決まる）／`ACT`（行われる）／`CLOSED`（終わり） | いま埋めようとしている不足の種類。`framework.md` の区間に、受付と終わりを加えたもの |
| 誰の番か（`turn`） | `ASKER`（本人）／`AI`／`OPERATOR`（担当者）／`OWNER`（仕様の持ち主）。終わった件は `null` | 次に動く人 |
| 待っていること（`awaiting`） | 下の表。終わった件は `null` | 番の人に、何をしてもらうのを待っているか。画面は、これを見て描くものを決める |
| 終わり方（`ending`） | `RESOLVED`（解決済み）／`ANSWERED`（回答済み）／`DECLINED`（見送り）／`WITHDRAWN`（取り下げ）。終わっていない件は `null` | 不足は埋まったか |

仕様の持ち主の番（`OWNER`）になるのは、担当者が相談している間だけ（「仕様の持ち主への相談」の節）。

### 待っていること

| `awaiting` | 番 | 区間 | 意味 | 次の操作 |
| --- | --- | --- | --- | --- |
| `INTAKE_ANSWER` | 本人 | 受付 | 入口の質問に答えている | 質問に答える（3）、前の質問に戻る（3a）、文章で書く（3b） |
| `QUESTION_CONFIRMATION` | 本人 | 受付 | 組み立てた問い（言い直し）を確かめている | 確定（5）、作り直し（5a）、入口の質問へ戻る（5b） |
| `INVESTIGATION` | AI | 分かる | 調べて答えを作っている | 進捗ストリームの購読（6） |
| `RESULT_CONFIRMATION` | 本人 | 分かる、決まる、行われる | 結果（AI の答え、または担当者が伝えた結果）を、本人が確かめている | 結果の確かめ（7） |
| `HANDLING` | 担当者 | 分かる、決まる、行われる | 担当者が受けている | 担当者の操作（「担当者の操作」の節） |
| `ASKER_REPLY` | 本人 | 分かる、決まる、行われる | 担当者が本人に聞き返し、返事を待っている | 追記（11） |
| `CONSULTATION` | 仕様の持ち主 | 分かる、決まる、行われる | 担当者が仕様の持ち主に相談し、決定を待っている | 持ち主の決定、担当者の取り下げ（「仕様の持ち主への相談」の節） |

### 種類ごとの経路

| 種類 | 調べるか | 言い直しを確定した後 |
| --- | --- | --- |
| `HOW_TO`、`HOW_IT_WORKS`、種類が決まっていない | 調べる | 根拠があり、問いに答えている場合は、答えを本人が確かめる（`KNOW`、`RESULT_CONFIRMATION`）。AI が答えられなかった場合（根拠がない、または問いに答えていない）は、答えの完了と同時に、担当者に渡す。本人に、送るかどうかを選ばせない |
| `LOOKS_WRONG` | 調べる | 答えの完了と同時に、担当者に渡す（意図した動きかは、担当者が判断する） |
| `DO_FOR_ME` | 調べる | 根拠があり、問いに答えている（本人が自分でできる手順が見つかった）場合は、本人が確かめる（`KNOW`、`RESULT_CONFIRMATION`）。それ以外は、答えの完了と同時に、担当者に渡す |
| `CHANGE_REQUEST` | 調べない | 確定と同時に、担当者に渡す |

緊急度が `BLOCKED` の問い合わせは、種類にかかわらず、入口の質問を終えた時点で担当者に渡す（言い直しの確認と調査を待たない）。調査は、渡した後に裏で行う（「調査の状況と、裏での調査」の節）。

### 担当者に渡す

「担当者に渡す」とは、番を担当者にすること（`turn: OPERATOR`、`awaiting: HANDLING`）。問い合わせは終わらない。

- 渡したときの区間は、種類で決まる。`HOW_TO`、`HOW_IT_WORKS`、種類が決まっていない場合は `KNOW`（知識の不足のまま、担当者が答える）。`LOOKS_WRONG`、`DO_FOR_ME`、`CHANGE_REQUEST` は `DECIDE`（意図どおりか、受けるか、採るかを決める）。
- 渡した理由を `handoverReason` に持つ（直近に渡したときの理由。InquiryView と担当者向けの詳細が返す。渡したことがなければ `null`）。

  | `handoverReason` | 意味 |
  | --- | --- |
  | `SENT_NOW` | 本人が、答えを待たずに送った（8） |
  | `UNRESOLVED` | 本人が、解決していないと答えた（7。結果への追記を含む） |
  | `BLOCKED` | 業務が止まっている |
  | `ROUTE` | 種類の経路（`LOOKS_WRONG`、`DO_FOR_ME`、`CHANGE_REQUEST`） |
  | `NO_ANSWER` | AI が答えられなかった（`HOW_TO`、`HOW_IT_WORKS`、種類が決まっていない場合） |
  | `TIMEOUT` | 調査が時間内に終わらなかった |
  | `REOPENED` | 本人が、終わった件に追記した |
  | `DECIDED` | 仕様の持ち主が、相談に答えて決めた（担当者の番に戻った） |

- 初めて渡したときに、担当者に通知する（「オペレータへの通知」の節）。その時刻を `handedOverAt` に持つ。2回目以降は通知しない（担当者のボードには、番が戻ったことが即時に届く）。
- 担当者の側の未読（`read`）を `false` にする。

### 本人が結果を確かめる

答えたのが AI でも担当者でも、結果は本人が確かめる（7）。

| 本人の答え | その後 |
| --- | --- |
| 解決した | 終わる（`phase: CLOSED`、`ending: RESOLVED`） |
| 解決していない | 担当者に渡す。区間は、AI の答えの後なら上の決まり、担当者の結果の後なら変えない |

`DO_FOR_ME` で AI が手順を示した場合、「解決した」は自分で行えたこと、「解決していない」は担当者に頼むことを意味する。

本人が確かめないまま期間が過ぎた件は、下の「返事がないとき」の決まりで終わる。

### 返事がないとき

本人の返事を待っている件は、返事がないまま期間が過ぎると、1度知らせたうえで終わる。担当者も本人も動かなくてよいのに、件が「本人の番」に残り続けることを防ぐ。終わり方は、何を待っていたかで決まる。

| 待っていること | 終わり方 | 理由 |
| --- | --- | --- |
| 結果を本人が確かめる（`RESULT_CONFIRMATION`）。答えたのが AI でも担当者でも同じ | `ANSWERED`（回答済み） | 結果は伝えた。不足が埋まったかは分からない |
| 担当者の聞き返しへの返事（`ASKER_REPLY`） | `WITHDRAWN`（取り下げ） | 答えを返す前に、本人が離れた |

知らせることと、終えることの決まりは、どちらも同じ。

| 順 | 条件 | 起きること |
| --- | --- | --- |
| 1. 知らせる | 本人の返事を待つ状態になってから、知らせるまでの期間が過ぎた。まだ知らせていない | 知らせた時刻（`remindedAt`）を持つ。本人側の未読（`unreadReply`）を `true` にする。状態は変えない |
| 2. 終える | 知らせてから、終えるまでの期間が過ぎた | 終わる（`phase: CLOSED`。`ending` は上の表のとおり）。終わらせたのが時間であることを持つ（`closedBy: TIME`）。本人側の未読を `true` にする。担当者に渡したことのある件なら、担当者のストリームに `updated` を流す（担当者の側の未読は変えない） |

- 起点は、本人の返事を待つ状態になった時刻（`awaitingSince`）。本人が問い合わせを開いただけでは変わらない。本人が答える（7）か追記する（11）と状態が変わり、この決まりの対象から外れる。担当者がもう一度結果を伝える、または聞き返すと、起点も、知らせたかどうかも、最初からになる。
- 終えるまでの期間は、実際に知らせた時刻から数える。1回の判定で、同じ件を知らせて続けて終えることはしない（サーバーが止まっていた後でも、知らせずに終えない）。
- 期間は営業日で数える。営業日は月曜から金曜（`inquiry.rules.zone` の時間帯で判定する）。祝日は扱わない。N 営業日後は、起点の時刻から日を1日ずつ進め、進めた先が営業日のときだけ数えて、N 回数えた日の同じ時刻。
- 知らせは、ツールの中だけで行う（本人のボードの未読と、問い合わせの画面の文言）。Slack には投稿しない。本人を特定して外から届ける経路（Slack の個人宛てなど）は、SSO の後に加える。
- 入口の途中の件は、この決まりの対象にしない（別の決まりとして後で加える）。
- この決まりで終わった件は、本人が追記すれば開き直せる（11）。`ANSWERED` で終わった、担当者に渡したことのない件（AI の答えに返事がなかった件）は、追記すると担当者に初めて渡る。時間で `WITHDRAWN` になった件も開き直せる（本人が自分で取り下げた件は、開き直せない）。

設定:

| キー | 既定 | 意味 |
| --- | --- | --- |
| `inquiry.rules.zone` | `Asia/Tokyo` | 営業日を判定する時間帯 |
| `inquiry.rules.no-reply.remind-after-business-days` | `5` | 知らせるまでの営業日の数 |
| `inquiry.rules.no-reply.close-after-business-days` | `5` | 知らせてから、終えるまでの営業日の数 |
| `inquiry.rules.sweep-enabled` | `true` | 決まりの判定を、定期的に行うか |
| `inquiry.rules.sweep-interval` | `15m` | 判定の間隔 |

判定は、定期的な実行（`sweep-interval` ごと）で行う。判定そのもの（どの件を知らせ、どの件を終えるか）と営業日の計算は、Spring に依存しないコード（`core/lifecycle`）に置き、現在時刻を引数で受け取る。同じ件を2つの実行が同時に変えようとした場合は、片方だけが通り、もう片方は次の回に見直す。

InquiryView と担当者向けの詳細は、`noReply` を持つ。

```json
"noReply": { "endsAs": "ANSWERED", "remindAt": "2026-11-27T03:00:00Z", "remindedAt": null, "closesAt": null }
```

- `awaiting` が `RESULT_CONFIRMATION` か `ASKER_REPLY` のときだけ非 null。それ以外は `null`。
- `endsAs` は、期間が過ぎたときの終わり方。`ANSWERED` か `WITHDRAWN`。
- `remindAt` は、知らせる予定の時刻。`remindedAt` は、実際に知らせた時刻（まだなら `null`）。`closesAt` は、終わる予定の時刻（知らせた後だけ非 null）。

### 開発用: 決まりの判定を、時刻を指定して行う

`POST /api/dev/rules/run`。リクエスト `{ "now": "2026-12-01T00:00:00Z" }`（`now` は省略でき、省略すると現在時刻）。与えた時刻を現在として、上の判定を1回行う。知らせた時刻（`remindedAt`）や履歴の時刻にも、与えた時刻を使う。

`200` `{ "reminded": 1, "closed": 0 }`（知らせた件数と、終えた件数）。

手元とスモークで、期間が過ぎるのを待たずに確かめるための口。`production` プロファイルでは存在しない（`404`）。

## InquiryView

すべてのエンドポイント（1を除く）が返す、問い合わせ1件の表現。

```json
{
  "id": "0b9c…(UUID)",
  "createdAt": "2026-11-22T03:00:00Z",
  "language": "ja",
  "phase": "INTAKE",
  "turn": "ASKER",
  "awaiting": "INTAKE_ANSWER",
  "ending": null,
  "closedBy": null,
  "declined": null,
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
  "handedOverAt": null,
  "handoverReason": null,
  "unreadReply": false,
  "noReply": null,
  "messages": [],
  "trace": [],
  "mock": true
}
```

- `type`、`system`、`function` は、決まっていなければ `null`。`by` は `ASKER`（本人が選んだ）、`AI`（AI が置いた）、`OPERATOR`（担当者が置き直した）のいずれか。本人が「わからない」と答え、AI も置けなかった場合は `null` のまま。
- `utterance` は、本人が書いた文章。文章で始めた場合の本文、文章に切り替えたときの本文、補足のいずれか。複数ある場合は、書いた順に空行でつなぐ。何も書いていなければ `null`。
- `intake.entries` は、答え終えた質問（聞いた順）。`intake.next` は、次に答える質問。入口が終わっていれば `null` で、`complete` が `true`。
- `intake.entries[].answer.kind` は `OPTION`（選択肢を選んだ。`optionId` と `label`）、`TEXT`（文章で答えた。`text`）、`UNKNOWN`（わからない）、`SKIPPED`（補足を書かずに進んだ）、`INFERRED`（本人が書いた文章から AI が読み取った。選択肢なら `optionId` と `label`、文章で答える質問なら `text`）のいずれか。
- `intake.next.kind` は `TYPE`、`SYSTEM`、`FUNCTION`、`DETAIL`、`NOTE` のいずれか。`options` が空の質問は文章で答える。`allowsText` が `true` の質問は、選択肢のほかに文章でも答えられる。`canSkip` は `NOTE` だけが `true` になりうる。「わからない」は、`NOTE` 以外のすべての質問で選べる（画面が常に出す）。
- `phase`、`turn`、`awaiting`、`ending` は「状態」の節のとおり。`declined` は、見送りで終わった件だけが持つ（`{ "reasonKind": "KNOWN_LIMITATION", "reason": "理由の文章" }`）。`handedOverAt` は、担当者に初めて渡した時刻（渡していなければ `null`）。
- `structured` は、問いを組み立てた後（`awaiting` が `QUESTION_CONFIRMATION` になった時点）から非 null。項目は `summary`（本人に見せる言い直しの文章。必須）、`targetSystem`、`goal`、`whatHappened`、`expectedResult`。`urgency` は `structured` から外し、問い合わせの `urgency` に一本化する。
- `answer` は、答えの完了後に非 null。項目は `grounded`、`answered`、`askerExplanation`、`steps`、`memo`、`citations`。`steps` は、本人が自分で行える手順（`[{ "action": "求人管理画面で、対象の求人の編集を開く", "check": "編集の画面が開く" }]`。行う順。`check` は結果の確かめ方で、なければ `null`）。手順がなければ空配列。`answered` が `false` のときは、常に空配列。裏での調査の結果（担当者だけに見せるもの）は、本人向けの InquiryView では `null` のまま（「調査の状況と、裏での調査」の節）。
- `handoverReason` は、直近に担当者へ渡した理由（「担当者に渡す」の節）。
- `canSendNow` は、いま「答えを待たずに担当者へ送る」（8）を実行できるか。
- `noReply` は、本人の返事を待っている間（結果の確かめ、聞き返しへの返事）だけ非 null（「返事がないとき」の節）。
- `closedBy` は、問い合わせを終わらせた人。`ASKER`、`OPERATOR`、`TIME`（返事がないまま期間が過ぎた）のいずれか。終わっていない件は `null`。開き直すと `null` に戻る。
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
- **設定の質問の答えが、文章に既に書かれていれば、聞かない。** マスの設定に質問があり、本人が文章を書いている場合は、聞き手のスキルが、設定の質問のうち文章に答えが書かれているものを読み取る。読み取れた質問は、答え終えた質問として `entries` に入れ（`answer.kind: INFERRED`）、聞かない。読み取れなかった質問だけを聞く。
  - 選択肢のある質問: 聞き手が返した選択肢が、その質問の選択肢にあれば採る（`optionId` と `label`）。なければ、読み取れなかったものとして聞く。
  - 文章で答える質問: 聞き手が返した答えが、本人の文章の中にそのまま含まれていれば採る（空白の違いは無視する。`answer` は `INFERRED` で、`text` に答えを入れる）。含まれていなければ、読み取れなかったものとして聞く。AI が文章にない答えを作るのを防ぐため。
  - 聞き手が失敗した場合は、設定の質問をすべて聞く（エラーにしない）。
  - 読み取った答えは、ほかの答えと同じく、押すとその質問に戻って答え直せる。
  - 途中で文章に切り替えた場合（3b）は、まだ答えていない設定の質問について、同じ読み取りを行う。本人が既に答えた質問は変えない。

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

設定の文言（種類、システム、機能、質問、選択肢の名前、`filed-guidance`）は、言語ごとの名前の対応で持つ（`{ ja: …, en: … }`）。API が返す `label` と `text` は、見る人の言語の名前（次の「言語」の節）。

## 言語

画面と API は、複数の言語に対応する。使える言語は設定 `inquiry.languages`（既定は `ja`、`en`）で、先頭が既定の言語。

### 文章の4つの種類

どの言語で出すかは、文章の種類で決まる。

| 種類 | 例 | 言語 |
| --- | --- | --- |
| 画面の固定の文言 | ボタン、見出し、状態を表す言葉 | 見る人の言語。画面が持つ |
| 設定の名前と、API が組み込みで持つ文言 | 種類、緊急度、システム、機能、マスの質問と選択肢、`filedGuidance`、入口の決まった質問 | 見る人の言語。API が返す |
| 書かれた内容 | 本人の文章、AI が書いた質問・言い直し・事実・説明、担当者の発言、見送りの理由 | 書かれたときの言語のまま。訳さない |
| 資料の引用 | 根拠の `quote`、資料の題名と場所 | 資料の原文のまま |

### 見る人の言語

リクエストごとに決まる。

- `Accept-Language` ヘッダーに書かれた順に、使える言語と比べる（`en-US` は `en` として比べる）。最初に当てはまった言語を使う。
- 当てはまる言語がなければ、既定の言語。
- ヘッダーを付けられない接続（6 と `GET /api/operator/stream`）は、クエリの `lang` で渡す。`lang` があれば、ヘッダーより優先する。
- その言語の名前が設定にない項目は、項目ごとに、既定の言語の名前で返す。

見る人の言語で返すもの:

- `GET /api/config` の `types`、`urgencies`、`systems`、`filedGuidance`
- InquiryView と一覧の、種類・緊急度・システム・機能の `label`
- `intake` の質問文、選択肢、答えの名前。設定から来たものと、入口の決まった質問（下の表）。AI が作った質問は「書かれた内容」なので、作ったときの言語のまま
- 置き場所の名前から作る `summary`（言い直しも本人の文章もない件。名前の区切りは、`ja` が「・」、`en` が「 / 」）と、名前もない件の `summary`（`（内容なし）`／`(No content)`）。言い直しや本人の文章から作る `summary` は「書かれた内容」なので、書かれたときの言語のまま

| 入口の決まった質問 | `ja` | `en` |
| --- | --- | --- |
| 種類 | 何をしたいですか | What can we help you with? |
| システム | どのシステムですか | Which system is it about? |
| 機能 | どの機能ですか | Which function is it about? |
| 補足（任意） | 補足があれば書いてください | Add anything else you would like to tell us |
| 補足（必須） | 状況を一言で教えてください | Tell us briefly what is happening |

### 本人の言語（`language`）

問い合わせが持つ。InquiryView、本人の一覧（10）、担当者の一覧と詳細に `language`（`"ja"` など）として返す。

- 作成（2）のときの、見る人の言語で決まる。
- その後、本人が行う状態を変えるリクエスト（3〜5b、7、8、11、12）のたびに、そのリクエストの言語に合わせ直す。本人が途中で画面の言語を切り替えた場合に、次に AI が書くものから新しい言語になる。
- 取得（GET）と、担当者の操作では変わらない。
- AI は、本人の言語で書く。対象は、聞き手が作る質問と選択肢、言い直し（文章と `structured` の各項目）、調査の事実と `memo`、説明、調べている間の進捗の文言。
- 既に書かれたものは、本人の言語が変わっても書き直さない。
- `structured.targetSystem` に入れるシステムの名前は、本人の言語の名前。

### 言語を切り替えないもの

- エラーの `message`。開発者向けで、画面は `code` から文言を決める。
- 状態の履歴の `detail`（置き直しの前後の名前）。既定の言語の名前を、置き直した時点で保存する。
- Slack への通知。既定の言語で書く。
- `GET /api/config` の `user.name` と `operatorName`（認証を加えるまでの固定値）。

## エンドポイント

### 1. `GET /api/config`

`200`

```json
{
  "mock": true,
  "skills": { "reception": "mock", "interviewer": "mock", "restater": "mock", "investigator": "mock", "explainer": "mock", "advisor": "mock", "drafter": "mock", "translator": "mock" },
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

`types` は5つを、聞く順（上の用語の順）で返す。`incidentGuidance` は廃止。`types`、`urgencies`、`systems`、`filedGuidance` は、見る人の言語で返す（「言語」の節）。`languages` は、使える言語の一覧（先頭が既定の言語）。

### 2. `POST /api/inquiries`

リクエスト `{}`、または `{ "text": "1〜4000文字", "urgency": "HURRY" }`。どちらの項目も省ける。

`201` InquiryView（`awaiting: INTAKE_ANSWER`）。

- `text` がない: 最初の質問（種類）が `intake.next` に入る。AI は呼ばない。
- `text` がある: 「文章で書く」の決まりで置き場所を置き、残りの最初の質問を `intake.next` に入れる。残りの質問がなければ、そのまま問いを組み立てて `awaiting: QUESTION_CONFIRMATION` にする。
- `urgency` を省くと `NORMAL`。

AI が失敗しても、この操作はエラーにしない（受付と聞き手の失敗は、質問を聞く形に置き換わる。言い直しの失敗は下の3と同じ扱い）。

### 3. `POST /api/inquiries/{id}/intake/answers`

`intake.next` の質問に答える。`awaiting` が `INTAKE_ANSWER` のときだけ有効。リクエストは次のいずれか。

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

- 次の質問があれば、`intake.next` に入る（`awaiting: INTAKE_ANSWER`）。
- 最後の質問に答えた場合は、言い直しのスキルが問いを組み立て、`awaiting: QUESTION_CONFIRMATION` にする。
- 最後の質問に答え、緊急度が `BLOCKED` の場合は、問いを組み立てたうえで担当者に渡す（`sentImmediately: true`）。言い直しが失敗した場合も、組み立てなしで同じく担当者に渡す。
- 言い直しのスキルが失敗した場合（`BLOCKED` 以外）は、答えを保存したうえで、エラー（`AI_REFUSED` は422、`AI_UNAVAILABLE` は503）を返す。本文に `inquiryId` を含める。`awaiting` は `INTAKE_ANSWER` のまま、`intake.complete` は `true`、`canSendNow` は `true` になる。エラーの本文には状態を含めないので、画面は `inquiryId` で取得（9）し直して、状態を反映する。同じ操作をやり直す方法は、5b（入口の質問へ戻る）ではなく、`POST /api/inquiries/{id}/intake/compose`（本文なし。問いの組み立てだけをやり直す。`intake.complete` が `true` で `awaiting` が `INTAKE_ANSWER` のときだけ有効）。

### 3a. `POST /api/inquiries/{id}/intake/back`

リクエスト `{ "questionId": "system" }`。答え終えた質問に戻る。`awaiting` が `INTAKE_ANSWER` のときだけ有効。

`200` InquiryView。指定した質問と、それより後の答えを捨て、指定した質問を `intake.next` にする。捨てた答えで決まっていた置き場所も、決まっていない状態に戻す。`entries` にない `questionId` は `VALIDATION`。本人が書いた文章（`utterance`）は消さない。

### 3b. `POST /api/inquiries/{id}/intake/text`

リクエスト `{ "text": "1〜4000文字" }`。文章で書く道に切り替える。`awaiting` が `INTAKE_ANSWER` のときだけ有効。

`200` InquiryView。「文章で書く」の決まりで、まだ答えていない置き場所を置く。答えかけの質問（`intake.next`）は、置けなければ改めて聞く。

### 4. `PUT /api/inquiries/{id}/urgency`

リクエスト `{ "urgency": "BLOCKED" }`。本人が緊急度を変える。`awaiting` が `INTAKE_ANSWER` か `QUESTION_CONFIRMATION` のときだけ有効。

`200` InquiryView。入口の質問の途中で `BLOCKED` に変えた場合、まだ答えていない詳しい質問は聞かない（`intake.next` が詳しい質問なら、補足に進める）。言い直しを確かめている途中で `BLOCKED` に変えた場合は、その時点の言い直しで担当者に渡す（`sentImmediately: true`）。

### 5. `PUT /api/inquiries/{id}/structured`

リクエスト `{}` または `{ "summary": "本人が書き直した文章" }`。言い直しを確定する。`awaiting` が `QUESTION_CONFIRMATION` のときだけ有効。

- 本文が空（`summary` なし）: AIの言い直しをそのまま確定する（「合っています」）
- `summary` あり（1〜2000文字）: 本人が書き直した文章で確定する。確定値の `summary` だけをこの文章に置き換え、ほかの項目はAIの値を残す。0次回答は `summary` を問いの本文として扱う

`200` InquiryView。

- `CHANGE_REQUEST`: 担当者に渡す（区間は `DECIDE`）
- それ以外: `phase: KNOW`、`turn: AI`、`awaiting: INVESTIGATION`

### 5a. `POST /api/inquiries/{id}/revision`

リクエスト `{ "note": "消えたのは一昨日から" }`（1〜1000文字）。本人が違う点を一言で伝え、AIが言い直しを作り直す。`awaiting` が `QUESTION_CONFIRMATION` で `revisionsLeft` が1以上のときだけ有効。残りが0なら `INVALID_STATE`。

`200` InquiryView（`awaiting: QUESTION_CONFIRMATION`、`structured` が新しい言い直し、`revisionsLeft` が1減る）。伝えられた一言は記録に残す。AIが失敗した場合（`AI_REFUSED`／`AI_UNAVAILABLE`）は残り回数を減らさない。

### 5b. `POST /api/inquiries/{id}/reopen`

本文なし。入口の質問へ戻る。`awaiting` が `QUESTION_CONFIRMATION` のときだけ有効。

`200` InquiryView（`awaiting: INTAKE_ANSWER`、`structured` は `null`）。最後に答えた質問を `intake.next` に戻し、その答えを捨てる（3a で最後の質問を指定したのと同じ）。`revisionsLeft` は元に戻さない。

### 6. `GET /api/inquiries/{id}/answer-stream`

`text/event-stream`。`awaiting` が `INVESTIGATION` のときに0次回答を生成し、進捗と結果を流す。

| event | data | 意味 |
| --- | --- | --- |
| `progress` | `{ "message": "コードを検索しています" }` | 進捗。0回以上 |
| `done` | InquiryView | 完了。この後ストリームを閉じる |
| `failed` | `{ "code": "AI_REFUSED", "message": "…" }` | 失敗。この後ストリームを閉じる |

- 完了時: 種類ごとの経路の表のとおり。`LOOKS_WRONG` は担当者に渡す（`handoverReason: ROUTE`）。それ以外の種類は、`answer.grounded` と `answer.answered` がともに `true` なら本人が確かめ（`turn: ASKER`、`awaiting: RESULT_CONFIRMATION`）、そうでなければ担当者に渡す（`DO_FOR_ME` は `ROUTE`、ほかは `NO_ANSWER`）。担当者に渡した場合も、`done` の InquiryView の `answer` には結果が入る（本人は、調べた結果を見たうえで、引き継がれたことを知る）。
- **時間切れ**: 調査が `inquiry.rules.answer-timeout`（既定 `60s`）を超えたら、その時点で担当者に渡し（`handoverReason: TIMEOUT`。履歴の出来事は `HANDED_OVER`、起こしたのは `TIME`）、`done` を流してストリームを閉じる。この InquiryView の `answer` は `null`。調査は打ち切らずに裏で続け、結果は担当者だけに渡す（「調査の状況と、裏での調査」の節）。長く待たせた末に、失敗を見せないため。時間切れの前に失敗した場合は、従来どおり `failed` を流す。
- 失敗時の `code`: `AI_REFUSED`／`AI_UNAVAILABLE`／`INVALID_STATE`（`awaiting` が `INVESTIGATION` でない、または同じ問い合わせの生成が進行中）。失敗しても `awaiting` は `INVESTIGATION` のままで、再購読による再試行と「答えを待たずに担当者へ送る」（8）ができる。
- 画面は `done` か `failed` を受けたら接続を閉じる（EventSource の自動再接続を止める）。

### 7. `POST /api/inquiries/{id}/resolution`

リクエスト `{ "resolved": true }`、または `{ "resolved": false, "note": "まだ解決していない点（任意。1〜4000文字）" }`。本人が結果を確かめる。`awaiting` が `RESULT_CONFIRMATION` のときだけ有効。

`200` InquiryView。「本人が結果を確かめる」の決まりのとおり。`note` があれば、本人の追記としてメッセージに加える。画面が `note` を付けるのは、担当者の結果を確かめるときだけ（AI の答えを確かめるときは付けない）。API は、どちらの場合も受け付ける。`resolved` が `true` のときに `note` を付けると `VALIDATION`。

### 8. `POST /api/inquiries/{id}/send-now`

本文なし。答えを待たずに担当者へ送る。**問いを確かめた後に限る。** 入力が足りないまま担当者に渡すと、聞き返しが増えるだけになるため。

実行できるのは、`canSendNow` が `true` のとき。次のいずれか。

- `awaiting` が `INVESTIGATION`、または AI の答えを確かめている `RESULT_CONFIRMATION`（言い直しを確定した後。担当者に渡したことがない場合に限る）
- AI の失敗で先へ進めない（3 で言い直しが失敗し、`intake.complete` が `true` のまま `awaiting` が `INTAKE_ANSWER` にある）。こちらの失敗を理由に、本人を止めない
- `awaiting` が `QUESTION_CONFIRMATION` で、`revisionsLeft` が 0（言い直しを2回作り直しても合わなかった）。汲み取れなかったのはこちらの側なので、本人を止めない

それ以外は `INVALID_STATE`。

`200` InquiryView。担当者に渡す（`sentImmediately: true`）。

答えを待たずに送った後も、AI の調査は裏で行い、結果を担当者に渡す（「調査の状況と、裏での調査」の節）。`handoverReason` は `SENT_NOW`。

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

英語の文章のために、次の語も同じ優先順位で見る（大文字と小文字を区別しない）: `CHANGE_REQUEST` は `feature request`、`improve`。`DO_FOR_ME` は `please`、`could you`。`LOOKS_WRONG` は `bug`、`wrong`、`broken`。`HOW_TO` は `how to`、`how do i`。

システムは、文章に設定のシステムの名前（どの言語の `label` でも。英字は大文字と小文字を区別しない）が含まれていれば、そのシステム。機能は、システムが決まっていて、文章にそのシステムの機能の名前が含まれていれば、その機能。含まれていなければ置かない（質問として聞く）。緊急度は、文章からは決めない。

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
| `[遅延]` | 調査 | 進捗の各段階で待つ（既定2秒、設定 `inquiry.mock.slow-step-delay` で変更）。進捗は3段階なので、既定では6秒かかる。時間切れと裏での調査を確かめるときは、`inquiry.rules.answer-timeout` をこれより短くする |
| `[エラー]` | 調査 | 進捗を流した後に `AI_UNAVAILABLE` |
| `[拒否]` | 調査 | 進捗を流した後に `AI_REFUSED` |
| `[説明エラー]` | 説明 | 調査と照合は成功し、説明が `AI_UNAVAILABLE` で失敗する。調査の結果は保存される |
| `[的外れ]` | 説明 | 調査は通常の事実を返し、説明が「問いに答えていない」と判定する。根拠あり（`grounded: true`）で `answered: false` になる |
| `[手順なし]` | 説明 | 手順（`answer.steps`）を返さない（タグがなければ、種類が `HOW_TO` か `DO_FOR_ME` のときに手順を2つ返す） |
| `[相談]` | 助言 | 判断の案の `needsConsultation` を `true` にし、理由を添える |
| `[手順]` | 助言 | 種類にかかわらず、手順の案を返す（タグがなければ、手順の案を返すのは種類が `DO_FOR_ME` と `CHANGE_REQUEST` のときだけ） |
| `[根拠なしの案]` | 助言 | 判断の案の `basedOn` に、存在しない番号だけを入れて返す。コードが案を捨て、`judgment` は `null` になる |
| `[案エラー]` | 助言 | 助言が `AI_UNAVAILABLE` で失敗する。`advice.status` は `FAILED` |
| `[下書きエラー]` | 下書き | 下書きが `AI_UNAVAILABLE` で失敗する |
| `[訳エラー]` | 訳 | 訳が `AI_UNAVAILABLE` で失敗する（タグは、本人が書いた文章から読む） |

### 説明が出す手順

モックの説明は、種類が `HOW_TO` か `DO_FOR_ME` のとき、説明の文章に加えて、手順を2つ返す（`[手順なし]` を付けた場合と、`[的外れ]` の場合は返さない）。

| 言語 | 1つ目 | 2つ目 |
| --- | --- | --- |
| `ja` | `action`「求人管理画面で、対象の求人の編集を開く」、`check` は `null` | `action`「掲載期限を変えて、保存する」、`check`「応募者向けの一覧に、求人が表示される」 |
| `en` | `action` "Open the edit screen for the job posting in the job admin screen"、`check` は `null` | `action` "Change the posting deadline and save"、`check` "The job posting appears in the list for applicants" |

### 助言と下書き

- **助言**: タグがなければ、判断の案を1つ返す（1件目の事実に基づく。`needsConsultation` は `false`。文章は「資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。」）。種類が `DO_FOR_ME` か `CHANGE_REQUEST` のときは、手順の案も返す（手順2つと、元に戻す方法。最後の事実に基づく）。
- **下書き**: 種類（`kind`）と本人の言語で、決まった文章を返す。`note` があれば、文章の中にそのまま入れる。
  - `ASK`、`ja`: 「確認させてください。（note、なければ「起きている場面を、もう少し詳しく教えていただけますか。」）」
  - `RESULT`、`ja`: 「お待たせしました。確認した結果をお伝えします。（note、なければ判断の案の文章。それもなければ1件目の事実の文）」
  - `en` は、同じ組み立ての英語（"Let me check one thing. …"、"Thank you for waiting. Here is what we found. …"）。

### 訳

モックの訳は、訳さない。原文の前に、訳の見本であることを示す印を付けて返す（訳す先が `ja` なら「【訳の見本】」、`en` なら "[Sample translation] "）。骨組みでは、訳がどこに出るかを確かめられれば足りる。

### 聞き手（設定に質問があり、文章が書かれている場合）

モックの聞き手は、設定の質問のうち、選択肢の名前（どの言語の `label` でも。英字は大文字と小文字を区別しない）が文章にそのまま含まれているものを、その選択肢で読み取る。複数の選択肢が含まれていれば、設定の順で先のもの。文章で答える質問は、読み取らない。`[質問エラー]` では `AI_UNAVAILABLE` で失敗し、設定の質問をすべて聞く。

### 聞き手（設定に質問がない場合）

モックの聞き手は、質問を1問返す。id は `q1`、文言は「どの場面での話ですか」、選択肢は「画面を開いたとき」「保存したとき」「一覧を見たとき」（選択肢の id は `o1`〜`o3`）。本人の言語が `en` なら、文言は「In what situation does this happen?」、選択肢は「When opening a screen」「When saving」「When viewing a list」。

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

本人の言語が `en` のときは、同じ組み立てを英語で行う。

1. 置き場所の前置き: 「About （機能） in （システム）: 」。システムだけなら「About （システム）: 」
2. 種類ごとの文（下の表）
3. 「What was written: （文章）」
4. 「(（質問文）: （答え）)」
5. 「Note: （一言）」

| 種類 | 種類ごとの文（`en`） |
| --- | --- |
| `HOW_TO` | I want to know how to use it. |
| `HOW_IT_WORKS` | I want to know how it currently works. |
| `LOOKS_WRONG` | Something may be behaving incorrectly. I want to check whether this is the intended behavior. |
| `DO_FOR_ME` | I would like a task to be done. |
| `CHANGE_REQUEST` | I would like something to be changed. |
| 決まっていない | I have something to ask about. |

前置きと答えに入れる名前（システム、機能、質問文、選択肢）は、本人の言語の名前。

モックの言い直しは、本人が書いた文章を `goal` にそのまま入れる（文章がなければ、種類ごとの文）。調査と説明のモックは、タグを `goal` から読む（`goal` は書き直しや作り直しで変わらないため、タグの振る舞いは最後まで保たれる）。

モックの説明は、渡された事実の文をつないで作る（「（事実1）。（事実2）。」の形。`LOOKS_WRONG` では先頭に「現在の実装では、」を付け、末尾に「この動作が意図した仕様かどうかは、担当者が確認します。」を足す）。渡されなかった事実の内容が説明に現れないことを、テストで確かめられる形にする。

本人の言語が `en` のとき、モックの調査は事実、`memo`、進捗の文言を英語で返し、モックの説明は英語でつなぐ（「（事実1）. （事実2）.」。`LOOKS_WRONG` では先頭に「In the current implementation: 」を付け、末尾に「Whether this behavior is intended will be confirmed by the support team.」を足す）。正常系の2つの事実は「A job posting is treated as expired from the exact time of its posting deadline」と「Expired job postings are not shown in the list for applicants」。ほかの文面（根拠のない事実、`memo`、進捗）は、日本語の文面と同じ意味の英語にする。根拠の引用は、どちらの言語でも資料の原文のまま。

モックの一次情報は `backend/src/main/resources/mock-knowledge/` の固定ファイル（サンプルのコード、FAQ、用語対応表）。正常系の出典はこのファイルの実在する行を指し、出典の照合は実際に動く。

## 既定の設定

既定の設定（`application.yaml`）は、骨組みであることが分かる仮の名前にする。実際の一覧は、環境ごとの設定で差し替える。

| 項目 | 既定 |
| --- | --- |
| 言語 | `ja`、`en`（既定は `ja`） |
| 種類（`en`） | How to use something（Steps for an operation）、How something works（Why it behaves the way it does）、Something looks wrong（It does not behave as expected）、Do something for me（Fix data or change a setting）、Change something（Add or improve a feature）。括弧の中は説明文（`hint`） |
| 緊急度（`en`） | Normal、Urgent、Work is blocked |
| `consultants` | 全体の相談先。「仕様の持ち主（開発用）」の1人 |
| `filed-guidance` | `ja` は「担当者が内容を確認し、このページに返信します。返信が届くと「送った問い合わせ」に印がつきます。」。`en` は「The support team will review this and reply on this page. When a reply arrives, a mark appears in “My inquiries”.」 |
| システム | `system-a`（システムA。機能は `a-1`（機能A-1）、`a-2`（機能A-2））、`system-b`（システムB。機能は `b-1`（機能B-1））、`system-c`（システムC。機能の設定なし） |
| マスの設定 | 1件。`system-a` の `LOOKS_WRONG` に、質問「いつから起きていますか」（選択肢は、今日から、今週から、もっと前から） |

確定したシステムの名前（本人の言語の `label`）を、`structured.targetSystem` に入れる。

API のテストは、業務らしい名前の一覧をテスト用の設定（`backend/src/test/resources/config/application.yaml`）で与えて行う。名前から置き場所を置く動きを、意味のある名前で確かめるため。システムは `job-admin`（求人管理画面。機能は `posting`（求人の掲載）、`applicants`（応募者の一覧））、`billing`（請求管理。機能は `invoice`（請求書））、`reports`（レポート。機能の設定なし）。マスの設定は、`job-admin` の `LOOKS_WRONG` に質問「いつから起きていますか」（選択肢つき）と、`billing` の `invoice` の `DO_FOR_ME` に、文章で答える質問「対象の取引先はどこですか」。

## オペレータへの通知

問い合わせを担当者に初めて渡した時点で、オペレータに2つの方法で知らせる。担当者に渡らずに終わった件（本人が AI の答えで解決した、取り下げた）では通知しない。

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

担当者に渡したことのある問い合わせの一覧。クエリ `unreadOnly=true` で未読だけに絞る。

`200` `{ "unreadCount": 2, "items": [{ "id", "filedAt", "language", "type": { "id", "label" } | null, "system": { "id", "label" } | null, "function": { "id", "label" } | null, "urgency", "summary", "sentImmediately", "read": false, "phase", "turn", "awaiting", "ending", "messageCount", "consultants" }] }`。`filedAt` は、担当者に初めて渡した時刻（`handedOverAt` と同じ）。`consultants` は、相談している間（`awaiting: CONSULTATION`）の相談先の名前の配列（決定を待っている相談の `to`）。相談していなければ空配列。`summary` は言い直しの文章、なければ本人が書いた文章の先頭80文字、それもなければ置き場所の名前（種類、システム、機能を「・」でつないだもの）。並びは新しい順。ただし緊急度が `BLOCKED` の件を先頭に寄せる。

### `GET /api/operator/inquiries/{id}`

`200` 問い合わせの全内容。`id`、`filedAt`、`language`（本人の言語）、`askerId`、`askerName`、`utterance`、`urgency`、`type`、`system`、`function`（それぞれ InquiryView と同じ形。`by` を含む）、`intake`（InquiryView の `intake.entries` と同じ形の配列。本人が何を選び、何に「わからない」と答えたかを、聞いた順に示す）、`structured`、`revisionNotes`、`answer`（InquiryView と同じ形。ただし `citations` は照合に落ちたものも含め、各出典に `verified` を付ける）、`sentImmediately`、`read`、`slackStatus`、`phase`、`turn`、`awaiting`、`ending`、`closedBy`、`declined`、`handoverReason`、`investigation`（「調査の状況と、裏での調査」の節）、`advice`（「AI の案」の節）、`defaultConsultants`・`consultations`・`decisions`（「仕様の持ち主への相談」の節）、`noReply`（「返事がないとき」の節）、`messages`、`history`（「状態の履歴」の節）、`trace`。担当者に渡したことのない問い合わせは `NOT_FOUND`。

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

仕様の持ち主の画面は `/owner`（決定を待っている相談の一覧）と `/owner/{id}`（その問い合わせの材料と、決定の入力）。`{id}` が存在しない、または相談したことがない場合は、一覧を表示したうえで「この問い合わせは見つかりませんでした」と示す。

一覧で問い合わせを選ぶと URL が `/operator/{id}` に変わり、ブラウザの「戻る」で前の選択に戻れる。`{id}` が存在しない、または担当者に渡したことがない場合は、一覧を表示したうえで「この問い合わせは見つかりませんでした」と示す。`/operator` でも `/owner` でも始まらないパスは本人向けの画面。

### `POST /api/operator/inquiries/{id}/read`

既読にする。`200` 上と同じ全内容（`read: true`）。

### `GET /api/operator/stream`

`text/event-stream`。流す event は「オペレータのストリーム」の節のとおり（`filed` と `updated`）。接続を保つため、一定間隔でコメント行を送る。

## スキルの構成に伴う取り決め

AI処理の内部の構成は `docs/skill-architecture.md` が正。画面との契約に関わる点だけをここに書く。

- **`answer.memo.unconfirmed`**: 調査が挙げたが、根拠の実在を確かめられなかった事実の文の一覧。該当がなければ空配列。**担当者にだけ返す。** 中身が入るのはオペレータの詳細（`GET /api/operator/inquiries/{id}`）の `answer.memo.unconfirmed` だけで、本人向けの InquiryView（2〜9 と進捗ストリームの `done`）では常に空配列にする。裏づけのない内容は、本人には真偽を判断できず、読めばそのまま試してしまうおそれがあるため。画面で隠すのではなく、API が本人向けの応答に含めない。オペレータ画面は「根拠を確かめられなかったこと」として、分かったこと（`findings`）と区別して示す。本人向けの説明（`askerExplanation`）は `findings` の内容だけから書かれ、`unconfirmed` の内容を含まない。
- **0次回答の進捗**: 調査の進捗に続けて「根拠を確かめています」が流れ、根拠ありの場合はさらに「答えをまとめています」が流れる。
- **説明の失敗**: 調査と照合が成功し、説明だけが失敗した場合、進捗ストリームは `failed`（`AI_UNAVAILABLE` または `AI_REFUSED`）を流し、`awaiting` は `INVESTIGATION` のまま。調査の結果（技術メモ、出典と照合結果、利用量）は保存する。ただし本人向けの InquiryView の `answer` は `ANSWERING` の間は `null` のままにする（説明がない状態を「答えが見つからなかった」と誤って見せないため）。画面は従来どおり再試行と「今すぐ担当者に送る」を出す。送った場合、オペレータの詳細（`GET /api/operator/inquiries/{id}`）の `answer` に保存済みの調査の内容が入り、`grounded` は `false`、`askerExplanation` は `null` になる。再試行で調査が成功すれば、保存済みの内容は新しい結果で置き換える。
- **利用量の記録**: `usages[].step` はスキルの識別子（`reception`、`interviewer`、`restater`、`investigator`、`explainer`、`advisor`、`drafter`、`translator`）。押して答えただけの入口では、受付は呼ばれない（記録にも現れない）。
- **問いに答えられたか（`answer.answered`）**: 確かめられた事実があること（`grounded`）と、その事実が本人の問いに答えていること（`answered`）を区別する。根拠はあるが的外れな事実しか集まらなかった場合に、それを答えとして見せないため。
  - `answered` は真偽値。`grounded` が `false` のときは常に `false`。`grounded` が `true` のときは、説明のスキルの判定（問いに答えているか）をそのまま入れる。
  - `grounded: true`、`answered: false` の場合も、`askerExplanation`（確認できなかったことと、関連して分かったこと）、`citations`、`memo` は従来どおり返す。
  - 画面の扱い（本人向け）:

    | `grounded` | `answered` | 表示 |
    | --- | --- | --- |
    | `true` | `true` | 従来どおり。説明を主に示し、「解決しました」を主のボタンにする |
    | `true` | `false` | 「ご質問への答えは、手元の資料では確認できませんでした。」と示し、その下に「関連して分かったこと」として説明を出す。ボタンは出さない（答えの完了と同時に、担当者に渡っている） |
    | `false` | `false` | 「手元の資料からは答えを見つけられませんでした。」ボタンは出さない（同上） |

  - 答えられなかった件は、種類にかかわらず担当者に渡る（「種類ごとの経路」の節）。解決したかを選ぶボタンが出るのは、上の表の1行目だけ。
  - オペレータの詳細の `answer` にも `answered` を含める。
  - モックの説明は `answered: true` を返す。タグ `[的外れ]` を付けた場合だけ `false` を返す（調査は通常の事実を返し、説明のスキルが「問いに答えていない」と判定した場合を再現する）。
- **スキルごとの実装**: `GET /api/config` に `skills` を加える。スキルの識別子から、動いている実装の名前（`mock` または `ollama`）への対応。例: `{ "reception": "ollama", "interviewer": "ollama", "restater": "ollama", "investigator": "mock", "explainer": "ollama" }`。`mock` は、スキルか一次情報の検索に1つでもモックがあれば `true`。画面はモックの案内枠に、どのスキルがモックかを示す。モックのシナリオのタグは、モックで動いているスキルにだけ効く（たとえば受付が実際のモデルなら、`[判定エラー]` を書いても失敗しない）。

## 担当者に渡した後

担当者に渡した問い合わせは、担当者と本人の間で番を受け渡しながら進む。番を渡すには、相手へのメッセージが要る。

### メッセージ（messages）

担当者の発言と本人の追記を、1本の時系列として持つ。

```json
{ "id": "UUID", "from": "OPERATOR", "kind": "ASK", "authorName": "担当者（開発用）", "body": "本文", "createdAt": "2026-11-21T12:00:00Z" }
```

- `from` は `OPERATOR`（担当者）か `ASKER`（本人）。
- `kind` は、担当者の発言では `ASK`（聞き返し）、`RESULT`（結果）、`DECLINE`（見送りの連絡）のいずれか。本人の追記では `NOTE`。
- `authorName` は表示用の名前。認証を加えるまでは、設定の固定値（`inquiry.dev.operator-name`、`inquiry.dev.user-name`）。
- 古い順に並べる。編集と削除はできない。

### 未読

- **担当者側**（`read`）: 番が担当者に移るたび、また担当者の番の間に本人が追記するたびに、`false` に戻す。
- **本人側**（`unreadReply`）: 担当者が発言すると `true`、本人がその問い合わせを取得（9）すると `false` になる。返事がないことを知らせたとき、期間が過ぎて終えたときも `true` にする（「返事がないとき」の節）。

### 調査の状況と、裏での調査

AI の調査（調査、照合、説明）は、本人が待っている間（`awaiting: INVESTIGATION`）だけでなく、担当者に渡した後にも、裏で行う。担当者に、材料のない件を渡さないため。裏での調査は、番も区間も変えない。

問い合わせは、調査の状況を持つ。担当者向けの詳細が `investigation` として返す。

```json
"investigation": { "status": "RUNNING", "audience": null, "startedAt": "2026-11-22T03:00:00Z", "finishedAt": null, "unconfirmedQuestion": false }
```

| 項目 | 意味 |
| --- | --- |
| `status` | `NOT_RUN`（調べていない）、`RUNNING`（調べている）、`DONE`（終わった）、`FAILED`（失敗した） |
| `audience` | 結果を誰に見せるか。`ASKER`（本人が待っている間に出た答え。本人にも見せる）、`OPERATOR`（裏での調査の結果。担当者だけに見せる）。`DONE` のときだけ非 null |
| `startedAt`、`finishedAt` | 直近の調査を始めた時刻、終わった（または失敗した）時刻 |
| `unconfirmedQuestion` | 調査のもとにした問い（言い直し）を、本人が確かめていない場合に `true`（業務が止まっている件） |

**結果を誰に見せるか**は、調査が終わった時点の状態で決まる。

| 終わった時点 | 扱い |
| --- | --- |
| 本人が待っている（`awaiting: INVESTIGATION`） | 従来どおり。`audience: ASKER`。種類ごとの経路で、本人が確かめるか、担当者に渡す |
| それ以外（担当者に渡した後） | `audience: OPERATOR`。結果を保存し、状態は変えない。本人向けの InquiryView の `answer` は `null` のまま。担当者の側を未読に戻し、担当者のストリームに `updated` を流す（問い合わせが終わっている場合は、保存だけ） |

- 裏での調査の結果を本人に見せないのは、本人から見た窓口を担当者に一本化するため。担当者が確かめていない答えが、担当者の返事と食い違って届くことを防ぐ。伝えるかどうかは、担当者が決める。
- 担当者向けの詳細の `answer` は、`audience` がどちらでも、保存されている結果を返す。

**裏での調査を始めるとき**:

| きっかけ | 条件 |
| --- | --- |
| 本人が、答えを待たずに送った（8） | 送る前の `awaiting` が `INVESTIGATION` だった場合。調査が既に走っていればそのまま続け、走っていなければ始める。AI の答えが既にある場合（`RESULT_CONFIRMATION` から送った）、言い直しを作れなかった場合、本人が言い直しを合っていないとしたまま送った場合（`QUESTION_CONFIRMATION` から送った）は、始めない |
| 業務が止まっている件を、担当者に渡した | 言い直しがあり、種類が `CHANGE_REQUEST` でない場合。本人は言い直しを確かめていないので、`unconfirmedQuestion` を `true` にする |
| 調査が時間切れになった（6） | 走っている調査を、そのまま続ける |
| 担当者が、調べ直すように頼んだ（下の「担当者の操作」） | 終わっていない、言い直しがある、いま調べていない |

- 同じ問い合わせの調査は、同時に1つだけ走らせる。
- 調査は、誰が頼んだ場合も、本人の閲覧権限（`AccessScope`）で行う。結果が本人に伝わりうるため。
- 裏での調査が失敗したら `status: FAILED` にして、担当者のストリームに `updated` を流す（未読には戻さない）。本人には何も見せない。説明だけが失敗した場合は、従来どおり調査の結果を保存し、`status` は `DONE` にする（`answer.grounded` は `false`、`askerExplanation` は `null`）。
- 裏での調査は、API のプロセスの中で走る。API を止めると失われるので、起動時に、`RUNNING` のまま残っている件を `FAILED` にする。本人が待っている件（`INVESTIGATION`）は、従来どおりストリームの再購読でやり直せる。担当者に渡した件は、担当者が調べ直せる。
- 調査の開始と終了は、状態の履歴に入れない（番が変わらないため）。時刻は `investigation` が持つ。
- 本人や担当者の操作が、裏での調査の結果を保存する瞬間と重なると、操作が `INVALID_STATE`（他の操作と競合しました）になることがある。取得し直してやり直せば通る。
- 種類が `CHANGE_REQUEST` の件を担当者が調べ直すとき、調査のスキルには、種類を決めずに渡す（調査のスキルは、調べない種類を受け付けないため）。

設定:

| キー | 既定 | 意味 |
| --- | --- | --- |
| `inquiry.rules.answer-timeout` | `60s` | 本人が待つ調査を、担当者に引き継ぐまでの時間。プロファイル `local-ai` では `10m`（手元のモデルは、調査に60秒以上かかるのが普通のため） |

### AI の案（advice）

担当者に渡った件には、AI が、確かめられた事実から、判断の案と、作業の手順の案を添える（スキルの構成と、案を確かめる決まりは `skill-architecture.md` の「担当者を支える2つのスキル」が正）。担当者向けの詳細が `advice` として返す。本人向けの InquiryView には含めない。

```json
"advice": {
  "status": "DONE",
  "judgment": {
    "text": "資料の記述どおりの動きと考えられます。不具合ではなく、仕様の説明で足りそうです。",
    "needsConsultation": false,
    "consultationReason": null,
    "basedOn": ["求人は、掲載期限の時刻ちょうどから期限切れとして扱われる。"]
  },
  "procedure": {
    "steps": [{ "target": "求人管理画面の求人の編集", "action": "掲載期限を、翌日の日付に変える", "check": "応募者向けの一覧に、求人が表示されること" }],
    "rollback": "掲載期限を、元の日付に戻す",
    "basedOn": ["期限切れの求人は、応募者向けの一覧に表示されない。"]
  }
}
```

| 項目 | 意味 |
| --- | --- |
| `status` | `NOT_RUN`（作っていない。確かめられた事実がない場合を含む）、`RUNNING`、`DONE`、`FAILED` |
| `judgment` | 判断の案。なければ `null`。`needsConsultation` は、仕様の持ち主への相談が要りそうか。`consultationReason` は、その理由（要らなければ `null`） |
| `procedure` | 作業の手順の案。なければ `null`。`steps` は、行う順。`rollback` は、元に戻す方法（なければ `null`） |
| `basedOn` | その案が基づく事実の文（`answer.memo.findings` の文と同じもの）。1件以上。基づく事実のない案は、API が返さない |

- 案は、担当者の番の問い合わせに、確かめられた事実が1つ以上あるときに、裏で作る。調べ直して調査の結果が置き換わると、前の案は消え、作り直される。
- 案ができたとき、失敗したときは、担当者のストリームに `updated` を流す（未読には戻さない。未読に戻すのは、調査の結果が届いたとき）。
- 案の文章は、既定の言語で書かれる（書かれた内容なので、見る人の言語では切り替わらない）。
- 担当者の操作が、案を保存する瞬間と重なると、操作が `INVALID_STATE`（他の操作と競合しました）になることがある。やり直せば通る（裏での調査の結果の保存と同じ）。
- API を止めると、作っている途中の案は失われる。起動時に、`RUNNING` のまま残っている件を `FAILED` にする。作り直すには、調べ直す。

### 担当者の操作

すべて、担当者に渡したことのある問い合わせが対象。そうでない問い合わせは `NOT_FOUND`。`200` で、詳細（`GET /api/operator/inquiries/{id}`）と同じ全内容を返す。

| 操作 | エンドポイント | リクエスト | 実行できるとき | その後 |
| --- | --- | --- | --- | --- |
| 本人に聞き返す | `POST /api/operator/inquiries/{id}/replies` | `{ "kind": "ASK", "body": "1〜4000文字" }` | 担当者の番 | メッセージを足し、本人の番にする（`awaiting: ASKER_REPLY`）。区間は変えない |
| 結果を伝える | 同上 | `{ "kind": "RESULT", "body": "1〜4000文字" }` | 担当者の番 | メッセージを足し、本人の番にする（`awaiting: RESULT_CONFIRMATION`）。区間は変えない |
| 見送る | `POST /api/operator/inquiries/{id}/decline` | `{ "reasonKind": "KNOWN_LIMITATION", "reason": "1〜2000文字" }` | 担当者の番 | 理由をメッセージ（`kind: DECLINE`）として足し、終わる（`ending: DECLINED`）。`declined` に理由を持つ |
| 区間を変える | `PUT /api/operator/inquiries/{id}/phase` | `{ "phase": "ACT" }` | 担当者の番 | 区間を `KNOW`、`DECIDE`、`ACT` のいずれかにする。番は変えない |
| 置き直す | `PUT /api/operator/inquiries/{id}/placement` | `{ "type": "DO_FOR_ME", "systemId": "system-a", "functionId": "a-1" }`（どれも `null` にできる） | 終わっていない | 置き場所を変える。置いた人（`by`）は `OPERATOR` になる。種類が変わった場合は、区間を新しい種類の「担当者に渡す」の決まりに合わせる。番は変えない |
| 調べ直す | `POST /api/operator/inquiries/{id}/investigation` | 本文なし | 終わっていない。言い直しがある。いま調べていない（`investigation.status` が `RUNNING` でない） | 裏での調査を始める（`investigation.status: RUNNING`）。番は変えない。種類が `CHANGE_REQUEST` でも、担当者が頼めば調べる。結果は担当者だけに見せる（`audience: OPERATOR`）。前の結果は、新しい結果が出た時点で置き換える（失敗した場合は、前の結果を残す） |
| 返信の下書きを作る | `POST /api/operator/inquiries/{id}/draft` | `{ "kind": "ASK" または "RESULT", "note": "伝えたいこと（任意。1〜2000文字）" }` | 担当者の番 | AI が、本人に送る文章の下書きを作って返す。**状態は変えず、何も送らない。** 応答は、ほかの操作と違い `{ "body": "下書きの文章", "language": "ja" }`。下書きは、本人の言語（`language`）で書かれる。担当者が確かめ、直してから、「本人に聞き返す」か「結果を伝える」で送る。失敗したときは `AI_UNAVAILABLE`／`AI_REFUSED` |
| 訳を見る | `POST /api/operator/inquiries/{id}/translation` | 本文なし（訳す先は、見る人の言語） | 本人の言語（`language`）が、見る人の言語と違う | 本人の言語で書かれた文章の訳を返す（下の「訳」）。**状態は変えない。** 応答は、ほかの操作と違う形。本人の言語と見る人の言語が同じなら `INVALID_STATE`。失敗したときは `AI_UNAVAILABLE`／`AI_REFUSED` |
| 既読にする | `POST /api/operator/inquiries/{id}/read` | 本文なし | いつでも | `read: true` |

- `reasonKind` は `NOT_REPRODUCIBLE`（再現しない）、`KNOWN_LIMITATION`（既知の制限）、`OUT_OF_SCOPE`（対象外）、`DUPLICATE`（重複）、`OTHER`（その他）のいずれか。
- 実行できないときは `INVALID_STATE`。入力の誤り（空の本文、範囲外の値、システムにない機能、存在しない id）は `VALIDATION`。
- 担当者は、見送る場合を除いて、問い合わせを終わらせられない。結果を伝えた後、終わらせるのは本人（7）。
- 以前の `PUT …/status`（未対応、対応中、完了の切り替え）は廃止した。

### 仕様の持ち主への相談

担当者が自分で決めてよいのは、既にある仕様や先例を当てはめる判断まで。新しい決定が要る件は、仕様の持ち主に相談する（`framework.md` の「段」）。相談している間は、仕様の持ち主の番になる（`turn: OWNER`、`awaiting: CONSULTATION`）。区間は変えない。持ち主が決めると、決定が先例として1つ残り、担当者の番に戻る。本人に伝えるのは担当者（本人から見た窓口を、担当者に一本化する）。

**相談先**は、設定で決める。名前（文字列）の一覧で、認証を加えるまでは、名前だけを持つ。

```yaml
inquiry:
  consultants: [仕様の持ち主（開発用）]        # 全体の相談先
  systems:
    - id: system-a
      consultants: [システムAの仕様の持ち主]    # システムの相談先
  cells:
    - system: system-a
      function: a-1
      consultants: [機能A-1の仕様の持ち主]      # マスの相談先
```

- 問い合わせの相談先は、置き場所に当てはまるマスの設定（詳しい質問と同じ選び方で選んだ1つ）の `consultants`。なければ、システムの `consultants`。なければ、全体の `consultants`。担当者向けの詳細が `defaultConsultants`（名前の配列。なければ空）として返す。
- 担当者は、相談するときに相手を替えられる。相談先が複数の人の場合は、その中の誰かが決めれば足りる。
- 既定の設定は、全体の相談先を「仕様の持ち主（開発用）」の1人にする。

**相談**（`consultations`）は、問い合わせが持つ。担当者向けの詳細が、古い順の配列で返す。

```json
"consultations": [{
  "id": "5b0e…(UUID)", "askedAt": "2026-11-22T03:00:00Z", "askedBy": "担当者（開発用）",
  "to": ["仕様の持ち主（開発用）"], "question": "期限ちょうどの時刻の扱いは、意図した仕様でしょうか。",
  "status": "OPEN", "closedAt": null, "precedentId": null
}]
```

- `status` は `OPEN`（決定を待っている）、`DECIDED`（決まった。`precedentId` に、できた先例の id）、`CANCELED`（取り下げた）。`OPEN` の相談は、同時に1つだけ。
- 相談には、材料が付く。持ち主は、担当者と同じ内容（本人が書いたこと、言い直し、調べた結果、AI の案、やり取り）を読める（下の持ち主向けの API）。担当者が材料を書き写す必要はない。

**担当者の操作**:

| 操作 | エンドポイント | リクエスト | 実行できるとき | その後 |
| --- | --- | --- | --- | --- |
| 相談する | `POST /api/operator/inquiries/{id}/consultations` | `{ "to": ["名前"], "question": "1〜4000文字" }`。`to` は1〜5人（名前は1〜100文字）。省くか空にすると、既定の相談先 | 担当者の番 | 相談を足し、仕様の持ち主の番にする（`awaiting: CONSULTATION`）。区間は変えない。相談先が1人も決まらなければ `VALIDATION` |
| 相談を取り下げる | `POST /api/operator/inquiries/{id}/consultations/cancel` | 本文なし | `awaiting: CONSULTATION` | 相談を `CANCELED` にし、担当者の番に戻す（`HANDLING`）。`handoverReason` は変えない |

どちらも、応答は担当者向けの詳細。

**持ち主向けの API**（`/api/owner` 配下。担当者向けの API と同じく、認証は後で加える）:

| エンドポイント | 内容 |
| --- | --- |
| `GET /api/owner/consultations` | 決定を待っている相談の一覧。`200` `{ "items": [{ "inquiryId", "consultationId", "askedAt", "askedBy", "to", "question", "summary", "type", "system", "function", "urgency", "language" }] }`。古い順（長く待たせている順）。`summary`、`type`、`system`、`function` は、担当者向けの一覧と同じ決まり |
| `GET /api/owner/inquiries/{id}` | 相談したことのある問い合わせの全内容。担当者向けの詳細と同じ形。相談したことのない問い合わせは `NOT_FOUND`。担当者の側の既読は変えない |
| `POST /api/owner/inquiries/{id}/decision` | 決める。下のとおり |

決める（`POST /api/owner/inquiries/{id}/decision`）:

```json
{ "decisionKind": "AS_INTENDED", "decision": "期限ちょうどの時刻から期限切れとして扱うのは、意図した仕様です。",
  "reason": "締め切りの時刻を過ぎた応募を受け付けないため。", "situation": "掲載期限の時刻ちょうどに、求人が一覧から消える。",
  "decidedBy": "仕様の持ち主（開発用）" }
```

- `decisionKind` は、決定の種類。`AS_INTENDED`（意図どおり）、`DEFECT`（不具合）、`ACCEPT`（受ける）、`REJECT`（受けない）、`ADOPT`（採用する）、`DEFER`（見送る）、`OTHER`（その他）のいずれか。
- `decision`（決定の内容）と `reason`（理由）は1〜4000文字。`decidedBy`（決めた人の名前）は1〜100文字。
- `situation`（どんな状況についての決定か）は1〜4000文字。省くと、言い直しの文章。なければ、本人が書いた文章の先頭200文字。それもなければ、相談の文章。
- 実行できるのは `awaiting: CONSULTATION` のとき。それ以外は `INVALID_STATE`。
- その後: **先例を1つ作る**（下の「先例」）。相談を `DECIDED` にする。担当者の番に戻す（`HANDLING`。`handoverReason: DECIDED`）。担当者の側を未読に戻し、担当者のストリームに `updated` を流す。応答は、担当者向けの詳細と同じ形。

**先例**（precedent）は、仕様の持ち主が下した決定の記録。問い合わせとは別に持ち、問い合わせが終わっても残る。人が下した決定は、ここにしか残らないため。

```json
{ "id": "9c1d…(UUID)", "inquiryId": "0b9c…", "type": { "id": "LOOKS_WRONG", "label": "おかしいかもしれない" },
  "system": { "id": "system-a", "label": "システムA" }, "function": null,
  "situation": "…", "decisionKind": "AS_INTENDED", "decision": "…", "reason": "…",
  "decidedBy": "仕様の持ち主（開発用）", "recordedBy": "OWNER", "confirmed": true, "language": "ja", "decidedAt": "2026-11-22T04:00:00Z" }
```

- 先例のマス（`type`、`system`、`function`）は、決めた時点の問い合わせの置き場所。後から問い合わせを置き直しても、先例のマスは変えない。名前（`label`）は、見る人の言語で返す。
- `situation`、`decision`、`reason` は、書かれた内容。書かれたときのまま持ち、訳さない。`language` は、決めたときの見る人の言語。
- `recordedBy` は、記録した人（`OWNER`。担当者が代わりに記録する場合は、後の区切りで加える）。`confirmed` は、決めた人が内容を確かめたか（持ち主が自分で記録した先例は、最初から `true`）。
- 担当者向けの詳細は、その問い合わせから生まれた先例を `decisions`（古い順の配列）として返す。
- 先例と相談は、担当者と持ち主にだけ返す。本人向けの InquiryView には含めない。本人には、`turn: OWNER`、`awaiting: CONSULTATION` であることだけが見える。

**相談している間の、ほかの操作**:

| 操作 | 扱い |
| --- | --- |
| 本人の追記（11） | 番は変えない（持ち主の番のまま）。担当者の側を未読に戻す |
| 本人の取り下げ（12） | 終わる。`OPEN` の相談は `CANCELED` にする |
| 担当者が聞き返す、結果を伝える、見送る、区間を変える | 担当者の番ではないので `INVALID_STATE`。先に、相談を取り下げる |
| 担当者が置き直す、調べ直す、下書き以外の読み取り（訳） | これまでどおり（終わっていなければ、できる） |
| 返事がないときの決まり | 対象外（本人の返事を待っているのではないため） |

### 訳

本人の言語と、担当者が見ている言語が違う場合、担当者は訳を頼める（LG-4。スキルの構成は `skill-architecture.md` の「訳のスキル」）。

`POST /api/operator/inquiries/{id}/translation` の応答:

```json
{
  "from": "en",
  "to": "ja",
  "items": [
    { "key": "utterance", "original": "The job posting disappeared.", "translated": "求人の掲載が消えました。" },
    { "key": "structured.summary", "original": "…", "translated": "…" },
    { "key": "answer.askerExplanation", "original": "…", "translated": "…" },
    { "key": "message:3f2a…", "original": "…", "translated": "…" }
  ]
}
```

- 訳すのは、本人の言語で書かれた文章のうち、次のもの（あるものだけ）: 本人が書いた文章（`utterance`）、言い直し（`structured.summary`）、本人向けの説明（`answer.askerExplanation`）、本人の発言（`messages` のうち `from` が `ASKER` のもの。`key` は `message:` に続けてメッセージの `id`）。
- 訳さないもの: 根拠の引用、設定の名前、担当者の発言、AI の案、入口の質問への答え（選択肢の名前は、見る人の言語で返している）。
- 訳は問い合わせに保存し、同じ文章を2度訳さない（訳す先の言語と、原文が同じなら、保存した訳を返す）。新しい発言が増えた後に呼ぶと、増えた分だけを訳す。
- 訳は、担当者向けの応答にだけ含める。本人向けの InquiryView には含めない。
- 訳は原文に添えるもので、原文を置き換えない。担当者向けの詳細（`GET /api/operator/inquiries/{id}`）は、これまでどおり原文だけを返す。

### 本人の操作（担当者に渡した後）

| 操作 | エンドポイント | 実行できるとき | その後 |
| --- | --- | --- | --- |
| 結果を確かめる | 7 | `awaiting: RESULT_CONFIRMATION` | 「本人が結果を確かめる」の決まりのとおり |
| 追記する | 11 | 担当者に渡したことがあり、本人が取り下げていない。または、担当者に渡したことがなく、`ANSWERED` で終わっている | 下の表 |
| 取り下げる | 12 | 終わっていない | 終わる（`ending: WITHDRAWN`） |

追記（11）の後は、追記したときの状態で決まる。

| 追記したときの状態 | その後 |
| --- | --- |
| 担当者の番（`HANDLING`） | 番は変えない。担当者の側を未読に戻す |
| 聞き返しへの返事を待っている（`ASKER_REPLY`） | 担当者の番にする（`HANDLING`） |
| 担当者の結果を確かめている（`RESULT_CONFIRMATION`） | 担当者の番にする（「解決していない」と答えたのと同じ） |
| 終わっている（`RESOLVED`、`ANSWERED`、`DECLINED`、時間で終わった `WITHDRAWN`） | 開き直す。終わり方を消し、区間を終わる前に戻して、担当者の番にする |
| 担当者に渡したことがなく、`ANSWERED` で終わっている（AI の答えに返事がなかった） | 開き直して、担当者に渡す（「担当者に渡す」の決まりのとおり。区間は種類で決まり、初めて渡すので担当者に通知する）。履歴の出来事は `REOPENED` |

担当者に渡したことのない問い合わせ（上の `ANSWERED` の場合を除く）、本人が取り下げた問い合わせ（`ending: WITHDRAWN` で `closedBy: ASKER`）への追記は `INVALID_STATE`。

### 10. `GET /api/inquiries`

自分（現在の利用者）が作った問い合わせの一覧（新しい順、最大50件）。

`200` `{ "unreadReplyCount": 1, "items": [{ "id", "createdAt", "language", "summary", "urgency", "phase", "turn", "awaiting", "ending", "handedOverAt", "unreadReply", "messageCount" }] }`。`summary` は、担当者向けの一覧と同じ決まり。`handedOverAt` は、結果を確かめている件の結果が、AI の答えか担当者の結果かを見分けるために使う（担当者に渡したことがあれば、担当者の結果）。

### 11. `POST /api/inquiries/{id}/messages`

リクエスト `{ "body": "1〜4000文字" }`。本人が追記する。上の「本人の操作」の決まりのとおり。

`200` InquiryView。

### 12. `POST /api/inquiries/{id}/withdraw`

本文なし。本人が取り下げる。終わっていない問い合わせで有効（終わっていれば `INVALID_STATE`）。

`200` InquiryView（`phase: CLOSED`、`ending: WITHDRAWN`）。担当者に渡したことのある問い合わせなら、担当者のストリームに `updated` を流す。

### 9（補足）. `GET /api/inquiries/{id}`

他人の問い合わせは `NOT_FOUND`。取得すると、その問い合わせの `unreadReply` を `false` にする。

### 状態の履歴（history）

状態が変わるたびに、1件ずつ記録する。誰の番にどれだけ時間がかかったかを後から測るため（測るのは後の段階。記録は、後から作り直せないので最初から残す）。

```json
{ "at": "2026-11-22T03:00:00Z", "actor": "OPERATOR", "event": "ASKED_BACK", "phase": "DECIDE", "turn": "ASKER", "awaiting": "ASKER_REPLY", "ending": null }
```

- `actor` は、その変化を起こした人。`ASKER`、`AI`、`OPERATOR`、`OWNER`（仕様の持ち主）、`TIME`（期間が過ぎたことで起きた）のいずれか。
- `detail` は、`PLACEMENT_CHANGED` 以外では `null`。
- `phase`、`turn`、`awaiting`、`ending` は、変化の後の状態。
- `event` は次のいずれか。

| `event` | 起こす人 | 意味 |
| --- | --- | --- |
| `CREATED` | 本人 | 問い合わせができた |
| `QUESTION_COMPOSED` | AI | 問いを組み立てた（言い直しの確認へ） |
| `REOPENED_INTAKE` | 本人 | 入口の質問へ戻った |
| `QUESTION_CONFIRMED` | 本人 | 問いを確かめた |
| `ANSWERED_BY_AI` | AI | AI が答えた（本人が確かめる） |
| `HANDED_OVER` | 本人、AI、時間 | 担当者に渡した（答えを待たずに送った、解決していないと答えた、種類の経路で渡った、AI が答えられなかった、業務が止まっている）。調査の時間切れで渡した場合は、起こしたのは時間（`TIME`） |
| `ASKED_BACK` | 担当者 | 本人に聞き返した |
| `REPLIED` | 本人 | 聞き返しに答えた |
| `RESULT_DELIVERED` | 担当者 | 結果を伝えた |
| `RESOLVED` | 本人 | 解決を伝えた |
| `DECLINED` | 担当者 | 見送った |
| `WITHDRAWN` | 本人、時間 | 本人が取り下げた。または、聞き返しに返事がないまま期間が過ぎて、取り下げになった |
| `REOPENED` | 本人 | 終わった件を、追記で開き直した |
| `REMINDED` | 時間 | 返事がないことを、本人に知らせた（状態は変わらない） |
| `ANSWERED` | 時間 | 返事がないまま期間が過ぎて、終わった |
| `CONSULTED` | 担当者 | 仕様の持ち主に相談した |
| `DECIDED` | 仕様の持ち主 | 相談に答えて決めた（先例が1つできた） |
| `CONSULTATION_CANCELED` | 担当者 | 相談を取り下げた |
| `PHASE_CHANGED` | 担当者 | 区間を変えた |
| `PLACEMENT_CHANGED` | 担当者 | 置き直した。この出来事だけ `detail` を持つ: `{ "before": { "type": "おかしいかもしれない", "system": "システムA", "function": null }, "after": { … } }`。値は、既定の言語の名前。決まっていなければ `null` |

履歴は、担当者向けの詳細（`history`）にだけ返す。本人向けの InquiryView には含めない。番が変わらない操作（担当者の番の間の本人の追記、既読）は、履歴に入れない。時間が起こした出来事（`REMINDED`）は、番が変わらなくても入れる。

### オペレータのストリーム

`GET /api/operator/stream` は、担当者に初めて渡したときに event `filed`、その後に状態やメッセージが変わるたびに event `updated` を流す。data は、一覧の1件と同じ形。

### 本人向けの画面の URL

| パス | 画面 |
| --- | --- |
| `/` | 新しい問い合わせの入力 |
| `/inquiries` | 自分が送った問い合わせのボード |
| `/inquiries/{id}` | その問い合わせ。途中ならその段階から再開し、終わっていれば経過とやり取りを示す |

問い合わせを作成したら、URL を `/inquiries/{id}` に変える（ページを開き直しても続きから再開できる）。`{id}` が存在しない、形式が崩れている、他人の問い合わせの場合は、「この問い合わせは見つかりませんでした」と示し、新しい問い合わせを書けるようにする。「新しい問い合わせを書く」は `/` に移る。

開き直したときの再開:

| `awaiting` | 画面 |
| --- | --- |
| `INTAKE_ANSWER` | 答え終えた質問（`intake.entries`）の要約と、次の質問（`intake.next`）。入口の答えは1問ごとに保存されるので、続きから再開できる |
| `QUESTION_CONFIRMATION` | 答え終えた質問の要約と、言い直しの確認 |
| `INVESTIGATION` | 進捗ストリームを購読し直す（調査をやり直す） |
| `RESULT_CONFIRMATION` | 調べた結果、担当者とのやり取り（あれば）、結果の確かめ |
| `HANDLING`、`ASKER_REPLY` | 経過の要約、いまの状況、やり取り、追記の入力欄 |
| 終わっている（`null`） | 経過の要約、終わりの文言、担当者に渡したことがあればやり取り |

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
- 助言の案を確かめる処理（基づく事実のない案を捨てる）も通常のコードで、記録には現れない。画面は、助言の後に「案の確認（コード）」を固定で描く。
- 助言、下書き、訳の呼び出しは、担当者向けの詳細の `trace` にだけ含める。本人向けの InquiryView の `trace` には含めない（担当者だけに見せる案があることを、本人のページに出さない）。

## 利用者の表示と仮の文言

- `GET /api/config` に `user`（`{ "id": "local-user", "name": "開発用ユーザー" }`）と `operatorName` を加える。認証を加えるまでは設定の固定値。
- `filedGuidance` の既定を「担当者が内容を確認し、このページに返信します。返信が届くと「送った問い合わせ」に印がつきます。」にする（往復ができたため、連絡方法が決まった）。
