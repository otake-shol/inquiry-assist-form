---
type: spec
title: スキルの構成
description: AIに任せる5つのスキルの境界、スキル間で受け渡す型、呼ぶ順序、失敗したときの扱い。AI処理を変えるときの判断基準。
---

# スキルの構成

AIに任せる仕事を5つのスキルに分け、呼ぶ順序と根拠の照合は通常のコードが担う。図と考え方は公開ページ（`site/index.html`）にある。この文書は実装の仕様を定める。

## 分ける基準

スキルは、次のどれかが違う単位で分ける。どれも変わらない分割はしない。

- **品質の基準**: 何をもって良い出力とするか
- **使える道具**: 一次情報の検索を使うか、どこまで使うか
- **失敗のしかた**: 失敗したときに本人と担当者に何が起きるか

## スキルの一覧

インターフェースは `dev.inquiryassist.core.skill` に置き、Spring にも HTTP にも依存させない。実装（モック、のちに実際のモデル）はスキルごとに別のクラスにする。

| スキル | 識別子 | 仕事 | 道具 |
| --- | --- | --- | --- |
| 受付 | `reception` | 種別を判定し、対象システムを推測する | なし |
| 聞き手 | `interviewer` | 足りない情報を、非エンジニアが答えられる質問にする | 用語対応表とFAQの軽い検索 |
| 言い直し | `restater` | 言い直しの文章と構造化データを作る。作り直しも担う | 用語対応表 |
| 調査 | `investigator` | 一次情報を調べ、根拠つきの事実を集める | 読み取り専用の検索とファイル読み取り（繰り返し使う） |
| 説明 | `explainer` | 確かめられた事実だけから、本人向けの説明を書く | 用語対応表 |

識別子は列挙型 `Skill` で持ち、利用量の記録（`usages[].step`）にこの値を使う。言い直しの作り直しは `restater` として記録する。

### インターフェース

```java
interface ReceptionSkill {
    AiResult<Reception> receive(String utterance, List<SystemOption> candidates, SystemOption selected);
}

interface InterviewerSkill {
    AiResult<Interview> ask(String utterance, InquiryCategory category, SystemOption system, AccessScope scope);
}

interface RestaterSkill {
    AiResult<StructuredInquiry> restate(
            String utterance, InquiryCategory category, List<AnsweredQuestion> answers, SystemOption system);

    AiResult<StructuredInquiry> revise(String utterance, StructuredInquiry current, String note);
}

interface InvestigatorSkill {
    AiResult<Investigation> investigate(StructuredInquiry inquiry, AccessScope scope, ProgressListener progress);
}

interface ExplainerSkill {
    AiResult<String> explain(StructuredInquiry inquiry, List<Finding> confirmedFindings);
}
```

## 受け渡す型

| 型 | 中身 | 不変条件 |
| --- | --- | --- |
| `Reception` | `category`、`guessedSystemId`（任意） | `category` は必須 |
| `Interview` | `questions` | 最大3個 |
| `Finding` | `statement`（事実の一文）、`evidence`（その事実の根拠となる出典） | `evidence` は1件以上。根拠を挙げられない内容は事実にせず、「見つからなかったこと」か「次に確認すること」に入れる |
| `Investigation` | `findings`、`notFound`、`nextChecks` | — |
| `VerifiedInvestigation` | `confirmed`、`unconfirmed`、`notFound`、`nextChecks`、出典ごとの照合結果 | `confirmed` の各事実の `evidence` は照合を通った出典だけ |

`Finding` が事実と根拠を対にして持つことが、この構成の要になる。事実と出典を別々の一覧で持つと、ある出典が照合で落ちたときに、どの事実が根拠を失ったのかが分からない。対にしておけば、根拠を失った事実だけを機械的に外せる。

## 調査から説明までの順序

調査、照合、説明の順に進める。この部分は `core` の `AnswerPipeline` にまとめ、Spring なしでテストできるようにする。

1. **調査**（AI）: `Investigation` を返す。進捗は調査のスキルが通知する。
2. **照合**（コード）: 出典ごとに実在を確かめ、事実を2つに分ける。
   - 根拠が1件でも照合を通った事実は `confirmed`。`evidence` は通った出典だけに絞る。
   - 根拠がすべて落ちた事実は `unconfirmed`。
3. **説明**（AI）: `confirmed` が1件以上あるときだけ呼ぶ。渡すのは `confirmed` だけで、`unconfirmed` は渡さない。説明のスキルは、確かめられなかった内容を知る手段を持たない。

進捗には、調査の通知に続けて「根拠を確かめています」と、説明を呼ぶ場合は「答えをまとめています」を加える。

結果は次のように組み立てる。

| 項目 | 中身 |
| --- | --- |
| `grounded` | `confirmed` が1件以上あるか |
| `askerExplanation` | 説明のスキルの出力。`grounded` でなければ `null` |
| `citations`（本人向け） | `confirmed` の事実の根拠（照合を通ったもの） |
| `memo.findings` | `confirmed` の事実の文 |
| `memo.unconfirmed` | `unconfirmed` の事実の文。担当者にだけ「根拠を確かめられなかったこと」として示す |
| `memo.notFound`、`memo.nextChecks` | 調査の出力のまま |

確かめられなかった事実を捨てずに `memo.unconfirmed` に残すのは、担当者にとっては調べる手がかりになるため。ただし事実としては示さず、必ず区別して表示する。

問い合わせた本人には見せない。本人は裏づけのない内容の真偽を判断できず、もっともらしい文面であればそのまま試してしまう。本人向けの応答には API の時点で含めず、画面での非表示には頼らない。

## 呼ぶ順序と種別

| 種別 | 受付 | 聞き手 | 言い直し | 調査・照合・説明 |
| --- | --- | --- | --- | --- |
| 仕様確認・相談 | 呼ぶ | 呼ぶ | 呼ぶ | 呼ぶ |
| 不具合の疑い | 呼ぶ | 呼ぶ | 呼ぶ | 呼ぶ |
| 作業依頼 | 呼ぶ | 呼ばない（固定の3問） | 呼ぶ | 呼ばない |
| 障害 | 呼ぶ | 呼ばない（固定の1問） | 「いいえ」で続行する場合だけ呼ぶ | 続行する場合だけ呼ぶ |

本人が種別を仕様確認・相談か不具合の疑いに変更し、聞き手の質問がまだない場合は、その時点で聞き手を呼ぶ。対象システムを変更しただけでは呼び直さない。

## 失敗したときの扱い

スキルごとに、失敗が本人に与える影響を変える。

| 失敗したスキル | 扱い | 理由 |
| --- | --- | --- |
| 受付 | 問い合わせを保存し、エラーと `inquiryId` を返す。本人は「今すぐ担当者に送る」を使える | 種別が分からないと先へ進めない |
| 聞き手 | 質問なしで先へ進む。失敗はログと利用量の記録に残す | 質問は答えを良くするための補助で、なくても進められる。本人を止めない |
| 言い直し | エラーを返す。本人は再試行か「今すぐ担当者に送る」を選ぶ | 言い直しがないと確認ができない |
| 調査 | 進捗ストリームで失敗を伝える。段階は調査待ちのままで、再試行できる | 従来どおり |
| 説明 | 調査と照合の結果（技術メモ、出典、利用量）を保存したうえで、失敗を伝える。段階は調査待ちのまま | 費用をかけた調査の結果を失わない。この後「今すぐ担当者に送る」を使えば、担当者に調査の内容が届く |

説明が失敗した後に再試行した場合は、調査からやり直す。調査の結果を使い回す仕組みは、実際のモデルをつなぐ段階で費用を見て判断する。

## モックの構成

モックもスキルごとに別のクラスにする。発話のキーワードとタグの解釈は共通の補助クラスにまとめる。タグの一覧は `docs/api-contract.md` の「モックのシナリオ」が正。

## 実際のモデルをつなぐときの決まり

この文書の範囲外だが、置き場所だけ先に決めておく。

- プロンプトはスキルごとに `backend/src/main/resources/skills/<識別子>/` へ版つきのファイルで置き、コードに埋め込まない。
- モデルと effort はスキルごとに設定で持つ。
- 利用量の記録には、スキルの識別子、モデル、プロンプトの版、トークン数を残す。
- 評価セットはスキルごとに持ち、品質の基準に対応する指標で採点する。
