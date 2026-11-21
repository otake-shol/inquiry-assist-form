# inquiry-assist-form

社内メンバーの悩みを答えられる問いに整形し、ソースコードとFAQを根拠にした0次回答を返す問い合わせフォーム。

要件定義と実装計画: https://claude.ai/artifact/6ZYgbeXb2hwfhN9jN7F8pi

## 構成

| 場所 | 内容 |
| --- | --- |
| `backend/` | Spring Boot（Java 21、Gradle Kotlin DSL） |
| `backend/src/main/java/dev/inquiryassist/core/` | AI処理の境界と型。画面・HTTP・Spring に依存させない |
| `backend/src/main/resources/db/migration/` | 保存スキーマ（Flyway。接続はフェーズ3で追加） |
| `frontend/` | React + TypeScript + Vite（SPA） |

## 設計上の決まり

- AI処理は `InquiryAssistant` と `KnowledgeSearch` の2つの境界で差し替える。モック実装と Bedrock 実装を Spring のプロファイル（`mock` / `bedrock`）で切り替える。
- `core` パッケージは Spring にも HTTP にも依存させない。Slack の入口や評価の自動実行からも同じ処理を呼ぶため。
- 0次回答の出典はサーバー側で実在を照合する。照合できた出典が1つもなければ「わからない」として扱う（`ZeroAnswer#withVerifiedCitations`）。
- 閲覧権限はプロンプトではなく検索の時点で適用する（`AccessScope`）。
- 種別ごとの経路は `InquiryCategory` に集約する。経路表を変えるときはテストも直す。
- 本番環境ではモックを有効にできないようにする（フェーズ3で起動時検査を追加）。

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

## 現在の状態

フェーズ3（モック、フォーム画面、保存と起票）まで。既定のプロファイルは `mock` で、AWS に接続せずに全経路が動く。

- 起票の通知はログ出力だけ（`LoggingFilingNotifier`）。起票先が決まったら `FilingNotifier` の実装を足す。
- 投稿者は固定ユーザー（`FixedCurrentUserProvider`）。SSO はフェーズ7。
- `bedrock` プロファイルは未実装（フェーズ4〜5）。
- 結果の `ABANDONED`（離脱）は自動では設定していない。
