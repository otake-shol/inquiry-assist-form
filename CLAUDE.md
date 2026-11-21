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

画面に触れたら UI スモーク（Playwright、フェーズ2で追加）も通す。純粋関数や型の不変条件を変えたら対応するテストを追加・更新する。

## 現在の状態

フェーズ1（土台）まで。モック実装とフォーム画面はフェーズ2で作る。
