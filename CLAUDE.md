# inquiry-assist-form

社内メンバーの悩みを答えられる問いに整形し、ソースコードとFAQを根拠にした0次回答を返す問い合わせフォーム。

要件定義と実装計画: https://claude.ai/artifact/6ZYgbeXb2hwfhN9jN7F8pi

## 構成

| 場所 | 内容 |
| --- | --- |
| `backend/` | Spring Boot（Java 21、Gradle Kotlin DSL） |
| `backend/src/main/java/dev/inquiryassist/core/` | AI処理の型。画面・HTTP・Spring に依存させない |
| `backend/src/main/java/dev/inquiryassist/core/skill/` | 5つのスキルの境界、受け渡す型、調査から説明までの流れ（`AnswerPipeline`） |
| `backend/src/main/java/dev/inquiryassist/mock/` | スキルと一次情報の検索のモック実装 |
| `backend/src/main/resources/db/migration/` | 保存スキーマ（Flyway）。適用済みの版は書き換えず、新しい版を足す |
| `frontend/` | React + TypeScript + Vite（SPA）。`/`、`/inquiries`、`/inquiries/{id}` が本人向け、`/operator` が担当者向け |
| `site/` | 業務フローとスキル設計の図解ページ（GitHub Pages で公開） |

## 設計上の決まり

- AIに任せる仕事は5つのスキル（受付、聞き手、言い直し、調査、説明）に分ける。境界、受け渡す型、呼ぶ順序、失敗時の扱いは `docs/skill-architecture.md` が正。スキルを呼ぶ順序は通常のコード（`InquiryService` と `AnswerPipeline`）が決め、スキル同士に相談させない。
- スキルは、品質の基準、使える道具、失敗のしかたのどれかが違う単位で分ける。どれも変わらない分割はしない。
- 調査は事実と根拠を対（`Finding`）で返す。照合（`InvestigationVerifier`）で根拠がすべて落ちた事実は説明に渡さず、「根拠を確かめられなかったこと」として区別して残す。説明は照合の後に、確かめられた事実だけから書く。確かめられた事実が1つもなければ「わからない」として扱う。
- 機械的に確かめられること（根拠の照合、閲覧権限、回数の上限）はAIに任せず、通常のコードで行う。
- `core` パッケージは Spring にも HTTP にも依存させない。Slack の入口や評価の自動実行からも同じ処理を呼ぶため。
- 閲覧権限はプロンプトではなく検索の時点で適用する（`AccessScope`）。
- 種別ごとの経路は `InquiryCategory` に集約する。経路表を変えるときはテストも直す。
- 本番環境ではモックを有効にできない（`ProductionMockGuard` が起動時に検査する）。
- 秘密情報（Slack の Webhook の URL など）は設定ファイルに書かず、環境変数で与える。このリポジトリは公開されている。

## Quality Gate / Definition of Done

コード変更を完了と言う前に、ルートで `npm run verify` が通ること。

- `npm run verify:backend`: `./gradlew check`（コンパイルと JUnit）
- `npm run verify:frontend`: 型チェック、oxlint、Prettier、Vitest

画面か API の契約に触れたら `npm run smoke`（Playwright）も通す。純粋関数や型の不変条件を変えたら対応するテストを追加・更新する。

## ローカルでの起動

Docker を起動したうえで、ルートで次を実行する。

```bash
npm run dev:backend    # API（8080）。PostgreSQL のコンテナも自動で起動する
npm run dev:frontend   # 画面（5173）
npm run smoke          # 実ブラウザのスモーク。API と画面を自動で起動する
```

Gradle は必ず `scripts/gradle.sh` 経由で実行する。asdf の遅延初期化と OrbStack の環境で、`JAVA_HOME` と `DOCKER_HOST` を補うため。

画面と API の間の仕様は `docs/api-contract.md` が正。変更するときは両方の実装とテストを同時に直す。

画面の考え方、形、言葉づかいは `docs/ux-principles.md` が正。画面に内部の言葉（「0次回答」「種別」「起票」など）を出さない。

## スキルの評価

プロンプト、モデル、設定を変えるときは、目で見た印象ではなく評価で判断する。決まりは `docs/evaluation.md` が正。

```bash
npm run dev:backend:local-ai                             # 手元のモデル（Ollama）をつないで起動
npm run verify:live                                      # 実際のモデルを呼ぶ確認（通常の検証には含めない）
npm run eval -- -Pskill=all -Psplit=dev -Prepeat=3       # 調整用の例を、各3回ずつ測る
```

- 例は `evals/cases/`。実際の問い合わせから作った例は `evals/private/` に置く（公開しない）。
- プロンプトを直すときに見てよいのは、調整用の例（`dev`）の結果だけ。確認用の例（`holdout`）は、直した後に通った割合を見るだけにする。
- 出力は同じ条件でも揃わない（`temperature` 0、`seed` 固定でも、2回の実行で半数の文面が変わった）。1回の実行で1件だけ変わった合否を、改善や悪化の根拠にしない。比較は各例を3回以上測り、通った割合で行う。
- プロンプトの例文は、扱う業務と無関係な題材で書く。業務に近い例文は出力に紛れ込み、評価も甘くなる。
- プロンプトを変えるときは、新しい版のファイル（`system-v2.md`）を足し、評価で前の版と比べる。

## 現在の状態

このリポジトリの位置づけは骨組み（スケルトン）。問い合わせの流れのどこに、どのスキルを置くかが分かることを目的にしている。スキルの中身（プロンプトの品質、実際の資料の検索）を作り込む場所ではない。調査の評価の例は作らない。

既定ではすべてのスキルがモックで動き、外部のAIに接続しない。プロファイル `local-ai` で、5つのスキルをすべて手元のモデル（Ollama）で動かせる。

- 調査は、検索と読み取りの道具を繰り返し使って見本の資料を調べる（`OllamaInvestigatorSkill`）。回数や行番号など、モデルに任せずコードで決めていることは `docs/skill-architecture.md` の「調査の実装」が正。調査の評価の例はまだない。Bedrock を呼ぶ実装は未着手。
- 手元のモデルでの品質は、評価で測っている途中。訂正を含む言い直しと、根拠の薄いシステムの推測が弱い（`docs/evaluation.md` の決まりに沿って直す）。
- 聞き手の呼び出しが「出力を読めない」で時々失敗する（18回中3回起きたが、再現できていない）。理由と生の出力は記録するようにしてある。
- 一次情報の検索はモックだけ（`mock-knowledge/` の見本のファイルを読む）。調べられるのは、見本の資料に書かれていることだけ。実際のリポジトリを調べる実装は未着手。
- 担当者への通知は、ツール内（`/operator` の一覧と即時通知）と Slack（Incoming Webhook。投稿先が未設定なら投稿しない）。
- 送った後の往復がある。担当者の画面（`/operator`）と本人の「送った問い合わせ」（`/inquiries`）は、状態ごとの列にカードが並ぶボード。担当者はカードのドラッグか詳細のパネルで対応状況（未対応、対応中、完了）を変えて返信でき、本人は経過を見て追記できる。本人側は新しい返信を自動では取りにいかない（開き直したときに取得する）。
- 画面の最下部の「裏側を見る（開発者向け）」に、どのスキルがどの実装で動いたかを出す（設定 `inquiry.trace.enabled`。本番では無効にする）。
- 投稿者は固定ユーザー（`FixedCurrentUserProvider`）。担当者向けの API にも認証がない。画面に出る名前は設定の固定値（`inquiry.dev.*`）。SSO は後の段階で加える。
- 結果の `ABANDONED`（離脱）は自動では設定していない。