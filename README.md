# 問い合わせアシスト（骨組み）

社内の問い合わせを、誰の番かを見失わずに終わりまで運ぶ、AI 補助つきの問い合わせ対応ツールの骨組み。

- 本人の状況を、答えられる問いに直す
- 資料を根拠に答え、答えきれない件は、材料と案を添えて担当者に渡す
- 新しい決定は仕様の持ち主が下し、先例として残る

**これは骨組みです。** 流れ、状態、画面、スキルの置き場所と受け渡す型は動きますが、資料、利用者、先例は見本で、AI のスキルは8つとも、決まった結果を返す見本の実装（モック）です。実際のモデル、資料の検索、認証は、使う側で差し替える前提で作っています（下の「カスタマイズの手引き」）。

図解: https://otake-shol.github.io/inquiry-assist-form/

## 動かす

前提は、Docker、Node.js 26 以上、Java 21。

```bash
npm install --prefix frontend
npm run dev:backend    # API（8080）。PostgreSQL のコンテナも自動で起動する
npm run dev:frontend   # 画面（5173）
```

| URL | 画面 |
| --- | --- |
| http://localhost:5173 | 本人（問い合わせる人） |
| http://localhost:5173/operator | 担当者のボード |
| http://localhost:5173/owner | 仕様の持ち主（相談への決定） |
| http://localhost:5173/precedents | 先例の一覧 |

確かめるには `npm run verify`（型、Lint、テスト）と `npm run smoke`（実ブラウザでの通し）。

## カスタマイズの手引き

**自分の変更を、自分のファイルに収める。** 骨組みのファイルを書き換えると、骨組みを更新したときに、取り込みで衝突します。変えたいものは、次の3つのどれかに当たります。

| 段 | 変えたいもの | どこで変えるか |
| --- | --- | --- |
| 1. 設定で変える | システムと機能、マスごとの質問、種類と緊急度の名前、相談先、期間、通知先、案内文 | `backend/config/application.yaml` を置く |
| 2. クラスを足して差し替える | AI のスキル、資料の検索、利用者と閲覧権限、通知 | `backend/src/main/java/dev/inquiryassist/custom/` にクラスを足す |
| 3. 画面を上書きする | 文言、製品の名前、色 | `frontend/src/i18n/overrides.ts`、`frontend/src/overrides.css` |

この3つに収まらないものは、骨組みの書き換えが要ります（末尾の「書き換えが要るもの」）。

### 1. 設定で変える

`backend/config/application.yaml` を置くと、骨組みの設定（`backend/src/main/resources/application.yaml`）の上に重なります。書いた項目だけが変わります。

```yaml
inquiry:
  systems:
    - id: portal
      label: { ja: 社内ポータル, en: Internal Portal }
      functions:
        - id: search
          label: { ja: 検索, en: Search }
  cells:
    - system: portal
      type: LOOKS_WRONG
      questions:
        - id: since
          text: { ja: いつから起きていますか, en: "Since when has this been happening?" }
          options:
            - { id: today, label: { ja: 今日から, en: Since today } }
            - { id: earlier, label: { ja: もっと前から, en: Earlier } }
  consultants: [ポータルの仕様の持ち主]
  rules:
    no-reply:
      remind-after-business-days: 3
```

- **一覧（`systems`、`cells`、`types`、`consultants` など）は、足し込みではなく、丸ごと置き換わります。** 一部だけを変えるときも、一覧の全体を書きます。
- 設定の強さは、コマンドライン引数、環境変数、`backend/config/`、骨組みの設定、の順です。
- **秘密の値（Slack の Webhook の URL など）は、ファイルに書かず、環境変数で渡します**（`INQUIRY_SLACK_DEFAULT_WEBHOOK_URL` など）。
- 設定できる項目の一覧は、`docs/api-contract.md` の「設定」にあります。

### 2. クラスを足して差し替える

自分のクラスは、`dev.inquiryassist` 以下のパッケージ（たとえば `dev.inquiryassist.custom`）に置きます。骨組みは、このパッケージの下を探して、見つけたクラスを使います。

#### AI のスキル

スキルは8つあります（受付、聞き手、言い直し、調査、説明、助言、下書き、訳）。それぞれ、インターフェース（`dev.inquiryassist.core.skill`）を実装したクラスを足し、設定でそのスキルの実装の名前を変えると、見本の実装（モック）と入れ替わります。

受付を差し替える例:

```java
package dev.inquiryassist.custom;

@Component
public class KeywordReceptionSkill implements ReceptionSkill {

    @Override
    public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, Placement decided) {
        InquiryType type = utterance.contains("エラー") ? InquiryType.LOOKS_WRONG : null;
        // 読み取れないものは null のままにする（質問として本人に聞かれる）
        Reception reception = new Reception(type, null, null);
        // 利用量: モデルの名前、指示文の版、入力・出力・キャッシュのトークン数、モックかどうか
        return new AiResult<>(reception, new Usage("keyword", "v1", 0, 0, 0, false));
    }
}
```

```yaml
# backend/config/application.yaml
inquiry:
  skills:
    reception: { provider: keyword }   # mock 以外の、好きな名前
```

- 名前（`provider`）は、画面の「裏側を見る」と、実行記録に、そのまま出ます。
- 起動のときに、スキルごとに、実装がちょうど1つあることを確かめます。設定だけ変えて実装がない場合と、実装を足したのに設定が `mock` のままの場合は、何をすればよいかを書いたメッセージで、起動が止まります。
- 失敗は、`AiUnavailableException`（呼び出せない）か `AiRefusalException`（拒否）を投げます。スキルごとの、失敗したときの扱いは、骨組みが決めています。
- 受け渡す型、いつ呼ばれるか、骨組みが確かめること（根拠の照合など）は、`docs/skill-architecture.md` にあります。実際のモデルをつなぐときに効く知見も、同じ文書の末尾にあります。
- 本番（プロファイル `production`）では、モックが1つでも残っていると、起動できません。

#### 資料の検索

`dev.inquiryassist.core.KnowledgeSearch` を実装したクラスを足し、設定で `inquiry.knowledge.provider` を、`mock` 以外の名前にします。閲覧権限（`AccessScope`）は、ここで適用します（見せてはいけない資料を、検索の結果に含めない）。骨組みは、この読み取りを使って、AI が挙げた引用が、資料に書かれているとおりかを確かめます。

#### 利用者と閲覧権限

`dev.inquiryassist.inquiry.CurrentUserProvider` を実装したクラスを足します。設定は要りません。足すと、骨組みの既定（固定の1人）は使われなくなります。

#### 通知

- **Slack のほかにも送る**: 「担当者に初めて渡した」（`InquiryFiledEvent`）、「更新された」（`InquiryUpdatedEvent`）を受け取るクラスを足します（`@EventListener`）。送るときは、別のスレッドで行い、例外を外に出さないでください。
- **Slack を止める**: Webhook の URL を設定しなければ、投稿しません。
- **担当者に渡す保存と一緒に、確実に送る**（送れなければ、渡したことも取り消す）: `dev.inquiryassist.inquiry.FilingNotifier` を実装したクラスを足します。

### 3. 画面を上書きする

**文言と、製品の名前**は、`frontend/src/i18n/overrides.ts` に、変えたい鍵だけを書きます。鍵は、辞書（`frontend/src/i18n/ja.ts`）と同じ入れ子の形です。辞書にない鍵は、型の検査で止まります。

```ts
export const overrides: MessagesOverrides = {
  ja: {
    text: { appTitle: "社内ヘルプ" },
    op: { title: "社内ヘルプ受付", tabTitle: "社内ヘルプ受付" },
  },
  en: {
    text: { appTitle: "Internal Help" },
    op: { title: "Internal Help inbox", tabTitle: "Internal Help inbox" },
  },
};
```

**色**は、`frontend/src/overrides.css` で、変数を上書きします。

```css
:root {
  --accent: #1f6f50; /* 基調の色（ボタン、強調） */
  --accent-strong: #17553d; /* 基調の色の、濃いほう */
  --accent-soft: #e3f2ea; /* 基調の色の、淡いほう */
}
```

変えられる変数は、`--bg`、`--ink`、`--muted`、`--line`、`--card`、`--accent`、`--accent-strong`、`--accent-ink`、`--accent-soft`、`--user-bg`、`--warn-bg`、`--warn-line`、`--danger-bg`、`--danger-line`。変数になっていない色（影、列の背景など）は、同じファイルに、セレクタを書いて上書きします。

種類、システム、機能、質問の名前は、画面ではなく、API の設定（上の 1）から来ます。

### 書き換えが要るもの

次のものは、上の3つでは変えられません。骨組みのファイルを書き換える必要があります。

| 変えたいもの | 書き換える場所 |
| --- | --- |
| 担当者と仕様の持ち主が、誰か（認証） | 担当者と持ち主の口（`/api/operator`、`/api/owner`）には、認証も、利用者の区別もありません。名前は、設定の固定値です。コントローラとサービスを書き換えます |
| 種類、緊急度を増やす。種類ごとの経路（どの種類を調べるか、どこで担当者に渡すか）を変える | `core/InquiryType`、`core/Urgency`、`core/lifecycle/InquiryStateMachine` と、画面 |
| 調査の流れ（調査、照合、説明）の順序や、段を変える | `core/skill/AnswerPipeline` |
| 言語を足す（日本語と英語のほかに） | 画面の辞書のファイルと、言語の一覧（`frontend/src/i18n/`）、API の設定の `languages` と、設定の名前のすべて |
| ブラウザのタブの題名（画面が動く前の分） | `frontend/index.html` |
| 見本の実装が返す内容（調査の事実など） | `backend/src/main/java/dev/inquiryassist/mock/` と、見本の資料（`backend/src/main/resources/mock-knowledge/`） |
| 保存の形（DB） | 新しい番号の移行のファイルを足します（`backend/src/main/resources/db/migration/`。適用済みの版は、書き換えません） |

文言を上書きした場合は、スモーク（`frontend/e2e/smoke.spec.ts`）が探している文言も、合わせて直す必要があります。

## 文書

| 文書 | 内容 |
| --- | --- |
| `docs/framework.md` | 問い合わせとは何か、解く課題、登場人物、状態と終わり方、規則 |
| `docs/requirements.md` | 要件と、要件ごとの実装状況 |
| `docs/api-contract.md` | 画面と API の間の契約（状態、口、エラー、設定、見本の実装のシナリオ） |
| `docs/skill-architecture.md` | 8つのスキルの境界、受け渡す型、呼ぶ時点、失敗したときの扱い |
| `docs/ux-principles.md` | 画面の考え方、形、言葉づかい |
| `docs/status.md` | いまの動き、分かっている制約、一度足して削った機能 |
